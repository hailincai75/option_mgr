package com.optionmgr.trade.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Builds the current position of one contract by replaying its trades in FIFO order (time, entry order).
 * Each opening trade becomes a lot. Pure Java, no Spring.
 *
 * <p>FEAT-004: a closing trade will reduce the remaining values of the earliest lots (FIFO) and drop lots whose
 * remaining contracts reach 0; the allocation of net amount / frozen cash is defined there (Q21). Until then no
 * closing trade can be stored, so one is rejected here instead of being silently mis-handled.
 */
public final class PositionBuilder {

    private PositionBuilder() {
    }

    /** @return empty when the contract has no open contracts */
    public static Optional<OptionPosition> build(ContractKey contract, Collection<LotTrade> trades) {
        List<LotTrade> ordered = new ArrayList<>(trades);
        ordered.sort(LotTrade.FIFO_ORDER);
        List<PositionLot> lots = new ArrayList<>();
        for (LotTrade t : ordered) {
            if (!t.action().isOpening()) {
                throw new UnsupportedOperationException("closing trades are handled from FEAT-004 on");
            }
            lots.add(PositionLot.opened(t));
        }
        lots.removeIf(l -> l.remainingContracts() == 0);
        if (lots.isEmpty()) {
            return Optional.empty();
        }
        // Q7 guarantees one direction and Q23 one multiplier per open position.
        PositionLot first = lots.get(0);
        return Optional.of(new OptionPosition(contract, PositionDirection.openedBy(first.action()),
                first.multiplier(), lots));
    }
}
