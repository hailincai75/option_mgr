package com.optionmgr.trade.domain;

import com.optionmgr.stock.domain.StockTradeFilter;
import com.optionmgr.stock.domain.StockTradeType;
import java.util.Objects;
import java.util.Set;

/**
 * FEAT-040 closed-records filter: the FEAT-006 option filter plus the record type. Criteria are combined with AND.
 * The contract and action criteria (expiration date, strike, call/put, action) only match option records (Q90): when
 * any of them is set, no stock sale matches. Pure Java.
 *
 * @param options option criteria (symbol and date range also apply to stock sales); empty actions = BTC, STC, EXP
 * @param type    OPTION or STOCK; null = both
 */
public record ClosedRecordFilter(TradeFilter options, ClosedRecordType type) {

    public static final ClosedRecordFilter NONE = new ClosedRecordFilter(TradeFilter.NONE, null);

    public ClosedRecordFilter {
        Objects.requireNonNull(options, "options");
    }

    public boolean includesOptions() {
        return type != ClosedRecordType.STOCK;
    }

    public boolean includesStocks() {
        return type != ClosedRecordType.OPTION && !hasOptionOnlyCriteria();
    }

    /** Q90: any of expiration date, strike, call/put, action is set. */
    public boolean hasOptionOnlyCriteria() {
        return options.expirationDate() != null || options.strike() != null || options.optionType() != null
                || !options.actions().isEmpty();
    }

    /** The stock sales matching the symbol and the (New York sale date) range. */
    public StockTradeFilter stockSales() {
        return new StockTradeFilter(options.symbol(), Set.of(StockTradeType.SELL, StockTradeType.ASSIGN_SELL),
                options.startDate(),
                options.endDate());
    }
}
