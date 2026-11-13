package com.optionmgr.cash.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single USD cash account (Q3/Q4). Its row is locked (PESSIMISTIC_WRITE) by every transaction that
 * adds cash movements, so replay checks and inserts are serialized.
 */
@Entity
@Table(name = "cash_account")
public class CashAccountEntity {

    @Id
    private Long id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CashAccountEntity() {
    }

    /** FEAT-033: the lock row of a new account (same id as the account). */
    public CashAccountEntity(long id, String currency, Instant createdAt) {
        this.id = id;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
