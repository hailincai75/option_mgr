package com.optionmgr.cash.service;

import com.optionmgr.cash.domain.CashSourceType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One ledger entry with the total cash right after it (recomputed by replay on every read). {@code hasAuditLog}:
 * for DEPOSIT / WITHDRAWAL the stored flag of the cash transfer (set when it is edited); false for every other
 * source type.
 */
public record LedgerLine(long id, Instant occurredAt, CashSourceType sourceType, long sourceId,
                         BigDecimal amount, BigDecimal balanceAfter, BigDecimal frozenChange, String description,
                         String remark, boolean hasAuditLog) {
}
