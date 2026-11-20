package com.optionmgr.trade.service;

import com.optionmgr.calendar.service.TradingCalendarService;
import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.InsertionCheck;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.cash.service.NewLedgerEntry;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.SaleMatch;
import com.optionmgr.stock.domain.StockReplay;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.trade.domain.AssignmentTerms;
import com.optionmgr.trade.domain.CallAssignmentTerms;
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.ContractHistory;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ContractReplay;
import com.optionmgr.trade.domain.ExpirationTimes;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.domain.ReplayedTrade;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeFilter;
import com.optionmgr.trade.domain.TradeRejectionMessages;
import com.optionmgr.trade.domain.ViolationKind;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import com.optionmgr.trade.repository.OptionTradeSpecifications;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Option trades: record an opening trade (FEAT-001) or a closing trade (FEAT-004) together with its cash effect,
 * read one back, list them (FEAT-002).
 *
 * <p>Every write replays all trades of the contract with the new trade inserted ({@link ContractReplay}): the new
 * trade and every later trade of the contract must stay valid (Q7, Q21, Q23, Q27, Q39). Later closes may then cover
 * different lots; their realized P&amp;L is derived on read, their released frozen cash is stored in their ledger
 * entry and corrected in the same transaction when it changes.
 */
@Service
public class OptionTradeService {

    public static final String FUTURE_TIME_MESSAGE = "成交时间不能晚于当前时间";

    /** Ledger source types written for option trades and expiries; all reference option_trade.id. */
    static final Set<CashSourceType> TRADE_SOURCE_TYPES = Set.of(CashSourceType.OPTION_OPEN, CashSourceType.OPTION_CLOSE,
            CashSourceType.OPTION_EXPIRE, CashSourceType.OPTION_ASSIGN, CashSourceType.OPTION_EXERCISE);

    /** Trade list order: newest first, same instant: later entered (higher id) first. */
    /** Newest first, same instant: later entered first; FEAT-046 (Q229): then account id (ids are global). */
    static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("tradedAt"), Sort.Order.desc("id"), Sort.Order.asc("accountId"));

    private static final Predicate<ViolationKind> ANY = k -> true;
    private static final Predicate<ViolationKind> NOT_MULTIPLIER = k -> k != ViolationKind.MULTIPLIER_MISMATCH;
    private static final Predicate<ViolationKind> MULTIPLIER = k -> k == ViolationKind.MULTIPLIER_MISMATCH;

    private final OptionTradeRepository trades;
    private final CashLedgerService ledger;
    private final StockTradeRepository stockTrades;
    private final CoverageService coverage;
    private final TradingCalendarService calendar;
    private final CloseLotStore closeLots;
    private final Clock clock;

    public OptionTradeService(OptionTradeRepository trades, CashLedgerService ledger, StockTradeRepository stockTrades,
                              CoverageService coverage, TradingCalendarService calendar, CloseLotStore closeLots,
                              Clock clock) {
        this.closeLots = closeLots;
        this.calendar = calendar;
        this.coverage = coverage;
        this.trades = trades;
        this.ledger = ledger;
        this.stockTrades = stockTrades;
        this.clock = clock;
    }

    /**
     * An opening trade whose fields already passed request validation (web layer).
     *
     * @param tradedAtLocal New York wall-clock trade time
     * @param note          optional; blank is stored as null
     */
    public record OpenCommand(ContractKey contract, TradeAction action, int contracts, int multiplier,
                              BigDecimal netAmount, LocalDateTime tradedAtLocal, String note) {
        public OpenCommand {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(netAmount, "netAmount");
            Objects.requireNonNull(tradedAtLocal, "tradedAtLocal");
        }
    }

    /**
     * A closing trade (BTC / STC) whose fields already passed request validation; multiplier comes from the position.
     *
     * @param contracts   FIFO mode: required; with {@code lotTradeIds}: optional, must equal the selected lots' sum
     * @param lotTradeIds FEAT-051: the selected lots (non-empty); null = FIFO mode (Q257)
     */
    public record CloseCommand(ContractKey contract, TradeAction action, Integer contracts, BigDecimal netAmount,
                               LocalDateTime tradedAtLocal, String note, List<Long> lotTradeIds) {

        public CloseCommand(ContractKey contract, TradeAction action, int contracts, BigDecimal netAmount,
                            LocalDateTime tradedAtLocal, String note) {
            this(contract, action, Integer.valueOf(contracts), netAmount, tradedAtLocal, note, null);
        }

        public CloseCommand {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(netAmount, "netAmount");
            Objects.requireNonNull(tradedAtLocal, "tradedAtLocal");
            if (action.isOpening()) {
                throw new IllegalArgumentException("not a closing action: " + action);
            }
            if (lotTradeIds == null && contracts == null) {
                throw new IllegalArgumentException("contracts required without lotTradeIds");
            }
            lotTradeIds = lotTradeIds == null ? null : List.copyOf(lotTradeIds);
        }
    }

    /** One page of trades, see {@link #list}. {@code page} is 1-based. */
    public record TradePage(List<TradeView> items, int page, int size, long totalItems, int totalPages) {
    }

    /**
     * A stored trade with the ledger entry it produced (its cash and frozen-cash effect) and, for a close or an
     * expiry, the lots it currently covers (FIFO) with its realized P&amp;L. {@code ledgerEntry} is null only for an
     * expiry (EXP) that releases no frozen cash (FEAT-012).
     */
    public record TradeView(OptionTradeEntity trade, CashLedgerEntryEntity ledgerEntry, Optional<CloseMatch> closeMatch,
                            StockTradeEntity assignedLot, SaleMatch assignedSale) {

        public TradeView(OptionTradeEntity trade, CashLedgerEntryEntity ledgerEntry, Optional<CloseMatch> closeMatch) {
            this(trade, ledgerEntry, closeMatch, null, null);
        }

        /**
         * Figures of a record that buys shares under its current match: a short-put assignment (FEAT-013) or a
         * long-call exercise (FEAT-014; premium cash-signed, i.e. negative); empty for every other record.
         */
        public Optional<AssignmentTerms> buyLeg() {
            TradeAction a = trade.getAction();
            if (!a.hasStockLeg() || closeMatch.isEmpty() || !a.buysStock(trade.getOptionType())) {
                return Optional.empty();
            }
            return Optional.of(AssignmentTerms.of(trade.contract(), trade.getContracts(), trade.getMultiplier(),
                    trade.getAssignmentFee(), closeMatch.get()));
        }

        /**
         * Figures of a record that sells shares under its current match: a short-call assignment (FEAT-041) or a
         * long-put exercise (FEAT-014; premium cash-signed, i.e. negative); empty for every other record.
         */
        public Optional<CallAssignmentTerms> sellLeg() {
            TradeAction a = trade.getAction();
            if (!a.hasStockLeg() || closeMatch.isEmpty() || a.buysStock(trade.getOptionType())) {
                return Optional.empty();
            }
            return Optional.of(CallAssignmentTerms.of(trade.contract(), trade.getContracts(), trade.getMultiplier(),
                    trade.getAssignmentFee(), closeMatch.get()));
        }

        public BigDecimal cashChange() {
            return ledgerEntry == null ? Money.ZERO : ledgerEntry.getAmount();
        }

        public BigDecimal frozenChange() {
            return ledgerEntry == null ? Money.ZERO : ledgerEntry.getFrozenChange();
        }

        public Long ledgerEntryId() {
            return ledgerEntry == null ? null : ledgerEntry.getId();
        }
    }

    /**
     * All stored trades of one contract with their ledger entries, as replay input. An expiry without a ledger entry
     * (nothing released) is absent from {@code ledgerByTrade}.
     */
    record ContractTrades(List<LotTrade> trades, Map<Long, CashLedgerEntryEntity> ledgerByTrade,
                          Map<Long, BigDecimal> assignmentFees) {
    }

    /**
     * FEAT-013: a stored assignment whose generated stock lot gets another cost under a new history (its assigned
     * lots or their premiums changed).
     */
    record CostCorrection(long assignmentId, TradeAction action, ContractKey contract, StockTradeEntity lot,
                          BigDecimal before, BigDecimal after) {

        /**
         * FEAT-041 call assignment / FEAT-014 put exercise: the corrected amount is the generated sale's proceeds,
         * not a generated lot's cost.
         */
        boolean call() {
            return !action.buysStock(contract.optionType());
        }
    }

    // ------------------------------------------------------------------ open (FEAT-001)

    /**
     * Checks in the order of FEAT-001 (fields, Q7 incl. later trades, Q23, cash; FEAT-038: no Q16) and saves trade + ledger entry
     * in one transaction; any failure throws {@link ApiException} and nothing is saved.
     */
    @Transactional
    public TradeView open(long accountId, OpenCommand cmd) {
        return open(accountId, cmd, false);
    }

    /**
     * As {@link #open(long, OpenCommand)}; {@code legacy} (test support only, e2e): an STO call stored as entered
     * before FEAT-039 - no coverage check (E1 / E3), saved LEGACY, then evaluated by the automatic inclusion.
     */
    @Transactional
    public TradeView open(long accountId, OpenCommand cmd, boolean legacy) {
        // 1. field rules that need the clock / the trade's New York date
        Instant tradedAt = MarketTime.toInstant(cmd.tradedAtLocal());
        LocalDate tradeDate = MarketTime.toLocal(tradedAt).toLocalDate();
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        if (cmd.contract().expirationDate().isBefore(tradeDate)) {
            errors.add(new ErrorResponse.FieldError("expirationDate", "到期日不能早于成交日（" + tradeDate + "）"));
        } else {
            checkNotAfterCutoff(cmd.contract(), tradedAt, errors);
        }
        checkNotFuture(tradedAt, errors);
        // FEAT-009 Q126: a new opening trade must expire on a US trading day (not for test-support legacy rows)
        if (!legacy) {
            calendar.requireTradingDayForOpen(cmd.contract().expirationDate());
        }
        OpenTradeTerms terms = new OpenTradeTerms(cmd.contract(), cmd.action(), cmd.contracts(), cmd.multiplier(),
                cmd.netAmount());
        String note = blankToNull(cmd.note());

        // Every cash-changing transaction holds the account lock: replay and cash checks see a stable state.
        ledger.lockAccount(accountId);
        ContractKey contract = terms.contract();
        ContractTrades existing = load(accountId, contract);
        LotTrade candidate = new LotTrade(LotTrade.NOT_YET_ENTERED, tradedAt, terms.action(), terms.contracts(),
                terms.multiplier(), terms.cashChange(), terms.frozenChange(), note);
        ContractHistory history = replayWith(existing, candidate);

        // 2. Q7 at the trade, and every later trade of the contract still valid (Q27/Q39)
        rejectFirstViolation(contract, history, NOT_MULTIPLIER);
        // 3. Q23 (FEAT-038: STO call is allowed - Q16 superseded; share coverage: FEAT-039 below)
        rejectFirstViolation(contract, history, MULTIPLIER);
        // FEAT-013: later assignments re-matched to other lots keep a positive stock cost
        List<CostCorrection> costs = costCorrections(accountId, contract, existing, history, LotTrade.NOT_YET_ENTERED);
        rejectNonPositiveCost(costs, "补录");
        // FEAT-039: E1 at the new STO call, then E3 for later points
        if (!legacy && terms.action() == TradeAction.STO && contract.optionType() == OptionType.CALL) {
            List<LotTrade> all = new ArrayList<>(existing.trades());
            all.add(candidate);
            CoverageService.Snapshot snapshot = coverage.snapshot(accountId, contract.symbol(),
                    CoverageService.Proposal.contract(contract, all));
            coverage.checkCallOpen(snapshot, LotTrade.NOT_YET_ENTERED);
            coverage.checkInvariant(snapshot, tradedAt, "补录");
        }

        // 4. cash (Q13, Q18 for STO put only, Q19), with later closes' re-matched frozen release.
        // A short call freezes nothing (OpenTradeTerms.freezesCollateral), so it only adds cash.
        Map<Long, BigDecimal> corrections = frozenCorrections(existing, history);
        checkOpenCash(accountId, terms, tradedAt, corrections);
        applyCostCorrections(costs);

        OptionTradeEntity trade = trades.save(new OptionTradeEntity(accountId, contract, terms.action(), terms.contracts(),
                terms.multiplier(), terms.netAmount(), tradedAt, note, clock.instant()));
        CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(accountId, tradedAt, CashSourceType.OPTION_OPEN,
                trade.getId(), terms.cashChange(), terms.frozenChange(), terms.description(), note));
        ledger.correctFrozenChanges(accountId, corrections);
        if (legacy && trade.getCoverageStatus() != null) {
            trade.markCoverageLegacy();
        }
        coverage.includeLegacy(accountId, contract.symbol());
        return new TradeView(trade, entry, Optional.empty());
    }

    // ------------------------------------------------------------------ close (FEAT-004)

    /**
     * Checks in the order of FEAT-004 (fields incl. Q36, position/direction, whole lots, later trades, cash with
     * Q20 release first) and saves trade + ledger entry; any failure throws {@link ApiException}, nothing is saved.
     */
    @Transactional
    public TradeView close(long accountId, CloseCommand cmd) {
        ContractKey contract = cmd.contract();
        // 1. field rules that need the clock / the trade's New York date
        Instant tradedAt = MarketTime.toInstant(cmd.tradedAtLocal());
        LocalDate tradeDate = MarketTime.toLocal(tradedAt).toLocalDate();
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        if (tradeDate.isAfter(contract.expirationDate())) {
            errors.add(new ErrorResponse.FieldError("tradedAtLocal",
                    "到期日（" + contract.expirationDate() + "）之后不能平仓"));
        } else {
            checkNotAfterCutoff(contract, tradedAt, errors);
        }
        checkNotFuture(tradedAt, errors);
        BigDecimal net = Money.of(cmd.netAmount());
        BigDecimal cashChange = cmd.action().receivesCash() ? net : net.negate();
        String note = blankToNull(cmd.note());

        ledger.lockAccount(accountId);
        ContractTrades existing = load(accountId, contract);
        // FEAT-051: with a lot selection the close's contracts are the selected lots' sum (rule 2)
        List<Long> selection = cmd.lotTradeIds() == null ? null : distinct(cmd.lotTradeIds());
        int contracts = selection == null ? cmd.contracts() : selectedContracts(existing, selection);
        LotTrade candidate = new LotTrade(LotTrade.NOT_YET_ENTERED, tradedAt, cmd.action(), contracts,
                LotTrade.INHERIT_MULTIPLIER, cashChange, Money.ZERO, note, selection);
        ContractHistory history = replayWith(existing, candidate);
        ReplayedTrade step = history.step(LotTrade.NOT_YET_ENTERED);

        // 2. position and direction; 3. lots (FEAT-051 a-e, or FIFO whole lots); 4. every later trade (time order)
        if (selection != null) {
            rejectPositionProblem(contract, step);
            checkSelection(contract, existing, cmd.lotTradeIds(), step, cmd.contracts());
        }
        rejectFirstViolation(contract, history, ANY);
        CloseMatch match = step.match().orElseThrow();
        BigDecimal frozenChange = match.releasedFrozen().negate();
        List<CostCorrection> costs = costCorrections(accountId, contract, existing, history, LotTrade.NOT_YET_ENTERED);
        rejectNonPositiveCost(costs, "补录");

        // 5. cash: release first, then check (Q20); Q19 replay for later points
        Map<Long, BigDecimal> corrections = frozenCorrections(existing, history);
        InsertionCheck check = ledger.checkInsertion(accountId, tradedAt, cashChange, frozenChange, corrections);
        if (!check.allowed()) {
            String message;
            if (check.violatedAtInsertedPoint()) {
                BigDecimal available = check.inserted().before().available();
                message = match.releasedFrozen().signum() > 0
                        ? CashShortfallMessages.notEnoughWithRelease(net, available, match.releasedFrozen())
                        : CashShortfallMessages.notEnoughNow(CashShortfallMessages.INSUFFICIENT, net, available);
            } else {
                message = CashShortfallMessages.insufficient(net, check);
            }
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT, message);
        }

        int multiplier = step.effectiveMultiplier();
        OptionTradeEntity trade = trades.save(new OptionTradeEntity(accountId, contract, cmd.action(), contracts,
                multiplier, net, tradedAt, note, clock.instant()));
        CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(accountId, tradedAt, CashSourceType.OPTION_CLOSE,
                trade.getId(), cashChange, frozenChange,
                OpenTradeTerms.describe(cmd.action(), contracts, multiplier, contract), note));
        // Q255 / Q257: both modes store the lots finally matched as the close's selection
        closeLots.replace(accountId, trade.getId(), lotIds(match));
        ledger.correctFrozenChanges(accountId, corrections);
        applyCostCorrections(costs);
        coverage.includeLegacy(accountId, contract.symbol());
        return new TradeView(trade, entry, Optional.of(match));
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public TradeView get(long accountId, long id) {
        OptionTradeEntity trade = trades.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> ApiException.notFound("Option trade " + id + " not found"));
        return views(accountId, List.of(trade)).get(0);
    }

    /** One page of the trade list (FEAT-002): newest first, same instant: later entered first. */
    @Transactional(readOnly = true)
    public TradePage list(long accountId, TradeFilter filter, int page, int size) {
        return list(List.of(accountId), filter, page, size);
    }

    /**
     * FEAT-046: one page of the trade list of several accounts - one database query over all of them (pagination of
     * the merged list); each row's figures are computed in its own account (Q53), the rows keep the page order.
     */
    @Transactional(readOnly = true)
    public TradePage list(List<Long> accountIds, TradeFilter filter, int page, int size) {
        Page<OptionTradeEntity> result = trades.findAll(OptionTradeSpecifications.matching(accountIds, filter),
                PageRequest.of(page - 1, size, LIST_ORDER));
        return new TradePage(viewsByAccount(result.getContent()), page, size, result.getTotalElements(),
                result.getTotalPages());
    }

    /** Views of trades of possibly several accounts, each computed in its own account, in the given order. */
    private List<TradeView> viewsByAccount(List<OptionTradeEntity> page) {
        Map<Long, List<OptionTradeEntity>> byAccount = new LinkedHashMap<>();
        page.forEach(t -> byAccount.computeIfAbsent(t.getAccountId(), a -> new ArrayList<>()).add(t));
        Map<Long, TradeView> byId = new HashMap<>();
        byAccount.forEach((accountId, rows) -> views(accountId, rows)
                .forEach(v -> byId.put(v.trade().getId(), v)));
        return page.stream().map(t -> byId.get(t.getId())).toList();
    }

    /** All stored trades of one contract with their ledger entries, in replay order. */
    @Transactional(readOnly = true)
    public List<LotTrade> contractTrades(long accountId, ContractKey contract) {
        return load(accountId, contract).trades();
    }

    /** Views of trades of one account. */
    List<TradeView> views(long accountId, List<OptionTradeEntity> page) {
        Map<Long, CashLedgerEntryEntity> entries = ledger.findBySources(accountId, TRADE_SOURCE_TYPES,
                page.stream().map(OptionTradeEntity::getId).toList());
        Map<Long, StockTradeEntity> lots = assignedLots(accountId, page.stream()
                .filter(t -> t.getAction().hasStockLeg()).map(OptionTradeEntity::getId).toList());
        // closes: their current FIFO match, one replay per contract
        Map<ContractKey, ContractHistory> histories = new HashMap<>();
        // FEAT-041: call assignments' delivered shares, one stock replay per symbol
        Map<String, StockReplay.History> stockHistories = new HashMap<>();
        List<TradeView> out = new ArrayList<>(page.size());
        for (OptionTradeEntity t : page) {
            CashLedgerEntryEntity e = entry(t, entries);
            Optional<CloseMatch> match = Optional.empty();
            if (!t.getAction().isOpening()) {
                ContractHistory h = histories.computeIfAbsent(t.contract(),
                        c -> ContractReplay.replay(load(accountId, c).trades()));
                match = h.step(t.getId()).match();
            }
            StockTradeEntity lot = lots.get(t.getId());
            SaleMatch sale = null;
            if (lot != null && lot.getType().isSale()) {
                sale = stockHistories.computeIfAbsent(lot.getSymbol(), s -> StockReplay.replay(
                                stockTrades.findByAccountIdAndSymbolOrderByTradedAtAscIdAsc(accountId, s).stream()
                                        .map(StockTradeEntity::toRecord).toList()))
                        .step(lot.getId()).flatMap(StockReplay.Step::sale).orElse(null);
            }
            out.add(new TradeView(t, e, match, lot, sale));
        }
        return out;
    }

    /** FEAT-013: the ASSIGN_BUY lot of each given assignment record (by option trade id). */
    Map<Long, StockTradeEntity> assignedLots(long accountId, List<Long> assignmentIds) {
        Map<Long, StockTradeEntity> out = new HashMap<>();
        if (!assignmentIds.isEmpty()) {
            for (StockTradeEntity s : stockTrades.findByAccountIdAndOptionTradeIdIn(accountId, assignmentIds)) {
                out.put(s.getOptionTradeId(), s);
            }
        }
        return out;
    }

    /**
     * FEAT-013: stored assignments (other than {@code excludedId} and a record not stored yet) whose generated lot's
     * cost differs under {@code history}: stock cost = strike x shares (rounded up) - premium of the assigned lots + fee.
     */
    List<CostCorrection> costCorrections(long accountId, ContractKey contract, ContractTrades existing,
                                         ContractHistory history, long excludedId) {
        List<ReplayedTrade> assignments = history.steps().stream()
                .filter(s -> s.trade().action().hasStockLeg() && s.match().isPresent()
                        && s.trade().tradeId() != LotTrade.NOT_YET_ENTERED && s.trade().tradeId() != excludedId)
                .toList();
        if (assignments.isEmpty()) {
            return List.of();
        }
        Map<Long, StockTradeEntity> lots = assignedLots(accountId,
                assignments.stream().map(s -> s.trade().tradeId()).toList());
        List<CostCorrection> out = new ArrayList<>();
        for (ReplayedTrade s : assignments) {
            long id = s.trade().tradeId();
            StockTradeEntity lot = lots.get(id);
            if (lot == null) {
                throw new IllegalStateException("assigned stock lot missing for assignment " + id);
            }
            // FEAT-041 / FEAT-014: a call assignment's / put exercise's generated record holds its sale proceeds
            TradeAction action = s.trade().action();
            BigDecimal cost = !action.buysStock(contract.optionType())
                    ? CallAssignmentTerms.of(contract, s.trade().contracts(), s.trade().multiplier(),
                            existing.assignmentFees().get(id), s.match().get()).proceeds()
                    : AssignmentTerms.of(contract, s.trade().contracts(), s.trade().multiplier(),
                            existing.assignmentFees().get(id), s.match().get()).stockCost();
            if (cost.compareTo(lot.getAmount()) != 0) {
                out.add(new CostCorrection(id, action, contract, lot, Money.of(lot.getAmount()), cost));
            }
        }
        return out;
    }

    /** "<verb>后被指派 #9 的股票成本将为 −12.00，请先删除该被指派" for the first correction that is not &gt; 0. */
    static void rejectNonPositiveCost(List<CostCorrection> costs, String verb) {
        costs.stream().filter(c -> c.after().signum() <= 0).findFirst().ifPresent(c -> {
            String name = c.action().displayName();
            if (c.call()) {
                // FEAT-041: "<verb>后被指派 #4 的股票卖出实收将为 −1.00，请先删除该被指派"; FEAT-014: "…行权 #5 …该行权"
                throw new ApiException(c.action() == TradeAction.EXR ? ErrorCode.EXERCISE_PROCEEDS_NOT_POSITIVE
                        : ErrorCode.ASSIGNMENT_PROCEEDS_NOT_POSITIVE, verb + "后" + name + " #"
                        + c.assignmentId() + " 的股票卖出实收将为 " + Money.display(c.after()) + "，请先删除该" + name);
            }
            throw new ApiException(ErrorCode.STOCK_COST_NOT_POSITIVE, verb + "后" + name + " #" + c.assignmentId()
                    + " 的股票成本将为 " + Money.display(c.after()) + "，请先删除该" + name);
        });
    }

    static void applyCostCorrections(List<CostCorrection> costs) {
        costs.forEach(c -> c.lot().correctCost(c.after()));
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ FEAT-051 selected lots

    /** The ids in request order without repeats. */
    static List<Long> distinct(List<Long> ids) {
        return List.copyOf(new java.util.LinkedHashSet<>(ids));
    }

    /** Current contracts of the selected opening trades of the contract (unknown ids count 0); at least 1. */
    static int selectedContracts(ContractTrades existing, List<Long> selection) {
        Map<Long, LotTrade> opens = opens(existing);
        int sum = selection.stream().map(opens::get).filter(Objects::nonNull).mapToInt(LotTrade::contracts).sum();
        return Math.max(sum, 1); // a selection with no lot of the contract is rejected by check a
    }

    /** The contract's opening trades by id. */
    static Map<Long, LotTrade> opens(ContractTrades existing) {
        Map<Long, LotTrade> out = new HashMap<>();
        existing.trades().stream().filter(t -> t.action().isOpening()).forEach(t -> out.put(t.tradeId(), t));
        return out;
    }

    /** Ids of the lots a close covers (FIFO order of the lots). */
    static List<Long> lotIds(CloseMatch match) {
        return match.coveredLots().stream().map(PositionLot::tradeId).toList();
    }

    /** FEAT-004 step 2 for the close being recorded / edited: position and direction at its point. */
    static void rejectPositionProblem(ContractKey contract, ReplayedTrade step) {
        for (ViolationKind k : List.of(ViolationKind.NO_POSITION, ViolationKind.CLOSE_WRONG_DIRECTION)) {
            if (step.violations().contains(k)) {
                throw new ApiException(errorCode(k), TradeRejectionMessages.of(contract, step, k, true));
            }
        }
    }

    /**
     * FEAT-051 step 3 a-e for the close being recorded / edited ({@code step}: its replay step, made with the
     * requested selection without repeats), each in the order of {@code requested} (null = selection unchanged:
     * only c-e).
     */
    static void checkSelection(ContractKey contract, ContractTrades existing, List<Long> requested,
                               ReplayedTrade step, Integer requestedContracts) {
        Map<Long, LotTrade> opens = opens(existing);
        List<Long> order = requested != null ? requested : step.trade().lotTradeIds();
        if (requested != null) {
            // a. an opening trade of this account's contract (only the id is named)
            for (Long id : requested) {
                if (!opens.containsKey(id)) {
                    throw new ApiException(ErrorCode.CLOSE_LOTS_INVALID, TradeRejectionMessages.lotNotInContract(contract, id));
                }
            }
            // b. no repeats
            Set<Long> seen = new java.util.HashSet<>();
            for (Long id : requested) {
                if (!seen.add(id)) {
                    throw new ApiException(ErrorCode.CLOSE_LOTS_INVALID,
                            TradeRejectionMessages.lotSelectedTwice(opens.get(id)));
                }
            }
        }
        // c. open at the close's point, d. not closed before it
        Map<Long, com.optionmgr.trade.domain.LotProblem> problems = new HashMap<>();
        step.lotProblems().forEach(p -> problems.putIfAbsent(p.lotTradeId(), p));
        for (com.optionmgr.trade.domain.LotProblem.Reason reason : List.of(
                com.optionmgr.trade.domain.LotProblem.Reason.NOT_YET_OPEN,
                com.optionmgr.trade.domain.LotProblem.Reason.ALREADY_CLOSED)) {
            for (Long id : order) {
                com.optionmgr.trade.domain.LotProblem p = problems.get(id);
                if (p != null && p.reason() == reason) {
                    throw new ApiException(ErrorCode.CLOSE_LOTS_INVALID,
                            TradeRejectionMessages.selectedLotProblem(p, step.trade()));
                }
            }
        }
        // e. contracts, when given, = the selected lots' sum (stored lots may have changed size: same check)
        int sum = order.stream().distinct().map(opens::get).filter(Objects::nonNull).mapToInt(LotTrade::contracts).sum();
        if (requestedContracts != null && requestedContracts != sum) {
            throw new ApiException(ErrorCode.CLOSE_LOTS_INVALID,
                    TradeRejectionMessages.closeContractsMismatch(requestedContracts, sum));
        }
        step.lotProblems().stream().findFirst().ifPresent(p -> {
            throw new ApiException(ErrorCode.CLOSE_LOTS_INVALID, TradeRejectionMessages.selectedLotProblem(p, step.trade()));
        });
    }

    /** FEAT-051 (Q256): the lots a close may select at {@code tradedAt} (null = its current time). */
    public record LotChoices(long closeTradeId, Instant tradedAt, List<PositionLot> lots, Set<Long> selected) {
    }

    /**
     * FEAT-051: the lots held at the close's point once the close itself is taken out of the replay, minus the lots
     * other BTC / STC have selected (stored); in FIFO order.
     */
    @Transactional(readOnly = true)
    public LotChoices closeLotChoices(long accountId, long closeId, LocalDateTime tradedAtLocal) {
        OptionTradeEntity trade = trades.findByIdAndAccountId(closeId, accountId)
                .orElseThrow(() -> ApiException.notFound("Option trade " + closeId + " not found"));
        if (!trade.getAction().isUserClose()) {
            throw ApiException.businessRule("只有平仓（BTC / STC）可以选择批次");
        }
        Instant at = tradedAtLocal == null ? trade.getTradedAt() : MarketTime.toInstant(tradedAtLocal);
        ContractTrades existing = load(accountId, trade.contract());
        List<LotTrade> others = new ArrayList<>();
        Set<Long> selected = Set.of();
        Set<Long> takenByOthers = new java.util.HashSet<>();
        for (LotTrade t : existing.trades()) {
            if (t.tradeId() == closeId) {
                selected = t.lotTradeIds() == null ? Set.of() : Set.copyOf(t.lotTradeIds());
                continue;
            }
            if (t.lotTradeIds() != null) {
                takenByOthers.addAll(t.lotTradeIds());
            }
            others.add(t);
        }
        // a probe at the close's (new) point and entry order: its step's lotsBefore are the lots held there
        others.add(new LotTrade(closeId, at, trade.getAction(), 1, LotTrade.INHERIT_MULTIPLIER, Money.ZERO,
                Money.ZERO, null, List.of()));
        List<PositionLot> held = ContractReplay.replay(others).step(closeId).lotsBefore();
        return new LotChoices(closeId, at,
                held.stream().filter(l -> !takenByOthers.contains(l.tradeId())).toList(), selected);
    }

    ContractTrades load(long accountId, ContractKey c) {
        List<OptionTradeEntity> entities = trades
                .findByAccountIdAndSymbolAndExpirationDateAndStrikeAndOptionTypeOrderByTradedAtAscIdAsc(
                        accountId, c.symbol(), c.expirationDate(), c.strike(), c.optionType());
        Map<Long, CashLedgerEntryEntity> entries = ledger.findBySources(accountId, TRADE_SOURCE_TYPES,
                entities.stream().map(OptionTradeEntity::getId).toList());
        Map<Long, List<Long>> selections = closeLots.forCloses(entities.stream()
                .filter(t -> t.getAction().isUserClose()).map(OptionTradeEntity::getId).toList());
        Map<Long, CashLedgerEntryEntity> byTrade = new LinkedHashMap<>();
        Map<Long, BigDecimal> fees = new HashMap<>();
        List<LotTrade> lotTrades = new ArrayList<>(entities.size());
        for (OptionTradeEntity t : entities) {
            CashLedgerEntryEntity e = entry(t, entries);
            if (e != null) {
                byTrade.put(t.getId(), e);
            }
            if (t.getAction().hasStockLeg()) {
                fees.put(t.getId(), t.getAssignmentFee());
            }
            lotTrades.add(toLotTrade(t, e, selections.get(t.getId())));
        }
        return new ContractTrades(lotTrades, byTrade, fees);
    }

    /**
     * The ledger entry of a stored record; null only for an expiry that releases nothing (FEAT-012). Every trade has
     * one.
     */
    static CashLedgerEntryEntity entry(OptionTradeEntity t, Map<Long, CashLedgerEntryEntity> entries) {
        CashLedgerEntryEntity e = entries.get(t.getId());
        if (e == null && t.getAction() != TradeAction.EXP) {
            throw new IllegalStateException("ledger entry missing for option trade " + t.getId());
        }
        return e;
    }

    /**
     * Replay input of a stored trade: cash and (for opens) frozen cash as recorded in its ledger entry. An expiry
     * without entry ({@code e} null) has no cash effect. {@code selectedLots}: a BTC / STC's stored selection
     * (FEAT-051; null = FIFO, only for data without a selection).
     */
    static LotTrade toLotTrade(OptionTradeEntity t, CashLedgerEntryEntity e, List<Long> selectedLots) {
        // an assignment's / exercise's ledger amount is the stock leg; the option side itself has no cash
        BigDecimal cash = e == null || t.getAction().hasStockLeg() ? Money.ZERO : e.getAmount();
        return new LotTrade(t.getId(), t.getTradedAt(), t.getAction(), t.getContracts(), t.getMultiplier(),
                cash, t.getAction().isOpening() ? e.getFrozenChange() : Money.ZERO, t.getNote(), selectedLots);
    }

    static ContractHistory replayWith(ContractTrades existing, LotTrade candidate) {
        List<LotTrade> all = new ArrayList<>(existing.trades());
        all.add(candidate);
        return ContractReplay.replay(all);
    }

    static void rejectFirstViolation(ContractKey contract, ContractHistory history,
                                             Predicate<ViolationKind> kinds) {
        history.firstViolationFrom(LotTrade.NOT_YET_ENTERED, kinds).ifPresent(step -> {
            ViolationKind kind = ContractHistory.primaryViolation(step, kinds);
            boolean atNewTrade = step.trade().tradeId() == LotTrade.NOT_YET_ENTERED;
            throw new ApiException(errorCode(kind), TradeRejectionMessages.of(contract, step, kind, atNewTrade));
        });
    }

    static ErrorCode errorCode(ViolationKind kind) {
        return switch (kind) {
            case OPEN_AGAINST_POSITION, CLOSE_WRONG_DIRECTION -> ErrorCode.POSITION_DIRECTION_CONFLICT;
            case MULTIPLIER_MISMATCH -> ErrorCode.MULTIPLIER_MISMATCH;
            case NO_POSITION -> ErrorCode.NO_POSITION_TO_CLOSE;
            case CLOSE_EXCEEDS_POSITION -> ErrorCode.CLOSE_EXCEEDS_POSITION;
            case CLOSE_NOT_WHOLE_LOTS -> ErrorCode.CLOSE_NOT_WHOLE_LOTS;
            case CLOSE_LOTS_INVALID -> ErrorCode.CLOSE_LOTS_INVALID;
        };
    }

    /**
     * Q39: existing closes whose released frozen cash differs under the new FIFO matching
     * (ledger entry id -&gt; new frozen change). Their cash amount never changes.
     */
    static Map<Long, BigDecimal> frozenCorrections(ContractTrades existing, ContractHistory history) {
        return frozenCorrections(existing, history, LotTrade.NOT_YET_ENTERED);
    }

    /** As above, ignoring the trade {@code excludedTradeId} (an edited close gets its new frozen change separately). */
    static Map<Long, BigDecimal> frozenCorrections(ContractTrades existing, ContractHistory history,
                                                   long excludedTradeId) {
        Map<Long, BigDecimal> corrections = new HashMap<>();
        for (ReplayedTrade s : history.steps()) {
            long id = s.trade().tradeId();
            if (id == LotTrade.NOT_YET_ENTERED || id == excludedTradeId || s.trade().action().isOpening()
                    || s.match().isEmpty()) {
                continue;
            }
            CashLedgerEntryEntity e = existing.ledgerByTrade().get(id);
            BigDecimal expected = s.match().get().releasedFrozen().negate();
            if (e == null) {
                // an expiry without ledger entry (short call / long): its lots never hold frozen cash
                if (expected.signum() != 0) {
                    throw new IllegalStateException("expiry " + id + " would release frozen cash but has no ledger entry");
                }
                continue;
            }
            if (e.getFrozenChange().compareTo(expected) != 0) {
                corrections.put(e.getId(), expected);
            }
        }
        return corrections;
    }

    /**
     * Q83 (FEAT-012): on the expiration date, a trade must not be later than 16:00:00 New York. Applies to the trade
     * being recorded or edited only; stored trades are never rejected retroactively.
     */
    static void checkNotAfterCutoff(ContractKey contract, Instant tradedAt, List<ErrorResponse.FieldError> errors) {
        if (ExpirationTimes.afterCutoff(contract.expirationDate(), tradedAt)) {
            errors.add(new ErrorResponse.FieldError("tradedAtLocal",
                    ExpirationTimes.afterCutoffMessage(contract.expirationDate())));
        }
    }

    private void checkNotFuture(Instant tradedAt, List<ErrorResponse.FieldError> errors) {
        if (tradedAt.isAfter(clock.instant())) {
            errors.add(new ErrorResponse.FieldError("tradedAtLocal", FUTURE_TIME_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed", errors);
        }
    }

    private void checkOpenCash(long accountId, OpenTradeTerms terms, Instant tradedAt, Map<Long, BigDecimal> corrections) {
        InsertionCheck check = ledger.checkInsertion(accountId, tradedAt, terms.cashChange(), terms.frozenChange(),
                corrections);
        if (terms.freezesCollateral()) {
            // Q18: available cash before this trade (premium not counted) must cover the collateral.
            BigDecimal collateral = terms.collateral();
            BigDecimal availableBefore = check.inserted().before().available();
            if (availableBefore.compareTo(collateral) < 0) {
                throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.notEnoughNow(
                        CashShortfallMessages.INSUFFICIENT_FOR_PUT_COLLATERAL, collateral, availableBefore));
            }
            if (!check.allowed()) {
                throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.insufficient(
                        CashShortfallMessages.INSUFFICIENT_FOR_PUT_COLLATERAL, collateral, check));
            }
            return;
        }
        if (!check.allowed()) {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT,
                    CashShortfallMessages.insufficient(terms.netAmount(), check));
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
