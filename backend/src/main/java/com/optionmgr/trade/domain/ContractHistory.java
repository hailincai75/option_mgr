package com.optionmgr.trade.domain;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Result of {@link ContractReplay#replay}: every trade with its state and violations, and the lots still open
 * after the last trade (FIFO order).
 */
public record ContractHistory(List<ReplayedTrade> steps, List<PositionLot> openLots) {

    public ContractHistory {
        steps = List.copyOf(steps);
        openLots = List.copyOf(openLots);
    }

    public ReplayedTrade step(long tradeId) {
        return steps.stream().filter(s -> s.trade().tradeId() == tradeId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("trade " + tradeId + " not in history"));
    }

    /** First step, from the given trade on (inclusive), having a violation that matches {@code kind}. */
    public Optional<ReplayedTrade> firstViolationFrom(long tradeId, Predicate<ViolationKind> kind) {
        boolean reached = false;
        for (ReplayedTrade s : steps) {
            reached = reached || s.trade().tradeId() == tradeId;
            if (reached && s.violations().stream().anyMatch(kind)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Most significant violation of a step, used for messages and error codes. */
    public static ViolationKind primaryViolation(ReplayedTrade step, Predicate<ViolationKind> kind) {
        for (ViolationKind k : ViolationKind.values()) {
            if (step.violations().contains(k) && kind.test(k)) {
                return k;
            }
        }
        throw new IllegalArgumentException("step has no matching violation");
    }
}
