package com.optionmgr.quote.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** FEAT-016: the single row (id 1) of market_data_status (V16). */
@Entity
@Table(name = "market_data_status")
public class MarketDataStatusEntity {

    @Id
    private Short id;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;
    @Column(name = "last_refresh_at")
    private Instant lastRefreshAt;

    protected MarketDataStatusEntity() {
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant getLastRefreshAt() {
        return lastRefreshAt;
    }

    public void recordRun(Instant at, boolean success) {
        lastAttemptAt = at;
        if (success) {
            lastSuccessAt = at;
        }
    }

    public void recordRefresh(Instant at) {
        lastRefreshAt = at;
    }
}
