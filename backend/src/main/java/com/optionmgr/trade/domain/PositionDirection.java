package com.optionmgr.trade.domain;

/** Side of an open position: LONG (built by BTO) or SHORT (built by STO). */
public enum PositionDirection {
    LONG,
    SHORT;

    public static PositionDirection openedBy(TradeAction action) {
        return action.sign() > 0 ? LONG : SHORT;
    }
}
