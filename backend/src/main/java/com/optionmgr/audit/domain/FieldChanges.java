package com.optionmgr.audit.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Collects the fields whose display values differ between before and after, in declaration order. */
public final class FieldChanges {

    private final List<FieldChange> changes = new ArrayList<>();

    public FieldChanges compare(String field, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, label, before, after));
        }
        return this;
    }

    public List<FieldChange> list() {
        return List.copyOf(changes);
    }
}
