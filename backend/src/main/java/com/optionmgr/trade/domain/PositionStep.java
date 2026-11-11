package com.optionmgr.trade.domain;

/**
 * One trade in the position replay of a contract, with the position just before it.
 *
 * @param event              the trade
 * @param positionBefore     signed open contracts just before it (+ long, - short, 0 flat)
 * @param positionMultiplier multiplier of the open position just before it; 0 when flat
 */
public record PositionStep(PositionEvent event, int positionBefore, int positionMultiplier) {

    /** Q7: an opening trade must not go against an existing position. */
    public boolean opensAgainstPosition() {
        return event.action().isOpening()
                && positionBefore != 0
                && Integer.signum(positionBefore) != event.action().sign();
    }

    /** Q23: while a position is open, every trade of the contract must use the position's multiplier. */
    public boolean multiplierDiffers() {
        return positionBefore != 0 && event.multiplier() != positionMultiplier;
    }
}
