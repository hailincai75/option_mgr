package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import com.optionmgr.stock.domain.LotSale;
import com.optionmgr.stock.domain.SaleMatch;
import com.optionmgr.stock.domain.StockPosition;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * FEAT-040: a stock sale presented as a closed record, with the same cash-sign convention as an option close of a
 * long position: opening net = -sold cost (paid), close net = +amount received. The figures are exactly FEAT-011's
 * FIFO match (Q69, Q87); the per-share values are display-only, HALF_UP to 4 decimals (Q88). Pure Java.
 *
 * @param shares shares sold
 * @param amount amount received (positive, scale 2)
 * @param match  FIFO match of the sale
 */
public record ClosedStockSale(int shares, BigDecimal amount, SaleMatch match) {

    public ClosedStockSale {
        Objects.requireNonNull(match, "match");
        if (shares <= 0) {
            throw new IllegalArgumentException("shares must be positive");
        }
        amount = Money.of(amount);
    }

    /** -sold cost, scale 2. */
    public BigDecimal openingNetTotal() {
        return match.soldCost().negate();
    }

    /** Sold cost / shares, positive, 4 decimals HALF_UP (Q88). */
    public BigDecimal costPerShare() {
        return StockPosition.perShare(match.soldCost(), shares);
    }

    /** +amount received. */
    public BigDecimal closeNetAmount() {
        return amount;
    }

    /** Amount / shares, 4 decimals HALF_UP (Q88) - the stock record's price per share. */
    public BigDecimal pricePerShare() {
        return StockPosition.perShare(amount, shares);
    }

    /** Amount - sold cost (= openingNetTotal + closeNetAmount). */
    public BigDecimal realizedPnl() {
        return match.realizedPnl();
    }

    /** Cash-signed cost of one sold lot part: -cost. */
    public static BigDecimal lotNetAmount(LotSale part) {
        return part.cost().negate();
    }
}
