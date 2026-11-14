package com.optionmgr.calendar.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * FEAT-042 rule 2: one detected ad-hoc closure (one per date, for all accounts).
 *
 * @param detectedOn New York date of the fetch that first detected it; never changes
 * @param revokedOn  New York date of the fetch that revoked it (Q127); null while ACTIVE
 */
public record AdhocClosure(LocalDate date, String eventName, LocalDate detectedOn, AdhocClosureStatus status,
                           LocalDate revokedOn) {

    public AdhocClosure {
        Objects.requireNonNull(date);
        Objects.requireNonNull(eventName);
        Objects.requireNonNull(detectedOn);
        Objects.requireNonNull(status);
        if ((status == AdhocClosureStatus.ACTIVE) != (revokedOn == null)) {
            throw new IllegalArgumentException("revokedOn must be set exactly when REVOKED");
        }
    }

    public static AdhocClosure detected(LocalDate date, String eventName, LocalDate today) {
        return new AdhocClosure(date, eventName, today, AdhocClosureStatus.ACTIVE, null);
    }

    public boolean active() {
        return status == AdhocClosureStatus.ACTIVE;
    }
}
