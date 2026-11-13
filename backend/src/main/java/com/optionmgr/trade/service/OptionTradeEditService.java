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
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.ContractHistory;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ContractReplay;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.domain.ReplayedTrade;
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

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;
    private final CashLedgerService ledger;
    private final AuditService audit;
    private final Clock clock;

    public OptionTradeEditService(OptionTradeRepository trades, OptionTradeService tradeService,
                                  CashLedgerService ledger, AuditService audit, Clock clock) {
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
        rejectFirstViolation(contract, history, "修改");

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
        List<SideEffect> effects = sideEffects(existing, corrections);
        trade.update(cmd.contracts(), effectiveMultiplier, net, tradedAt, note);
        trade.markAudited(); // only the edited trade; records corrected as side effects keep their flag
        ledger.updateEntry(accountId, entry.getId(), tradedAt, entry.getSourceType(), cashChange, frozenChange,
                OpenTradeTerms.describe(trade.getAction(), cmd.contracts(), effectiveMultiplier, contract), note);
        ledger.correctFrozenChanges(accountId, corrections);
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
        CashLedgerEntryEntity entry = existing.ledgerByTrade().get(id);

        ContractHistory history = ContractReplay.replay(
                existing.trades().stream().filter(t -> t.tradeId() != id).toList());
        Optional<ReplayedTrade> violation = history.firstViolationFrom(firstTradeId(history), k -> true);
        if (violation.isPresent()) {
            ReplayedTrade v = violation.get();
            ViolationKind kind = ContractHistory.primaryViolation(v, k -> true);
            String message = coveredBy(existing, id).filter(closeId -> closeId == v.trade().tradeId()).isPresent()
                    ? TradeRejectionMessages.deletedOpenStillClosed(v, kind)
                    : TradeRejectionMessages.of(contract, v, kind, false, "删除");
            throw new ApiException(OptionTradeService.errorCode(kind), message);
        }

        Map<Long, BigDecimal> corrections = OptionTradeService.frozenCorrections(existing, history, id);
        rejectNegativeCash(ledger.replayWithChanges(accountId, movements(existing, corrections), Set.of(entry.getId())), "删除");

        Map<String, Object> before = snapshot(trade, entry.getAmount(), entry.getFrozenChange());
        List<SideEffect> effects = sideEffects(existing, corrections);
        String summary = summary(trade);
        ledger.deleteEntry(accountId, entry.getId());
        trades.delete(trade);
        ledger.correctFrozenChanges(accountId, corrections);
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

    /** Any trade of the contract invalid after the change (the history before the change was valid). */
    private static void rejectFirstViolation(ContractKey contract, ContractHistory history, String verb) {
        for (ReplayedTrade s : history.steps()) {
            if (!s.isValid()) {
                ViolationKind kind = ContractHistory.primaryViolation(s, k -> true);
                throw new ApiException(OptionTradeService.errorCode(kind),
                        TradeRejectionMessages.of(contract, s, kind, false, verb));
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

    /** Q42: corrected frozen changes of other trades, for the audit record. */
    private List<SideEffect> sideEffects(OptionTradeService.ContractTrades existing, Map<Long, BigDecimal> corrections) {
        List<SideEffect> effects = new ArrayList<>();
        existing.ledgerByTrade().forEach((tradeId, e) -> {
            if (corrections.containsKey(e.getId())) {
                OptionTradeEntity t = trades.findById(tradeId).orElseThrow();
                effects.add(new SideEffect(tradeId, t.getAction().name(), t.contract().label(), "frozenChange",
                        signed(e.getFrozenChange()), signed(corrections.get(e.getId()))));
            }
        });
        return effects;
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

    private static String summary(OptionTradeEntity t) {
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
