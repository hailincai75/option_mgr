package com.optionmgr.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A FIFO lot (one BUY or TRANSFER_IN) with its original and remaining shares / cost (scale 2).
 *
 * @param sourceId the buy / transfer-in record id
 */
public record StockLot(long sourceId, StockTradeType type, Instant tradedAt, int originalShares,
                       BigDecimal originalCost, int remainingShares, BigDecimal remainingCost, String note) {
}
