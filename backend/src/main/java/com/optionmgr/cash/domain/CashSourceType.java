package com.optionmgr.cash.domain;

/**
 * Where a cash ledger entry comes from. Later features add values (e.g. OPTION_CLOSE for FEAT-004);
 * adding a value is backward compatible. Persisted by name (max 40 chars).
 */
public enum CashSourceType {
    DEPOSIT,
    WITHDRAWAL,
    /** FEAT-001 opening option trade (BTO / STO); source id = option_trade.id. */
    OPTION_OPEN,
    /** FEAT-004 closing option trade (BTC / STC); source id = option_trade.id. Its frozen change may be corrected
     *  when an earlier trade is back-filled and the FIFO lot matching changes (Q39). */
    OPTION_CLOSE,
    /** FEAT-012 expire worthless; source id = option_trade.id of the EXP record. Written only when it releases
     *  frozen cash (short put): amount 0, frozen change = -released; the frozen change may be corrected (Q39). */
    OPTION_EXPIRE,
    /** FEAT-013 short put assigned; source id = option_trade.id of the ASG record. amount = -(purchase + fee),
     *  frozen change = -released; the frozen change may be corrected (Q39). */
    OPTION_ASSIGN,
    /** FEAT-011 stock purchase; source id = stock_trade.id; amount = -paid, frozen change 0. */
    STOCK_BUY,
    /** FEAT-011 stock sale; source id = stock_trade.id; amount = +received, frozen change 0. */
    STOCK_SELL
}
