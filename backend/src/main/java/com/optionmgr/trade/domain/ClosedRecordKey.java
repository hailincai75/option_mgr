package com.optionmgr.trade.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * FEAT-040: identity and sort key of a closed record (option close / expiry or stock sale). Pure Java.
 *
 * @param type     option or stock (ids are only unique per type)
 * @param id       option trade id or stock record id
 * @param closedAt close / expiry cutoff / sale time
 */
public record ClosedRecordKey(ClosedRecordType type, long id, Instant closedAt) {

    /**
     * List order: newest first; at the same instant stock sales above option records (Q92); within a type the later
     * entry (larger id) first.
     */
    public static final Comparator<ClosedRecordKey> NEWEST_FIRST = Comparator
            .comparing(ClosedRecordKey::closedAt, Comparator.reverseOrder())
            .thenComparing(k -> k.type() == ClosedRecordType.STOCK ? 0 : 1)
            .thenComparing(ClosedRecordKey::id, Comparator.reverseOrder());

    public ClosedRecordKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(closedAt, "closedAt");
    }
}
