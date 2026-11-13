package com.optionmgr.cash.repository;

import com.optionmgr.cash.domain.CashMovement;
import com.optionmgr.cash.domain.CashSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the unified cash ledger. {@code id} is the entry order; replay order is (occurredAt, id).
 * {@code amount} is the signed cash change, {@code frozenChange} the signed change of frozen cash.
 * {@code sourceType} + {@code sourceId} reference the originating record (cash_transfer, later trades).
 */
@Entity
@Table(name = "cash_ledger_entry")
public class CashLedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 40)
    private CashSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "frozen_change", nullable = false, precision = 19, scale = 2)
    private BigDecimal frozenChange;

    @Column(length = 500)
    private String description;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CashLedgerEntryEntity() {
    }

    public CashLedgerEntryEntity(long accountId, Instant occurredAt, CashSourceType sourceType, Long sourceId, BigDecimal amount,
                                 BigDecimal frozenChange, String description, String remark, Instant createdAt) {
        this.accountId = accountId;
        this.occurredAt = occurredAt;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.amount = amount;
        this.frozenChange = frozenChange;
        this.description = description;
        this.remark = remark;
        this.createdAt = createdAt;
    }

    /**
     * Corrects the frozen-cash change of a trade entry whose FIFO lot matching changed because an earlier trade was
     * back-filled (FEAT-004 Q39). The cash amount never changes.
     */
    public void correctFrozenChange(BigDecimal frozenChange) {
        this.frozenChange = frozenChange;
    }

    /** FEAT-005: an edited record keeps its entry (id = entry order) and gets its new values. */
    public void update(Instant occurredAt, CashSourceType sourceType, BigDecimal amount, BigDecimal frozenChange,
                       String description, String remark) {
        this.occurredAt = occurredAt;
        this.sourceType = sourceType;
        this.amount = amount;
        this.frozenChange = frozenChange;
        this.description = description;
        this.remark = remark;
    }

    public CashMovement toMovement() {
        return new CashMovement(occurredAt, id, amount, frozenChange);
    }

    public Long getId() {
        return id;
    }

    public long getAccountId() {
        return accountId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public CashSourceType getSourceType() {
        return sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getFrozenChange() {
        return frozenChange;
    }

    public String getDescription() {
        return description;
    }

    public String getRemark() {
        return remark;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
