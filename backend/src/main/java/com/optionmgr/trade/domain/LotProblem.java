package com.optionmgr.trade.domain;

/**
 * FEAT-051: why a lot selected by a close (BTC / STC with {@link LotTrade#lotTradeIds()}) cannot be closed by it at
 * its point in the contract replay.
 *
 * @param lotTradeId  the selected opening trade id
 * @param lot         the opening trade as replay input (current time / contracts); null when it is not an opening
 *                    trade of the replayed contract
 * @param reason      the problem
 * @param closedBy    {@link Reason#ALREADY_CLOSED}: the earlier record that closed the lot; null otherwise
 * @param selectedContracts {@link Reason#CONTRACTS_MISMATCH}: contracts of all selected lots; 0 otherwise
 */
public record LotProblem(long lotTradeId, LotTrade lot, Reason reason, LotTrade closedBy, int selectedContracts) {

    public enum Reason {
        /** Not opened (yet) at the close's point: opened later, or not an open of this contract. */
        NOT_YET_OPEN,
        /** Already closed by an earlier close / expiry / assignment / exercise. */
        ALREADY_CLOSED,
        /** The selected lots' contracts no longer add up to the close's contracts (a lot's size was changed). */
        CONTRACTS_MISMATCH
    }
}
