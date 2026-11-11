package com.optionmgr.trade.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Builds the current position of one contract from all its trades: {@link ContractReplay} in FIFO order, opens
 * add lots, closes remove the earliest whole lots (Q21). Pure Java, no Spring.
 */
public final class PositionBuilder {

    private PositionBuilder() {
    }

    /** @return empty when the contract has no open contracts */
    public static Optional<OptionPosition> build(ContractKey contract, Collection<LotTrade> trades) {
        return fromLots(contract, ContractReplay.replay(trades).openLots());
    }

    public static Optional<OptionPosition> fromLots(ContractKey contract, List<PositionLot> openLots) {
        if (openLots.isEmpty()) {
            return Optional.empty();
        }
        // Q7 guarantees one direction and Q23 one multiplier per open position.
        PositionLot first = openLots.get(0);
        return Optional.of(new OptionPosition(contract, PositionDirection.openedBy(first.action()),
                first.multiplier(), openLots));
    }
}
