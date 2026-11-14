package com.optionmgr.calendar.repository;

import com.optionmgr.calendar.domain.AdhocClosure;
import com.optionmgr.calendar.domain.AdhocClosureStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** FEAT-042: one detected ad-hoc closure (V14). */
@Entity
@Table(name = "adhoc_closure")
public class AdhocClosureEntity {

    @Id
    @Column(name = "closure_date", nullable = false)
    private LocalDate closureDate;

    @Column(name = "event_name", nullable = false, length = 200)
    private String eventName;

    @Column(name = "detected_on", nullable = false)
    private LocalDate detectedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdhocClosureStatus status;

    @Column(name = "revoked_on")
    private LocalDate revokedOn;

    protected AdhocClosureEntity() {
    }

    public AdhocClosureEntity(AdhocClosure c) {
        this.closureDate = c.date();
        update(c);
    }

    public void update(AdhocClosure c) {
        this.eventName = c.eventName();
        this.detectedOn = c.detectedOn();
        this.status = c.status();
        this.revokedOn = c.revokedOn();
    }

    public AdhocClosure toDomain() {
        return new AdhocClosure(closureDate, eventName, detectedOn, status, revokedOn);
    }
}
