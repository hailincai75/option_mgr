package com.optionmgr.stock.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * FIFO result of one sale.
 *
 * @param lots        lots sold from, FIFO order
 * @param soldCost    sum of the parts' cost, scale 2
 * @param realizedPnl sale amount - soldCost, scale 2
 */
public record SaleMatch(List<LotSale> lots, BigDecimal soldCost, BigDecimal realizedPnl) {

    public SaleMatch {
        lots = List.copyOf(lots);
    }
}
