package com.optionmgr.trade.service;

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
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.ContractHistory;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ContractReplay;
import com.optionmgr.trade.domain.ExpirationTimes;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OpenTradeTerms;
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
            CashSourceType.OPTION_EXPIRE);

    /** Trade list order: newest first, same instant: later entered (higher id) first. */
    static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("tradedAt"), Sort.Order.desc("id"));

    private static final Predicate<ViolationKind> ANY = k -> true;
    private static final Predicate<ViolationKind> NOT_MULTIPLIER = k -> k != ViolationKind.MULTIPLIER_MISMATCH;
    private static final Predicate<ViolationKind> MULTIPLIER = k -> k == ViolationKind.MULTIPLIER_MISMATCH;

    private final OptionTradeRepository trades;
    private final CashLedgerService ledger;
    private final Clock clock;

    public OptionTradeService(OptionTradeRepository trades, CashLedgerService ledger, Clock clock) {
        this.trades = trades;
        this.ledger = ledger;
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

    /** A closing trade (BTC / STC) whose fields already passed request validation; multiplier comes from the position. */
    public record CloseCommand(ContractKey contract, TradeAction action, int contracts, BigDecimal netAmount,
                               LocalDateTime tradedAtLocal, String note) {
        public CloseCommand {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(netAmount, "netAmount");
            Objects.requireNonNull(tradedAtLocal, "tradedAtLocal");
            if (action.isOpening()) {
                throw new IllegalArgumentException("not a closing action: " + action);
            }
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
    public record TradeView(OptionTradeEntity trade, CashLedgerEntryEntity ledgerEntry, Optional<CloseMatch> closeMatch) {

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
    record ContractTrades(List<LotTrade> trades, Map<Long, CashLedgerEntryEntity> ledgerByTrade) {
    }

    // ------------------------------------------------------------------ open (FEAT-001)

    /**
     * Checks in the order of FEAT-001 (fields, Q7 incl. later trades, Q23, cash; FEAT-038: no Q16) and saves trade + ledger entry
     * in one transaction; any failure throws {@link ApiException} and nothing is saved.
     */
    @Transactional
    public TradeView open(long accountId, OpenCommand cmd) {
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
        // 3. Q23 (FEAT-038: STO call is allowed - Q16 superseded; no share-coverage check before FEAT-011)
        rejectFirstViolation(contract, history, MULTIPLIER);

        // 4. cash (Q13, Q18 for STO put only, Q19), with later closes' re-matched frozen release.
        // A short call freezes nothing (OpenTradeTerms.freezesCollateral), so it only adds cash.
        Map<Long, BigDecimal> corrections = frozenCorrections(existing, history);
        checkOpenCash(accountId, terms, tradedAt, corrections);

        OptionTradeEntity trade = trades.save(new OptionTradeEntity(accountId, contract, terms.action(), terms.contracts(),
                terms.multiplier(), terms.netAmount(), tradedAt, note, clock.instant()));
        CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(accountId, tradedAt, CashSourceType.OPTION_OPEN,
                trade.getId(), terms.cashChange(), terms.frozenChange(), terms.description(), note));
        ledger.correctFrozenChanges(accountId, corrections);
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
        LotTrade candidate = new LotTrade(LotTrade.NOT_YET_ENTERED, tradedAt, cmd.action(), cmd.contracts(),
                LotTrade.INHERIT_MULTIPLIER, cashChange, Money.ZERO, note);
        ContractHistory history = replayWith(existing, candidate);

        // 2.-4. position and direction, whole lots, then every later trade of the contract (time order)
        rejectFirstViolation(contract, history, ANY);
        ReplayedTrade step = history.step(LotTrade.NOT_YET_ENTERED);
        CloseMatch match = step.match().orElseThrow();
        BigDecimal frozenChange = match.releasedFrozen().negate();

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
        OptionTradeEntity trade = trades.save(new OptionTradeEntity(accountId, contract, cmd.action(), cmd.contracts(),
                multiplier, net, tradedAt, note, clock.instant()));
        CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(accountId, tradedAt, CashSourceType.OPTION_CLOSE,
                trade.getId(), cashChange, frozenChange,
                OpenTradeTerms.describe(cmd.action(), cmd.contracts(), multiplier, contract), note));
        ledger.correctFrozenChanges(accountId, corrections);
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
        Page<OptionTradeEntity> result = trades.findAll(OptionTradeSpecifications.matching(accountId, filter),
                PageRequest.of(page - 1, size, LIST_ORDER));
        return new TradePage(views(accountId, result.getContent()), page, size, result.getTotalElements(),
                result.getTotalPages());
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
        // closes: their current FIFO match, one replay per contract
        Map<ContractKey, ContractHistory> histories = new HashMap<>();
        List<TradeView> out = new ArrayList<>(page.size());
        for (OptionTradeEntity t : page) {
            CashLedgerEntryEntity e = entry(t, entries);
            Optional<CloseMatch> match = Optional.empty();
            if (!t.getAction().isOpening()) {
                ContractHistory h = histories.computeIfAbsent(t.contract(),
                        c -> ContractReplay.replay(load(accountId, c).trades()));
                match = h.step(t.getId()).match();
            }
            out.add(new TradeView(t, e, match));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    ContractTrades load(long accountId, ContractKey c) {
        List<OptionTradeEntity> entities = trades
                .findByAccountIdAndSymbolAndExpirationDateAndStrikeAndOptionTypeOrderByTradedAtAscIdAsc(
                        accountId, c.symbol(), c.expirationDate(), c.strike(), c.optionType());
        Map<Long, CashLedgerEntryEntity> entries = ledger.findBySources(accountId, TRADE_SOURCE_TYPES,
                entities.stream().map(OptionTradeEntity::getId).toList());
        Map<Long, CashLedgerEntryEntity> byTrade = new LinkedHashMap<>();
        List<LotTrade> lotTrades = new ArrayList<>(entities.size());
        for (OptionTradeEntity t : entities) {
            CashLedgerEntryEntity e = entry(t, entries);
            if (e != null) {
                byTrade.put(t.getId(), e);
            }
            lotTrades.add(toLotTrade(t, e));
        }
        return new ContractTrades(lotTrades, byTrade);
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
     * without entry ({@code e} null) has no cash effect.
     */
    static LotTrade toLotTrade(OptionTradeEntity t, CashLedgerEntryEntity e) {
        BigDecimal cash = e == null ? Money.ZERO : e.getAmount();
        return new LotTrade(t.getId(), t.getTradedAt(), t.getAction(), t.getContracts(), t.getMultiplier(),
                cash, t.getAction().isOpening() ? e.getFrozenChange() : Money.ZERO, t.getNote());
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
