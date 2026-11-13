package com.optionmgr.trade.domain;

/**
 * Trade direction. FEAT-001 records only the opening actions (BTO / STO); BTC / STC are defined here so that
 * FEAT-004 can store closes in the same table and the position replay already knows their sign.
 * Persisted by name.
 */
public enum TradeAction {
    /** Buy to Open: long +contracts, cash out. */
    BTO(1, true),
    /** Sell to Open: short -contracts, cash in. */
    STO(-1, true),
    /** Buy to Close (FEAT-004): reduces a short position. */
    BTC(1, false),
    /** Sell to Close (FEAT-004): reduces a long position. */
    STC(-1, false),
    /**
     * FEAT-012 expire worthless: closes the whole position at the expiration cutoff (16:00 New York), whichever side
     * it is (sign 0: never "against" a position). No cash; stored with net amount 0. Cannot be edited, only deleted.
     */
    EXP(0, false),
    /**
     * FEAT-013 a short put is assigned: closes the earliest whole short lots like a BTC (sign +1, so a long position
     * is "the wrong side"), recorded at 16:00 New York of the assignment date, net amount 0, option realized P&amp;L
     * 0 (the premium goes into the cost of the generated stock lot, Q8). Cannot be edited, only deleted.
     */
    ASG(1, false);

    private final int sign;
    private final boolean opening;

    TradeAction(int sign, boolean opening) {
        this.sign = sign;
        this.opening = opening;
    }

    /** +1 if the action adds long contracts (buys), -1 if it adds short contracts (sells). */
    public int sign() {
        return sign;
    }

    public boolean isOpening() {
        return opening;
    }

    /** True for actions that receive cash (sells), false for actions that pay cash (buys). */
    public boolean receivesCash() {
        return sign < 0;
    }

    /** BTC / STC: a closing trade entered by the user (not an expiry). */
    public boolean isUserClose() {
        return this == BTC || this == STC;
    }

    /** User-facing name in texts: the code, except "到期作废" for EXP and "被指派" for ASG. */
    public String displayName() {
        return switch (this) {
            case EXP -> "到期作废";
            case ASG -> "被指派";
            default -> name();
        };
    }

    /** EXP / ASG: closing records shaped by the system; they can only be deleted, never edited. */
    public boolean isDeleteOnly() {
        return this == EXP || this == ASG;
    }

    public int signedContracts(int contracts) {
        return sign * contracts;
    }
}
