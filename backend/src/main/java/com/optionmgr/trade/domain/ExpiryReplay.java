package com.optionmgr.trade.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * FEAT-012: what an expiry of one contract would cover. An expiry is recorded at the expiration cutoff (16:00 New
 * York) and entered last, so it sorts after every stored record at or before the cutoff (Q19 entry order). Pure Java.
 */
public final class ExpiryReplay {

    private ExpiryReplay() {
    }

    /** The lots open right after every stored record not later than the cutoff (FIFO order). */
    public static List<PositionLot> lotsAtCutoff(ContractKey contract, Collection<LotTrade> trades) {
        Instant cutoff = ExpirationTimes.tradingCutoff(contract.expirationDate());
        return ContractReplay.replay(trades.stream().filter(t -> !t.tradedAt().isAfter(cutoff)).toList()).openLots();
    }

    /** Net contracts (absolute) at the cutoff: the expiry size (Q65). */
    public static int contractsAtCutoff(ContractKey contract, Collection<LotTrade> trades) {
        return lotsAtCutoff(contract, trades).stream().mapToInt(PositionLot::remainingContracts).sum();
    }

    /**
     * The earliest stored record later than the cutoff - only possible for trades entered before FEAT-012 (Q83).
     * Any such record conflicts with an expiry.
     */
    public static Optional<LotTrade> firstLateTrade(ContractKey contract, Collection<LotTrade> trades) {
        Instant cutoff = ExpirationTimes.tradingCutoff(contract.expirationDate());
        return trades.stream().filter(t -> t.tradedAt().isAfter(cutoff)).min(LotTrade.FIFO_ORDER);
    }
}
