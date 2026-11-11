package com.optionmgr.cash.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Replays cash movements in time order (same instant: entry order) and checks the Q19 rule:
 * after inserting a movement, total and available cash must be &gt;= 0 at every point from that movement on.
 * Pure Java, no Spring; deposits, withdrawals and (later) trades all go through here.
 */
public final class CashReplay {

    private CashReplay() {
    }

    /** All movements in replay order with the running cash state. */
    public static List<ReplayStep> replay(Collection<CashMovement> movements) {
        List<CashMovement> ordered = new ArrayList<>(movements);
        ordered.sort(CashMovement.REPLAY_ORDER);
        List<ReplayStep> steps = new ArrayList<>(ordered.size());
        CashBalance balance = CashBalance.EMPTY;
        for (CashMovement m : ordered) {
            CashBalance after = balance.apply(m);
            steps.add(new ReplayStep(m, balance, after));
            balance = after;
        }
        return steps;
    }

    /** Cash state after all movements ("current" state, since no movement is later than now). */
    public static CashBalance finalBalance(Collection<CashMovement> movements) {
        CashBalance balance = CashBalance.EMPTY;
        for (CashMovement m : movements) {
            balance = balance.apply(m);
        }
        return balance;
    }

    /**
     * Inserts {@code candidate} into {@code existing} and replays. Existing movements at the same instant come
     * before the candidate when its sequence is {@link CashMovement#NOT_YET_ENTERED}.
     */
    public static InsertionCheck checkInsertion(Collection<CashMovement> existing, CashMovement candidate) {
        List<CashMovement> all = new ArrayList<>(existing.size() + 1);
        all.addAll(existing);
        all.add(candidate);
        ReplayStep inserted = null;
        ReplayStep violation = null;
        for (ReplayStep step : replay(all)) {
            if (step.movement() == candidate) {
                inserted = step;
            }
            if (inserted != null && !step.after().isValid()) {
                violation = step;
                break;
            }
        }
        if (inserted == null) {
            throw new IllegalStateException("candidate not found in replay");
        }
        return new InsertionCheck(inserted, Optional.ofNullable(violation));
    }
}
