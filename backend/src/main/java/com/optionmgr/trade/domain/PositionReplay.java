package com.optionmgr.trade.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Position of one contract at every point in time: its trades replayed in (time, entry order), the signed
 * contract count accumulated (BTO/BTC +, STO/STC -). Pure Java, no Spring.
 *
 * <p>FEAT-001 only has opens, so the position only grows; FEAT-004 stores closes in the same trade table and
 * they flow through the same replay (closes reduce the position; when it returns to 0 the position's
 * multiplier is forgotten). FEAT-004 adds its own close checks (e.g. close quantity &lt;= position) on top of
 * {@link #replay}.
 */
public final class PositionReplay {

    private PositionReplay() {
    }

    /** Every trade in replay order with the position just before it. */
    public static List<PositionStep> replay(Collection<PositionEvent> events) {
        List<PositionEvent> ordered = new ArrayList<>(events);
        ordered.sort(PositionEvent.REPLAY_ORDER);
        List<PositionStep> steps = new ArrayList<>(ordered.size());
        int position = 0;
        int multiplier = 0;
        for (PositionEvent e : ordered) {
            steps.add(new PositionStep(e, position, multiplier));
            if (position == 0) {
                multiplier = e.multiplier();
            }
            position += e.signedContracts();
            if (position == 0) {
                multiplier = 0;
            }
        }
        return steps;
    }

    /**
     * Inserts {@code candidate} among {@code existing} trades of the same contract and reports, from the
     * candidate on (earlier points are unaffected), the first trade that would open against the position (Q7)
     * and the first trade whose multiplier differs from the open position's (Q23).
     */
    public static PositionInsertionCheck checkInsertion(Collection<PositionEvent> existing, PositionEvent candidate) {
        List<PositionEvent> all = new ArrayList<>(existing.size() + 1);
        all.addAll(existing);
        all.add(candidate);
        PositionStep inserted = null;
        PositionStep directionConflict = null;
        PositionStep multiplierMismatch = null;
        for (PositionStep step : replay(all)) {
            if (step.event() == candidate) {
                inserted = step;
            }
            if (inserted == null) {
                continue;
            }
            if (directionConflict == null && step.opensAgainstPosition()) {
                directionConflict = step;
            }
            if (multiplierMismatch == null && step.multiplierDiffers()) {
                multiplierMismatch = step;
            }
        }
        if (inserted == null) {
            throw new IllegalStateException("candidate not found in replay");
        }
        return new PositionInsertionCheck(inserted, Optional.ofNullable(directionConflict),
                Optional.ofNullable(multiplierMismatch));
    }
}
