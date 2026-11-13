package com.optionmgr.cash.service;

import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.InsertionCheck;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-007: add a deposit or withdrawal. */
@Service
public class CashTransferService {

    /** Validation-error message for a future time (field {@code occurredAtLocal}). */
    public static final String FUTURE_TIME_MESSAGE = "时间不能晚于当前时间";

    private final CashTransferRepository transfers;
    private final CashLedgerService ledger;
    private final Clock clock;

    public CashTransferService(CashTransferRepository transfers, CashLedgerService ledger, Clock clock) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param type            deposit or withdrawal
     * @param amount          positive, at most 2 decimals (already validated by the web layer)
     * @param occurredAtLocal New York wall-clock time
     * @param remark          optional; blank is stored as null
     */
    public record Command(TransferType type, BigDecimal amount, LocalDateTime occurredAtLocal, String remark) {
        public Command {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(occurredAtLocal, "occurredAtLocal");
        }
    }

    public record Result(CashTransferEntity transfer, CashLedgerEntryEntity ledgerEntry) {
    }

    @Transactional
    public Result create(long accountId, Command cmd) {
        Instant occurredAt = MarketTime.toInstant(cmd.occurredAtLocal());
        if (occurredAt.isAfter(clock.instant())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                    List.of(new ErrorResponse.FieldError("occurredAtLocal", FUTURE_TIME_MESSAGE)));
        }
        BigDecimal amount = Money.of(cmd.amount());
        BigDecimal signed = cmd.type().signedAmount(amount);
        String remark = cmd.remark() == null || cmd.remark().isBlank() ? null : cmd.remark();

        ledger.lockAccount(accountId);
        // Deposits only add cash, so they can never make any point negative (spec: always allowed).
        if (cmd.type() == TransferType.WITHDRAWAL) {
            InsertionCheck check = ledger.checkInsertion(accountId, occurredAt, signed, Money.ZERO);
            if (!check.allowed()) {
                throw new ApiException(ErrorCode.CASH_INSUFFICIENT,
                        CashShortfallMessages.insufficient(amount, check));
            }
        }

        CashTransferEntity transfer = transfers.save(
                new CashTransferEntity(accountId, cmd.type(), amount, occurredAt, remark, clock.instant()));
        CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(accountId, occurredAt, cmd.type().sourceType(),
                transfer.getId(), signed, Money.ZERO, null, remark));
        return new Result(transfer, entry);
    }
}
