package com.optionmgr.trade.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * FEAT-039: shares held vs shares covered by short calls over time, for one account and one symbol. Pure Java.
 *
 * <p>Events are ordered by {@link Key} = (time, entry order across option and stock records, Q19). At one key a
 * release (BTC / EXP / ASG of a lot) comes before a share move, and a share move before an opening: a call
 * assignment's release and its delivered shares (ASSIGN_SELL) share one key, so the lot stops needing shares at the
 * moment the shares leave. The invariant is: after every event, held &gt;= covered, where covered counts only the
 * lots selected by the caller (CHECKED lots, plus lots treated as checked for a check).
 */
public final class CoverageTimeline {

    /** Replay position: time, then entry order. */
    public record Key(Instant at, long seq) implements Comparable<Key> {

        private static final Comparator<Key> ORDER = Comparator.comparing(Key::at).thenComparingLong(Key::seq);

        public Key {
            Objects.requireNonNull(at, "at");
        }

        @Override
        public int compareTo(Key o) {
            return ORDER.compare(this, o);
        }
    }

    /** A stock record: + shares (buy, transfer-in, put assignment), - shares (sell, call assignment). */
    public record ShareMove(Key key, long delta) {
    }

    /**
     * An STO call lot.
     *
     * @param requiredShares contracts x multiplier (whole lot, rule 1)
     * @param released       when it stops needing shares (BTC / EXP / ASG); null while still open
     * @param checked        CHECKED (occupies shares) or LEGACY (does not)
     */
    public record CallLot(long tradeId, Key opened, long requiredShares, Key released, boolean checked) {

        public CallLot {
            Objects.requireNonNull(opened, "opened");
        }

        public boolean open() {
            return released == null;
        }
    }

    public record State(long held, long covered) {

        public long available() {
            return held - covered;
        }
    }

    /** First point where held &lt; covered: its time, the covered shares (required) and the shares held then. */
    public record Shortfall(Instant at, long required, long held) {
    }

    private record Event(Key key, int rank, long heldDelta, long coveredDelta) {
        static final Comparator<Event> ORDER = Comparator.comparing(Event::key).thenComparingInt(Event::rank);
    }

    private static final int RELEASE = 0;
    private static final int MOVE = 1;
    private static final int OPEN = 2;

    private final List<ShareMove> moves;
    private final List<CallLot> lots;

    public CoverageTimeline(List<ShareMove> moves, List<CallLot> lots) {
        this.moves = List.copyOf(moves);
        this.lots = List.copyOf(lots);
    }

    public List<CallLot> lots() {
        return lots;
    }

    public Optional<CallLot> lot(long tradeId) {
        return lots.stream().filter(l -> l.tradeId() == tradeId).findFirst();
    }

    /** Lots that occupy shares: CHECKED ones. */
    public static Predicate<CallLot> checked() {
        return CallLot::checked;
    }

    /** CHECKED lots plus the given lot ids (treated as checked). */
    public static Predicate<CallLot> checkedOr(Set<Long> extra) {
        Set<Long> ids = Set.copyOf(extra);
        return l -> l.checked() || ids.contains(l.tradeId());
    }

    /** CHECKED lots except {@code tradeId} (E1: "可用（不含本笔）"). */
    public static Predicate<CallLot> checkedExcept(long tradeId) {
        return l -> l.checked() && l.tradeId() != tradeId;
    }

    /** State right before {@code key} (events at earlier keys only). */
    public State before(Key key, Predicate<CallLot> counted) {
        long held = 0;
        long covered = 0;
        for (Event e : events(counted)) {
            if (e.key().compareTo(key) >= 0) {
                break;
            }
            held += e.heldDelta();
            covered += e.coveredDelta();
        }
        return new State(held, covered);
    }

    /** State after every event (the current state: no record is later than now). */
    public State current(Predicate<CallLot> counted) {
        long held = 0;
        long covered = 0;
        for (Event e : events(counted)) {
            held += e.heldDelta();
            covered += e.coveredDelta();
        }
        return new State(held, covered);
    }

    /** First event at or after {@code from} after which held &lt; covered. */
    public Optional<Shortfall> firstShortfall(Predicate<CallLot> counted, Instant from) {
        long held = 0;
        long covered = 0;
        for (Event e : events(counted)) {
            held += e.heldDelta();
            covered += e.coveredDelta();
            if (held < covered && !e.key().at().isBefore(from)) {
                return Optional.of(new Shortfall(e.key().at(), covered, held));
            }
        }
        return Optional.empty();
    }

    /**
     * Q101 automatic inclusion: open LEGACY lots in (open time, trade id) order; each one that, counted with the
     * CHECKED lots and the lots included before it, keeps the invariant from its opening on becomes CHECKED.
     *
     * @return ids of the lots to include, in evaluation order
     */
    public List<Long> inclusions() {
        List<CallLot> legacy = lots.stream().filter(l -> !l.checked() && l.open())
                .sorted(Comparator.comparing((CallLot l) -> l.opened().at()).thenComparingLong(CallLot::tradeId))
                .toList();
        Set<Long> included = new HashSet<>();
        List<Long> out = new ArrayList<>();
        for (CallLot lot : legacy) {
            Set<Long> trial = new HashSet<>(included);
            trial.add(lot.tradeId());
            if (firstShortfall(checkedOr(trial), lot.opened().at()).isEmpty()) {
                included.add(lot.tradeId());
                out.add(lot.tradeId());
            }
        }
        return out;
    }

    /** For a LEGACY lot: the first point where it would not be covered if it were CHECKED (hover text). */
    public Optional<Shortfall> legacyShortfall(CallLot lot) {
        return firstShortfall(checkedOr(Set.of(lot.tradeId())), lot.opened().at());
    }

    private List<Event> events(Predicate<CallLot> counted) {
        List<Event> events = new ArrayList<>();
        for (ShareMove m : moves) {
            events.add(new Event(m.key(), MOVE, m.delta(), 0));
        }
        for (CallLot l : lots) {
            if (!counted.test(l)) {
                continue;
            }
            events.add(new Event(l.opened(), OPEN, 0, l.requiredShares()));
            if (l.released() != null) {
                events.add(new Event(l.released(), RELEASE, 0, -l.requiredShares()));
            }
        }
        events.sort(Event.ORDER);
        return events;
    }
}
