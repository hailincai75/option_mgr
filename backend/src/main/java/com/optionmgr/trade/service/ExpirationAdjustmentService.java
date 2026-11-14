package com.optionmgr.trade.service;

import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import com.optionmgr.audit.domain.FieldChange;
import com.optionmgr.audit.service.AuditService;
import com.optionmgr.calendar.domain.AdhocClosure;
import com.optionmgr.calendar.domain.TradingCalendar;
import com.optionmgr.calendar.service.AdhocClosureService;
import com.optionmgr.calendar.service.TradingCalendarService;
import com.optionmgr.cash.domain.CashReplay;
import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.trade.domain.ContractHistory;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ContractReplay;
import com.optionmgr.trade.domain.ExpirationAdjustmentRules;
import com.optionmgr.trade.domain.OptionPosition;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.ReplayedTrade;
import com.optionmgr.trade.domain.TradeRejectionMessages;
import com.optionmgr.trade.domain.ViolationKind;
import com.optionmgr.trade.repository.ExpirationAdjustmentEntity;
import com.optionmgr.trade.repository.ExpirationAdjustmentRepository;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-043 (exception to Q43): moves every record of a contract in one account from an ACTIVE ad-hoc closure to an
 * earlier trading day. Amounts, times, lots and ledger amounts stay (Q130); the whole history is still replayed and
 * one ADJUST_EXPIRATION audit record is written.
 */
@Service
public class ExpirationAdjustmentService {

    private static final String VERB = "调整";

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;
    private final ExpirationAdjustmentRepository adjustments;
    private final AdhocClosureService adhocClosures;
    private final TradingCalendarService tradingCalendar;
    private final CashLedgerService ledger;
    private final CoverageService coverage;
    private final AuditService audit;
    private final OptionPositionService positions;
    private final Clock clock;

    public ExpirationAdjustmentService(OptionTradeRepository trades, OptionTradeService tradeService,
                                       ExpirationAdjustmentRepository adjustments, AdhocClosureService adhocClosures,
                                       TradingCalendarService tradingCalendar, CashLedgerService ledger,
                                       CoverageService coverage, AuditService audit, OptionPositionService positions,
                                       Clock clock) {
        this.trades = trades;
        this.tradeService = tradeService;
        this.adjustments = adjustments;
        this.adhocClosures = adhocClosures;
        this.tradingCalendar = tradingCalendar;
        this.ledger = ledger;
        this.coverage = coverage;
        this.audit = audit;
        this.positions = positions;
        this.clock = clock;
    }

    /** @param note null / blank = none */
    public record Command(ContractKey contract, LocalDate newExpirationDate, String note) {
        public Command {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(newExpirationDate, "newExpirationDate");
        }
    }

    /**
     * @param position the new contract's position with the summary it was built from; empty when the contract is
     *                 flat
     */
    public record Result(long auditId, ContractKey oldContract, ContractKey newContract, List<Long> tradeIds,
                         Optional<OptionPosition> position, OptionPositionService.PositionSummary summary) {
    }

    @Transactional
    public Result adjust(long accountId, Command cmd) {
        ledger.lockAccount(accountId);
        ContractKey contract = cmd.contract();
        LocalDate newDate = cmd.newExpirationDate();
        ContractKey target = ExpirationAdjustmentRules.moved(contract, newDate);

        // 2. the contract has records in this account
        List<OptionTradeEntity> records = find(accountId, contract);
        if (records.isEmpty()) {
            throw ApiException.notFound("该账户没有合约 " + contract.label() + " 的记录");
        }

        // 3.-9. the rules of the requirement, in contract order
        Optional<AdhocClosure> closure = adhocClosures.findActive(contract.expirationDate());
        TradingCalendar calendar = tradingCalendar.calendar();
        boolean alreadyAdjusted = adjustments.existsByAccountIdAndSymbolAndNewExpirationDateAndStrikeAndOptionType(
                accountId, contract.symbol(), contract.expirationDate(), contract.strike(), contract.optionType());
        ExpirationAdjustmentRules.Request request = new ExpirationAdjustmentRules.Request(contract,
                records.stream().map(t -> new ExpirationAdjustmentRules.Record(t.getId(), t.getAction(),
                        t.getTradedAt())).toList(),
                newDate, closure.isPresent(), alreadyAdjusted, calendar::isTradingDay,
                !newDate.equals(contract.expirationDate()) && !find(accountId, target).isEmpty());
        ExpirationAdjustmentRules.check(request).ifPresent(v -> {
            throw new ApiException(errorCode(v.rejection()), v.message());
        });

        // 10. Q130: the full replay still holds (contract, coverage, cash)
        OptionTradeService.ContractTrades existing = tradeService.load(accountId, contract);
        ContractHistory history = ContractReplay.replay(existing.trades());
        for (ReplayedTrade s : history.steps()) {
            if (!s.isValid()) {
                ViolationKind kind = ContractHistory.primaryViolation(s, k -> true);
                throw new ApiException(OptionTradeService.errorCode(kind),
                        TradeRejectionMessages.of(target, s, kind, false, VERB));
            }
        }
        if (contract.optionType() == OptionType.CALL) {
            Instant first = records.stream().map(OptionTradeEntity::getTradedAt).min(Comparator.naturalOrder())
                    .orElseThrow();
            coverage.checkInvariant(coverage.snapshot(accountId, contract.symbol()), first, VERB);
        }
        CashReplay.firstViolation(ledger.replayWithChanges(accountId, Map.of(), Set.of())).ifPresent(v -> {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.afterChange(VERB, v));
        });

        // apply: expiration date of every record, ledger descriptions naming the contract, audit, "only once"
        List<Long> tradeIds = records.stream().map(OptionTradeEntity::getId).sorted().toList();
        String oldLabel = contract.label();
        String newLabel = target.label();
        for (OptionTradeEntity t : records) {
            t.changeExpiration(newDate);
            t.markAudited();
            CashLedgerEntryEntity e = existing.ledgerByTrade().get(t.getId());
            if (e != null && e.getDescription() != null && e.getDescription().contains(oldLabel)) {
                ledger.updateEntry(accountId, e.getId(), e.getOccurredAt(), e.getSourceType(), e.getAmount(),
                        e.getFrozenChange(), e.getDescription().replace(oldLabel, newLabel), e.getRemark());
            }
        }
        trades.flush();

        String note = cmd.note() == null || cmd.note().isBlank() ? null : cmd.note().strip();
        Map<String, Object> before = contractSnapshot(contract, tradeIds);
        Map<String, Object> after = contractSnapshot(target, tradeIds);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("symbol", contract.symbol());
        detail.put("strike", ContractKey.strikeText(contract.strike()));
        detail.put("optionType", contract.optionType().name());
        detail.put("oldExpirationDate", contract.expirationDate().toString());
        detail.put("newExpirationDate", newDate.toString());
        detail.put("adhocClosureEventName", closure.orElseThrow().eventName());
        detail.put("tradeIds", tradeIds);
        detail.put("note", note);
        long auditId = audit.record(new AuditService.NewAuditRecord(accountId, AuditOperation.ADJUST_EXPIRATION,
                AuditObjectType.OPTION_TRADE, tradeIds.get(0),
                ExpirationAdjustmentRules.summary(contract, newDate, tradeIds.size()), before, after,
                List.of(new FieldChange("expirationDate", "到期日", contract.expirationDate().toString(),
                        newDate.toString())),
                List.of()), detail, tradeIds);
        adjustments.save(new ExpirationAdjustmentEntity(accountId, contract, newDate, clock.instant(), auditId));

        OptionPositionService.PositionSummary summary = positions.currentPositions(accountId);
        Optional<OptionPosition> position = summary.positions().stream()
                .filter(p -> p.contract().equals(target)).findFirst();
        return new Result(auditId, contract, target, tradeIds, position, summary);
    }

    private List<OptionTradeEntity> find(long accountId, ContractKey c) {
        return trades.findByAccountIdAndSymbolAndExpirationDateAndStrikeAndOptionTypeOrderByTradedAtAscIdAsc(
                accountId, c.symbol(), c.expirationDate(), c.strike(), c.optionType());
    }

    private static Map<String, Object> contractSnapshot(ContractKey c, List<Long> tradeIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", c.symbol());
        m.put("expirationDate", c.expirationDate().toString());
        m.put("strike", ContractKey.strikeText(c.strike()));
        m.put("optionType", c.optionType().name());
        m.put("contractLabel", c.label());
        m.put("tradeIds", tradeIds);
        return m;
    }

    private static ErrorCode errorCode(ExpirationAdjustmentRules.Rejection r) {
        return switch (r) {
            case NOT_ADHOC_CLOSURE -> ErrorCode.EXPIRATION_NOT_ADHOC_CLOSURE;
            case ALREADY_ADJUSTED -> ErrorCode.EXPIRATION_ALREADY_ADJUSTED;
            case ALREADY_SETTLED -> ErrorCode.CONTRACT_ALREADY_SETTLED;
            case NEW_DATE_NOT_TRADING_DAY -> ErrorCode.NEW_EXPIRATION_NOT_TRADING_DAY;
            case NEW_DATE_OUT_OF_RANGE -> ErrorCode.NEW_EXPIRATION_OUT_OF_RANGE;
            case TRADE_AFTER_NEW_CUTOFF -> ErrorCode.TRADE_AFTER_NEW_EXPIRATION;
            case TARGET_CONTRACT_EXISTS -> ErrorCode.TARGET_CONTRACT_EXISTS;
        };
    }
}
