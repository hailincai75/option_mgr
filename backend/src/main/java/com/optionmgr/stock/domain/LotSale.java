package com.optionmgr.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The part of one lot taken by a sale.
 *
 * @param sourceId   lot's source record id (BUY / TRANSFER_IN)
 * @param sourceType BUY or TRANSFER_IN
 * @param tradedAt   lot time
 * @param shares     shares taken from the lot
 * @param cost       cost of those shares, scale 2 (whole remainder, or pro rata HALF_UP, Q69/Q87)
 */
public record LotSale(long sourceId, StockTradeType sourceType, Instant tradedAt, int shares, BigDecimal cost) {
}
