package com.optionmgr.trade.service;

import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import com.optionmgr.audit.domain.FieldChange;
import com.optionmgr.audit.domain.FieldChanges;
import com.optionmgr.audit.domain.SideEffect;
import com.optionmgr.audit.service.AuditService;
import com.optionmgr.cash.domain.CashMovement;
import com.optionmgr.cash.domain.CashReplay;
import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.ReplayStep;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.StockRecord;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.stock.service.StockTradeService;
import com.optionmgr.trade.domain.AssignmentTerms;
import com.optionmgr.trade.domain.CallAssignmentTerms;
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.ContractHistory;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ContractReplay;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.ReplayedTrade;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeRejectionMessages;
import com.optionmgr.trade.domain.ViolationKind;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-005: edit or delete an option trade. The change is applied to the whole history: the contract is replayed
 * ({@link ContractReplay}) and the whole cash ledger is replayed; anything invalid rejects the change. Otherwise the
 * trade and its ledger entry are updated in place (ids = entry order kept) or deleted, later closes' released
 * frozen cash is corrected, and one audit record is written (Q12, Q42).
 */
@Service
public class OptionTradeEditService {

    public static final String NO_CHANGES_MESSAGE = "没有修改";
    public static final String EXPIRY_NOT_EDITABLE_MESSAGE = "到期作废不能修改，记错请删除后重新作废";
    public static final String ASSIGNMENT_NOT_EDITABLE_MESSAGE = "被指派不能修改，记错请删除后重新记录";

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;
    private final CashLedgerService ledger;
    private final AuditService audit;
    private final StockTradeService stocks;
    private final StockTradeRepository stockTrades;
    private final Clock clock;

    public OptionTradeEditService(OptionTradeRepository trades, OptionTradeService tradeService,
                                  CashLedgerService ledger, AuditService audit, StockTradeService stocks,
                                  StockTradeRepository stockTrades, Clock clock) {
        this.stocks = stocks;
        this.stockTrades = stockTrades;
        this.trades = trades;
        this.tradeService = tradeService;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Editable fields (Q43), already format-validated by the web layer.
     *
     * @param multiplier opens only; null = unchanged. Ignored for closes (they take the position's multiplier).
     * @param note       full replacement; null / blank = no note
     */
    public record EditCommand(int contracts, BigDecimal netAmount, LocalDateTime tradedAtLocal, Integer multiplier,
                              String note) {
        public EditCommand {
            Objects.requireNonNull(netAmount, "netAmount");
            Objects.requireNonNull(tradedAtLocal, "tradedAtLocal");
        }
    }

    // ------------------------------------------------------------------ edit

    @Transactional
    public OptionTradeService.TradeView update(long accountId, long id, EditCommand cmd) {
        ledger.lockAccount(accountId);
        OptionTradeEntity trade = find(accountId, id);
        if (trade.getAction() == TradeAction.EXP) {
            throw new ApiException(ErrorCode.EXPIRY_NOT_EDITABLE, EXPIRY_NOT_EDITABLE_MESSAGE);
        }
        if (trade.getAction() == TradeAction.ASG) {
            throw new ApiException(ErrorCode.ASSIGNMENT_NOT_EDITABLE, ASSIGNMENT_NOT_EDITABLE_MESSAGE);
        }
        ContractKey contract = trade.contract();
        boolean opening = trade.getAction().isOpening();

        // 1. field rules of the edited trade
        Instant tradedAt = MarketTime.toInstant(cmd.tradedAtLocal());
        LocalDate tradeDate = MarketTime.toLocal(tradedAt).toLocalDate();
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        if (tradeDate.isAfter(contract.expirationDate())) {
            errors.add(new ErrorResponse.FieldError("tradedAtLocal", opening
                    ? "成交日不能晚于到期日（" + contract.expirationDate() + "）"
                    : "到期日（" + contract.expirationDate() + "）之后不能平仓"));
        } else if (!tradedAt.equals(trade.getTradedAt())) {
            // Q83 / Q85: only a changed trade time is checked against 16:00 (stored late trades are not re-checked)
            OptionTradeService.checkNotAfterCutoff(contract, tradedAt, errors);
        }
        if (tradedAt.isAfter(clock.instant())) {
            errors.add(new ErrorResponse.FieldError("tradedAtLocal", OptionTradeService.FUTURE_TIME_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed", errors);
        }
        BigDecimal net = Money.of(cmd.netAmount());
        int multiplier = opening && cmd.multiplier() != null ? cmd.multiplier() : trade.getMultiplier();
        String note = blankToNull(cmd.note());
        boolean sizeOrTimeChanged = cmd.contracts() != trade.getContracts() || multiplier != trade.getMultiplier()
                || !tradedAt.equals(trade.getTradedAt());
        if (!sizeOrTimeChanged && net.compareTo(trade.getNetAmount()) == 0 && Objects.equals(note, trade.getNote())) {
            throw new ApiException(ErrorCode.NO_CHANGES, NO_CHANGES_MESSAGE);
        }

        // 2.-4. replay the contract with the edited trade (same id = same entry order)
        OptionTradeService.ContractTrades existing = tradeService.load(accountId, contract);
        OpenTradeTerms terms = opening
                ? new OpenTradeTerms(contract, trade.getAction(), cmd.contracts(), multiplier, net) : null;
        BigDecimal cashChange = opening ? terms.cashChange() : (trade.getAction().receivesCash() ? net : net.negate());
        LotTrade edited = new LotTrade(id, tradedAt, trade.getAction(), cmd.contracts(),
                opening ? multiplier : LotTrade.INHERIT_MULTIPLIER, cashChange,
                opening ? terms.frozenChange() : Money.ZERO, note);
        List<LotTrade> all = new ArrayList<>(existing.trades().stream().filter(t -> t.tradeId() != id).toList());
        all.add(edited);
        ContractHistory history = ContractReplay.replay(all);
        ReplayedTrade step = history.step(id);
        if (!step.isValid()) {
            ViolationKind kind = ContractHistory.primaryViolation(step, k -> true);
            throw new ApiException(OptionTradeService.errorCode(kind),
                    TradeRejectionMessages.of(contract, step, kind, true));
        }
        rejectFirstViolation(contract, history, "修改", systemRecordCovering(existing, id));
        List<OptionTradeService.CostCorrection> costs = tradeService.costCorrections(accountId, contract, existing,
                history, LotTrade.NOT_YET_ENTERED);
        OptionTradeService.rejectNonPositiveCost(costs, "修改");

        int effectiveMultiplier = step.effectiveMultiplier();
        BigDecimal frozenChange = opening
                ? terms.frozenChange()
                : step.match().map(m -> m.releasedFrozen().negate()).orElseThrow();
        CashLedgerEntryEntity entry = existing.ledgerByTrade().get(id);
        Map<Long, BigDecimal> corrections = OptionTradeService.frozenCorrections(existing, history, id);

        // 5. cash: Q18 for the edited STO put itself (Q41), Q19 at every point
        Map<Long, CashMovement> replacements = movements(existing, corrections);
        replacements.put(entry.getId(), new CashMovement(tradedAt, entry.getId(), cashChange, frozenChange));
        List<ReplayStep> steps = ledger.replayWithChanges(accountId, replacements, Set.of());
        if (opening && terms.freezesCollateral() && sizeOrTimeChanged) {
            BigDecimal availableBefore = steps.stream().filter(s -> s.movement().sequence() == entry.getId())
                    .findFirst().orElseThrow().before().available();
            if (availableBefore.compareTo(terms.collateral()) < 0) {
                throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.notEnoughNow(
                        CashShortfallMessages.INSUFFICIENT_FOR_PUT_COLLATERAL, terms.collateral(), availableBefore));
            }
        }
        rejectNegativeCash(steps, "修改");

        // save
        Map<String, Object> before = snapshot(trade, entry.getAmount(), entry.getFrozenChange());
        List<SideEffect> effects = sideEffects(existing, corrections, costs);
        effects.addAll(soldCostEffects(accountId, contract.symbol(), costs, null));
        trade.update(cmd.contracts(), effectiveMultiplier, net, tradedAt, note);
        trade.markAudited(); // only the edited trade; records corrected as side effects keep their flag
        ledger.updateEntry(accountId, entry.getId(), tradedAt, entry.getSourceType(), cashChange, frozenChange,
                OpenTradeTerms.describe(trade.getAction(), cmd.contracts(), effectiveMultiplier, contract), note);
        ledger.correctFrozenChanges(accountId, corrections);
        OptionTradeService.applyCostCorrections(costs);
        Map<String, Object> after = snapshot(trade, cashChange, frozenChange);
        audit.record(new AuditService.NewAuditRecord(accountId, AuditOperation.UPDATE, AuditObjectType.OPTION_TRADE, id,
                summary(trade), before, after, changes(before, after), effects));
        return tradeService.get(accountId, id);
    }

    // ------------------------------------------------------------------ delete

    @Transactional
    public void delete(long accountId, long id) {
        ledger.lockAccount(accountId);
        OptionTradeEntity trade = find(accountId, id);
        ContractKey contract = trade.contract();
        OptionTradeService.ContractTrades existing = tradeService.load(accountId, contract);
        // null only for an expiry that released nothing (FEAT-012)
        CashLedgerEntryEntity entry = existing.ledgerByTrade().get(id);

        ContractHistory history = ContractReplay.replay(
                existing.trades().stream().filter(t -> t.tradeId() != id).toList());
        Optional<ReplayedTrade> violation = history.firstViolationFrom(firstTradeId(history), k -> true);
        if (violation.isPresent()) {
            ReplayedTrade v = violation.get();
            ViolationKind kind = ContractHistory.primaryViolation(v, k -> true);
            boolean coveringRecord = coveredBy(existing, id).filter(closeId -> closeId == v.trade().tradeId()).isPresent();
            String message;
            if (coveringRecord && v.trade().action().isDeleteOnly()) {
                message = TradeRejectionMessages.coveredOpenChanged(v, kind, "删除");
            } else if (coveringRecord) {
                message = TradeRejectionMessages.deletedOpenStillClosed(v, kind);
            } else {
                message = TradeRejectionMessages.of(contract, v, kind, false, "删除");
            }
            throw new ApiException(OptionTradeService.errorCode(kind), message);
        }
        List<OptionTradeService.CostCorrection> costs = tradeService.costCorrections(accountId, contract, existing,
                history, id);
        OptionTradeService.rejectNonPositiveCost(costs, "删除");

        // FEAT-013: deleting an assignment removes its generated lot; every later sale must stay covered (Q15)
        boolean assignment = trade.getAction() == TradeAction.ASG;
        StockTradeEntity lot = assignment
                ? tradeService.assignedLots(accountId, List.of(id)).get(id)
                : null;
        if (lot != null) {
            stocks.checkHoldings(accountId, lot.getSymbol(),
                    records -> records.stream().filter(r -> r.id() != lot.getId()).toList(), -1, "删除");
        }

        Map<Long, BigDecimal> corrections = OptionTradeService.frozenCorrections(existing, history, id);
        rejectNegativeCash(ledger.replayWithChanges(accountId, movements(existing, corrections),
                entry == null ? Set.of() : Set.of(entry.getId())), "删除");

        BigDecimal cashChange = entry == null ? Money.ZERO : entry.getAmount();
        BigDecimal frozenChange = entry == null ? Money.ZERO : entry.getFrozenChange();
        Map<String, Object> before = snapshot(trade, cashChange, frozenChange);
        if (assignment) {
            OptionTradeService.TradeView view = tradeService.views(accountId, List.of(trade)).get(0);
            before.put("assignmentDate", MarketTime.toLocal(trade.getTradedAt()).toLocalDate().toString());
            before.put("assignmentFee", Money.of(trade.getAssignmentFee()));
            if (trade.getOptionType() == OptionType.CALL) {
                // FEAT-041: the figures of rule 3 (premium, strike amount, proceeds, delivered cost, stock P&L)
                CallAssignmentTerms terms = view.callAssignment().orElseThrow();
                before.put("shares", terms.shares());
                before.put("premium", terms.premium());
                before.put("saleAmount", terms.saleAmount());
                before.put("proceeds", terms.proceeds());
                before.put("soldCost", view.assignedSale() == null ? null : view.assignedSale().soldCost());
                before.put("stockRealizedPnl", view.assignedSale() == null ? null : view.assignedSale().realizedPnl());
            } else {
                AssignmentTerms terms = view.assignment().orElseThrow();
                before.put("shares", terms.shares());
                before.put("purchaseAmount", terms.purchaseAmount());
                before.put("stockCost", terms.stockCost());
            }
            before.put("stockTradeId", lot == null ? null : lot.getId());
        }
        List<SideEffect> effects = sideEffects(existing, corrections, costs);
        effects.addAll(soldCostEffects(accountId, contract.symbol(), costs, lot == null ? null : lot.getId()));
        String summary = summary(trade);
        if (entry != null) {
            ledger.deleteEntry(accountId, entry.getId());
        }
        if (lot != null) {
            stockTrades.delete(lot);
            stockTrades.flush();
        }
        trades.delete(trade);
        ledger.correctFrozenChanges(accountId, corrections);
        OptionTradeService.applyCostCorrections(costs);
        audit.record(new AuditService.NewAuditRecord(accountId, AuditOperation.DELETE, AuditObjectType.OPTION_TRADE, id,
                summary, before, null, List.of(), effects));
    }

    // ------------------------------------------------------------------ helpers

    private OptionTradeEntity find(long accountId, long id) {
        return trades.findByIdAndAccountId(id, accountId).orElseThrow(() -> ApiException.notFound("Option trade " + id + " not found"));
    }

    private static long firstTradeId(ContractHistory history) {
        return history.steps().isEmpty() ? LotTrade.NOT_YET_ENTERED : history.steps().get(0).trade().tradeId();
    }

    /** The close that covers the open {@code openId} in the current (unchanged) history, if any. */
    private static Optional<Long> coveredBy(OptionTradeService.ContractTrades existing, long openId) {
        for (ReplayedTrade s : ContractReplay.replay(existing.trades()).steps()) {
            Optional<CloseMatch> m = s.match();
            if (m.isPresent() && m.get().coveredLots().stream().anyMatch(l -> l.tradeId() == openId)) {
                return Optional.of(s.trade().tradeId());
            }
        }
        return Optional.empty();
    }

    /** The expiry (EXP) or assignment (ASG) that covers the open {@code openId} in the current history, if any. */
    private static Optional<Long> systemRecordCovering(OptionTradeService.ContractTrades existing, long openId) {
        Optional<Long> record = coveredBy(existing, openId);
        return record.filter(rid -> existing.trades().stream()
                .anyMatch(t -> t.tradeId() == rid && t.action().isDeleteOnly()));
    }

    /**
     * Any trade of the contract invalid after the change (the history before the change was valid). When the
     * invalid record is the expiry that covered the edited open (FEAT-012), the message names that expiry.
     */
    private static void rejectFirstViolation(ContractKey contract, ContractHistory history, String verb,
                                             Optional<Long> coveringExpiry) {
        for (ReplayedTrade s : history.steps()) {
            if (!s.isValid()) {
                ViolationKind kind = ContractHistory.primaryViolation(s, k -> true);
                boolean covering = coveringExpiry.filter(eid -> eid == s.trade().tradeId()).isPresent();
                throw new ApiException(OptionTradeService.errorCode(kind), covering
                        ? TradeRejectionMessages.coveredOpenChanged(s, kind, verb)
                        : TradeRejectionMessages.of(contract, s, kind, false, verb));
            }
        }
    }

    private static void rejectNegativeCash(List<ReplayStep> steps, String verb) {
        CashReplay.firstViolation(steps).ifPresent(v -> {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.afterChange(verb, v));
        });
    }

    /** Corrected frozen changes as ledger movements (same time, amount and entry order). */
    private static Map<Long, CashMovement> movements(OptionTradeService.ContractTrades existing,
                                                    Map<Long, BigDecimal> corrections) {
        Map<Long, CashMovement> out = new HashMap<>();
        for (CashLedgerEntryEntity e : existing.ledgerByTrade().values()) {
            if (corrections.containsKey(e.getId())) {
                out.put(e.getId(), new CashMovement(e.getOccurredAt(), e.getId(), e.getAmount(),
                        corrections.get(e.getId())));
            }
        }
        return out;
    }

    /** Q42: corrected frozen changes of other trades and (FEAT-013) corrected stock costs, for the audit record. */
    private List<SideEffect> sideEffects(OptionTradeService.ContractTrades existing, Map<Long, BigDecimal> corrections,
                                         List<OptionTradeService.CostCorrection> costs) {
        List<SideEffect> effects = new ArrayList<>();
        existing.ledgerByTrade().forEach((tradeId, e) -> {
            if (corrections.containsKey(e.getId())) {
                OptionTradeEntity t = trades.findById(tradeId).orElseThrow();
                effects.add(new SideEffect(tradeId, t.getAction().name(), t.contract().label(), "frozenChange",
                        signed(e.getFrozenChange()), signed(corrections.get(e.getId()))));
            }
        });
        for (OptionTradeService.CostCorrection c : costs) {
            effects.add(new SideEffect(c.assignmentId(), TradeAction.ASG.name(), c.contract().label(),
                    c.call() ? SideEffect.PROCEEDS : SideEffect.STOCK_COST, Money.display(c.before()),
                    Money.display(c.after())));
        }
        return effects;
    }

    /**
     * FEAT-041: call assignments of {@code symbol} whose delivered shares' cost changes because generated records'
     * amounts are corrected ({@code costs}: an ASSIGN_BUY lot's cost) or a generated record is removed.
     */
    private List<SideEffect> soldCostEffects(long accountId, String symbol,
                                             List<OptionTradeService.CostCorrection> costs, Long removedStockId) {
        Map<Long, BigDecimal> amounts = new HashMap<>();
        costs.forEach(c -> amounts.put(c.lot().getId(), c.after()));
        if (amounts.isEmpty() && removedStockId == null) {
            return List.of();
        }
        return stocks.assignedSaleCostEffects(accountId, symbol, records -> records.stream()
                .filter(r -> removedStockId == null || r.id() != removedStockId)
                .map(r -> amounts.containsKey(r.id())
                        ? new StockRecord(r.id(), r.type(), r.tradedAt(), r.shares(), amounts.get(r.id()), r.note())
                        : r)
                .toList());
    }

    private static Map<String, Object> snapshot(OptionTradeEntity t, BigDecimal cashChange, BigDecimal frozenChange) {
        ContractKey c = t.contract();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("symbol", t.getSymbol());
        m.put("expirationDate", t.getExpirationDate());
        m.put("strike", ContractKey.strikeText(t.getStrike()));
        m.put("optionType", t.getOptionType());
        m.put("contractLabel", c.label());
        m.put("action", t.getAction());
        m.put("contracts", t.getContracts());
        m.put("multiplier", t.getMultiplier());
        m.put("netAmount", Money.of(t.getNetAmount()));
        m.put("tradedAt", t.getTradedAt());
        m.put("tradedAtLocal", LOCAL_SECONDS.format(MarketTime.toLocal(t.getTradedAt())));
        m.put("note", t.getNote());
        m.put("cashChange", Money.of(cashChange));
        m.put("frozenChange", Money.of(frozenChange));
        return m;
    }

    /** "STO AAPL 2026-12-18 200 P"; an expiry: "到期作废 XYZ 2026-11-20 45 P 3 张". */
    private static String summary(OptionTradeEntity t) {
        if (t.getAction() == TradeAction.EXP) {
            return t.getAction().displayName() + " " + t.contract().label() + " " + t.getContracts() + " 张";
        }
        if (t.getAction() == TradeAction.ASG) {
            // "被指派 XYZ 2026-11-20 45 P 3 张（买入 300 股 XYZ）"; a call (FEAT-041): "…（卖出 300 股 IXQZ）"
            String verb = t.getOptionType() == OptionType.CALL ? "卖出" : "买入";
            return t.getAction().displayName() + " " + t.contract().label() + " " + t.getContracts() + " 张（" + verb + " "
                    + t.getContracts() * t.getMultiplier() + " 股 " + t.getSymbol() + "）";
        }
        return t.getAction().name() + " " + t.contract().label();
    }

    private static List<FieldChange> changes(Map<String, Object> b, Map<String, Object> a) {
        return new FieldChanges()
                .compare("contracts", "张数", String.valueOf(b.get("contracts")), String.valueOf(a.get("contracts")))
                .compare("netAmount", "净额", Money.display((BigDecimal) b.get("netAmount")),
                        Money.display((BigDecimal) a.get("netAmount")))
                .compare("tradedAt", "成交时间", MarketTime.displayMinute((Instant) b.get("tradedAt")),
                        MarketTime.displayMinute((Instant) a.get("tradedAt")))
                .compare("multiplier", "multiplier", String.valueOf(b.get("multiplier")),
                        String.valueOf(a.get("multiplier")))
                .compare("note", "备注", (String) b.get("note"), (String) a.get("note"))
                .compare("frozenChange", "冻结变动", signed((BigDecimal) b.get("frozenChange")),
                        signed((BigDecimal) a.get("frozenChange")))
                .list();
    }

    /** "+12.35", "−24.70", "0.00". */
    static String signed(BigDecimal amount) {
        return amount.signum() > 0 ? "+" + Money.display(amount) : Money.display(amount);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
