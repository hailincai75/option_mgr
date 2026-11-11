package com.optionmgr.trade.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * The whole lots a close trade covers (FIFO, Q21) and its effect.
 *
 * @param coveredLots    the earliest lots, entirely closed
 * @param releasedFrozen sum of the covered lots' frozen cash (released by a BTC of a short put), scale 2
 * @param realizedPnl    Q21: covered lots' opening net (cash sign) + this close's cash change; scale 2, no rounding
 */
public record CloseMatch(List<PositionLot> coveredLots, BigDecimal releasedFrozen, BigDecimal realizedPnl) {

    public CloseMatch {
        coveredLots = List.copyOf(coveredLots);
    }
}
