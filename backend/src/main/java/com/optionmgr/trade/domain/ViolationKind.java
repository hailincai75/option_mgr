package com.optionmgr.trade.domain;

/** Why a trade is invalid at its point in the contract replay. */
public enum ViolationKind {
    /** Q7: an open against an existing opposite position. */
    OPEN_AGAINST_POSITION,
    /** Q23: multiplier differs from the open position's. */
    MULTIPLIER_MISMATCH,
    /** FEAT-004: a close without any position. */
    NO_POSITION,
    /** FEAT-004 / Q7: BTC on a long position or STC on a short one. */
    CLOSE_WRONG_DIRECTION,
    /** FEAT-004: close contracts &gt; position. */
    CLOSE_EXCEEDS_POSITION,
    /** Q21: close contracts are not the sum of the earliest whole lots. */
    CLOSE_NOT_WHOLE_LOTS
}
