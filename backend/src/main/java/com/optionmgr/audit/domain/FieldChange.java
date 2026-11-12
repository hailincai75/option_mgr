package com.optionmgr.audit.domain;

import java.util.Objects;

/**
 * One changed field of an edit, already formatted for display ("—" for an empty value).
 *
 * @param field  property name, e.g. {@code netAmount}
 * @param label  Chinese label, e.g. 净额
 * @param before display value before
 * @param after  display value after
 */
public record FieldChange(String field, String label, String before, String after) {

    public static final String EMPTY = "—";

    public FieldChange {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(label, "label");
        before = before == null ? EMPTY : before;
        after = after == null ? EMPTY : after;
    }

    /** "净额：360.00 → 365.00". */
    public String text() {
        return label + "：" + before + " → " + after;
    }
}
