package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * FEAT-006: the figures of one close trade for the closed-trades list. All amounts carry the cash sign
 * (short opening / STC +, long opening / BTC -), scale 2. Pure Java.
 *
 * @param action         BTC or STC
 * @param contracts      closed contracts
 * @param closeNetAmount the close's cash change
 * @param match          the lots it covers (FIFO) and its realized P&amp;L (Q21)
 */
public record ClosedTrade(TradeAction action, int contracts, BigDecimal closeNetAmount, CloseMatch match) {

    public ClosedTrade {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(match, "match");
        if (action.isOpening()) {
            throw new IllegalArgumentException("not a close: " + action);
        }
        closeNetAmount = Money.of(closeNetAmount);
    }

    /** BTC closes a short position, STC a long one. */
    public PositionDirection closedDirection() {
        return action == TradeAction.BTC ? PositionDirection.SHORT : PositionDirection.LONG;
    }

    public List<PositionLot> coveredLots() {
        return match.coveredLots();
    }

    /** Covered lots' opening net (cash sign). */
    public BigDecimal openingNetTotal() {
        return match.coveredLots().stream().map(PositionLot::originalNetAmount).reduce(Money.ZERO, BigDecimal::add);
    }

    /** Q22: display-only, half-up to the cent. */
    public BigDecimal openingNetPerContract() {
        return perContract(openingNetTotal());
    }

    public BigDecimal closeNetPerContract() {
        return perContract(closeNetAmount);
    }

    /** Q21: opening net total + close net, no allocation, no rounding. */
    public BigDecimal realizedPnl() {
        return match.realizedPnl();
    }

    private BigDecimal perContract(BigDecimal amount) {
        return amount.divide(BigDecimal.valueOf(contracts), Money.SCALE, OptionPosition.AVERAGE_ROUNDING);
    }
}
