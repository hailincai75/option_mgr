package com.optionmgr.cash.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.TransferType;
import com.optionmgr.common.time.MarketLocalDateTimeDeserializer;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** Request/response bodies of the FEAT-007 cash API (see docs/api/api-contract.md). */
public final class CashDtos {

    private CashDtos() {
    }

    public record CreateTransferRequest(
            @NotNull(message = "请选择类型")
            TransferType type,

            @NotNull(message = "金额不能为空")
            @Positive(message = "金额必须大于 0")
            @Digits(integer = 100, fraction = 2, message = "金额最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "金额过大")
            BigDecimal amount,

            @NotNull(message = "时间不能为空")
            @JsonDeserialize(using = MarketLocalDateTimeDeserializer.class)
            LocalDateTime occurredAtLocal,

            @Size(max = 500, message = "备注最多 500 个字符")
            String remark) {
    }

    public record TransferResponse(long id, TransferType type, BigDecimal amount, Instant occurredAt, String remark,
                                   long ledgerEntryId, Instant createdAt) {
    }

    /** FEAT-035: {@code accounts} = one row per account of the scope (only the account itself for a single account). */
    public record CashSummaryResponse(BigDecimal totalCash, BigDecimal frozenCash, BigDecimal availableCash,
                                      List<AccountCashResponse> accounts) {
    }

    /** FEAT-035: one account's cash (the same three values as that account's own summary). */
    public record AccountCashResponse(long accountId, String accountName, BigDecimal totalCash, BigDecimal frozenCash,
                                      BigDecimal availableCash) {
    }

    /** FEAT-046: {@code accountId} / {@code accountName} = the row's account (current name), also for one account. */
    public record LedgerEntryResponse(long accountId, String accountName, long id, Instant occurredAt,
                                      CashSourceType sourceType, long sourceId,
                                      BigDecimal amount, BigDecimal balanceAfter, BigDecimal frozenChange,
                                      String description,
                                      String remark,
                                      /* DEPOSIT / WITHDRAWAL: stored flag of the cash transfer (set when it is edited);
                                         other source types: always false */
                                      boolean hasAuditLog) {
    }

    public record LedgerPageResponse(List<LedgerEntryResponse> items, int page, int size, long totalItems,
                                     int totalPages) {
    }
}
