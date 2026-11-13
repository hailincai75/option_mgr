package com.optionmgr.cash.service;

import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import com.optionmgr.audit.domain.FieldChange;
import com.optionmgr.audit.domain.FieldChanges;
import com.optionmgr.audit.service.AuditService;
import com.optionmgr.cash.domain.CashMovement;
import com.optionmgr.cash.domain.CashReplay;
import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.ReplayStep;
import com.optionmgr.cash.domain.TransferType;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.repository.CashTransferEntity;
import com.optionmgr.cash.repository.CashTransferRepository;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-005: edit or delete a deposit / withdrawal (Q24, Q40), replayed under Q19 and audited (Q12). */
@Service
public class CashTransferEditService {

    public static final String NO_CHANGES_MESSAGE = "没有修改";

    private static final Set<CashSourceType> TRANSFER_SOURCES = Set.of(CashSourceType.DEPOSIT, CashSourceType.WITHDRAWAL);
    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final CashTransferRepository transfers;
    private final CashLedgerService ledger;
    private final AuditService audit;
    private final Clock clock;

    public CashTransferEditService(CashTransferRepository transfers, CashLedgerService ledger, AuditService audit,
                                   Clock clock) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public CashTransferService.Result update(long accountId, long id, CashTransferService.Command cmd) {
        ledger.lockAccount(accountId);
        CashTransferEntity transfer = find(accountId, id);
        CashLedgerEntryEntity entry = entryOf(transfer);

        Instant occurredAt = MarketTime.toInstant(cmd.occurredAtLocal());
        if (occurredAt.isAfter(clock.instant())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                    List.of(new ErrorResponse.FieldError("occurredAtLocal", CashTransferService.FUTURE_TIME_MESSAGE)));
        }
        BigDecimal amount = Money.of(cmd.amount());
        String remark = cmd.remark() == null || cmd.remark().isBlank() ? null : cmd.remark();
        if (cmd.type() == transfer.getType() && amount.compareTo(transfer.getAmount()) == 0
                && occurredAt.equals(transfer.getOccurredAt()) && Objects.equals(remark, transfer.getRemark())) {
            throw new ApiException(ErrorCode.NO_CHANGES, NO_CHANGES_MESSAGE);
        }

        BigDecimal signed = cmd.type().signedAmount(amount);
        List<ReplayStep> steps = ledger.replayWithChanges(accountId,
                Map.of(entry.getId(), new CashMovement(occurredAt, entry.getId(), signed, Money.ZERO)), Set.of());
        rejectNegative(steps, "修改");

        Map<String, Object> before = snapshot(transfer);
        transfer.update(cmd.type(), amount, occurredAt, remark);
        transfer.markAudited();
        ledger.updateEntry(accountId, entry.getId(), occurredAt, cmd.type().sourceType(), signed, Money.ZERO, null, remark);
        Map<String, Object> after = snapshot(transfer);
        audit.record(new AuditService.NewAuditRecord(accountId, AuditOperation.UPDATE, AuditObjectType.CASH_TRANSFER, id,
                summary(before), before, after, changes(before, after), List.of()));
        return new CashTransferService.Result(transfer, entry);
    }

    @Transactional
    public void delete(long accountId, long id) {
        ledger.lockAccount(accountId);
        CashTransferEntity transfer = find(accountId, id);
        CashLedgerEntryEntity entry = entryOf(transfer);
        rejectNegative(ledger.replayWithChanges(accountId, Map.of(), Set.of(entry.getId())), "删除");

        Map<String, Object> before = snapshot(transfer);
        ledger.deleteEntry(accountId, entry.getId());
        transfers.delete(transfer);
        audit.record(new AuditService.NewAuditRecord(accountId, AuditOperation.DELETE, AuditObjectType.CASH_TRANSFER, id,
                summary(before), before, null, List.of(), List.of()));
    }

    private CashTransferEntity find(long accountId, long id) {
        return transfers.findByIdAndAccountId(id, accountId).orElseThrow(() -> ApiException.notFound("Cash transfer " + id + " not found"));
    }

    private CashLedgerEntryEntity entryOf(CashTransferEntity t) {
        return Optional.ofNullable(ledger.findBySources(t.getAccountId(), TRANSFER_SOURCES, List.of(t.getId())).get(t.getId()))
                .orElseThrow(() -> new IllegalStateException("ledger entry missing for transfer " + t.getId()));
    }

    private static void rejectNegative(List<ReplayStep> steps, String verb) {
        CashReplay.firstViolation(steps).ifPresent(v -> {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.afterChange(verb, v));
        });
    }

    private static Map<String, Object> snapshot(CashTransferEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("type", t.getType());
        m.put("amount", t.getAmount());
        m.put("occurredAt", t.getOccurredAt());
        m.put("occurredAtLocal", LOCAL_SECONDS.format(MarketTime.toLocal(t.getOccurredAt())));
        m.put("remark", t.getRemark());
        return m;
    }

    private static String typeLabel(Object type) {
        return type == TransferType.DEPOSIT ? "入金" : "出金";
    }

    private static String summary(Map<String, Object> s) {
        return typeLabel(s.get("type")) + " " + Money.display((BigDecimal) s.get("amount"));
    }

    private static List<FieldChange> changes(Map<String, Object> b, Map<String, Object> a) {
        return new FieldChanges()
                .compare("type", "类型", typeLabel(b.get("type")), typeLabel(a.get("type")))
                .compare("amount", "金额", Money.display((BigDecimal) b.get("amount")),
                        Money.display((BigDecimal) a.get("amount")))
                .compare("occurredAt", "时间", MarketTime.displayMinute((Instant) b.get("occurredAt")),
                        MarketTime.displayMinute((Instant) a.get("occurredAt")))
                .compare("remark", "备注", (String) b.get("remark"), (String) a.get("remark"))
                .list();
    }
}
