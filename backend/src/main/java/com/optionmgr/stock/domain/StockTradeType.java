package com.optionmgr.stock.domain;

import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * FEAT-011 kind of stock record. The user always enters a positive amount (Q67): BUY = paid incl. fees, SELL =
 * received net of fees, TRANSFER_IN = total cost of an opening holding (Q71, no cash effect). Persisted by name.
 */
public enum StockTradeType {
    BUY("买入"),
    SELL("卖出"),
    TRANSFER_IN("转入");

    private final String label;

    StockTradeType(String label) {
        this.label = label;
    }

    /** Chinese label: 买入 / 卖出 / 转入. */
    public String label() {
        return label;
    }

    /** BUY and TRANSFER_IN create a FIFO lot; SELL consumes lots. */
    public boolean createsLot() {
        return this != SELL;
    }

    /** Signed change of total cash, scale 2: BUY -amount, SELL +amount, TRANSFER_IN 0. */
    public BigDecimal cashChange(BigDecimal amount) {
        return switch (this) {
            case BUY -> Money.of(amount).negate();
            case SELL -> Money.of(amount);
            case TRANSFER_IN -> Money.ZERO;
        };
    }

    /** Ledger source type of the record's cash entry; TRANSFER_IN has no entry. */
    public Optional<CashSourceType> ledgerSource() {
        return switch (this) {
            case BUY -> Optional.of(CashSourceType.STOCK_BUY);
            case SELL -> Optional.of(CashSourceType.STOCK_SELL);
            case TRANSFER_IN -> Optional.empty();
        };
    }

    /** Ledger description, e.g. "买入 200 股 IXQZ". */
    public String describe(int shares, String symbol) {
        return label + " " + shares + " 股 " + symbol;
    }
}
