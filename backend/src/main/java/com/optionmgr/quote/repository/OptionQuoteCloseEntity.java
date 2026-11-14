package com.optionmgr.quote.repository;

import com.optionmgr.quote.domain.OptionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** FEAT-016 Q142: the 16:20 close snapshot of one contract on one day (V16 option_quote_close). */
@Entity
@Table(name = "option_quote_close")
public class OptionQuoteCloseEntity extends OptionQuoteColumns {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected OptionQuoteCloseEntity() {
    }

    public OptionQuoteCloseEntity(LocalDate date, OptionState s) {
        this.snapshotDate = date;
        setContract(s.contract());
    }

    public void update(OptionState s, Instant at) {
        setQuote(s);
        capturedAt = at;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
