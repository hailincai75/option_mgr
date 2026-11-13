package com.optionmgr.cash.service;

import com.optionmgr.cash.domain.CashSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A cash ledger entry to append. Used by deposits/withdrawals and trades.
 *
 * @param accountId    account the entry belongs to (FEAT-033)
 * @param occurredAt   when the cash moved (UTC)
 * @param sourceType   kind of originating record
 * @param sourceId     id of the originating record (save it first, in the same transaction)
 * @param amount       signed cash change, scale 2 (inflow positive)
 * @param frozenChange signed frozen-cash change, scale 2 (0 for deposits/withdrawals)
 * @param description  system-generated text, may be null
 * @param remark       user remark, may be null
 */
public record NewLedgerEntry(long accountId, Instant occurredAt, CashSourceType sourceType, long sourceId,
                             BigDecimal amount, BigDecimal frozenChange, String description, String remark) {

    public NewLedgerEntry {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(frozenChange, "frozenChange");
    }
}
