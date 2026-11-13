package com.optionmgr.cash.repository;

import com.optionmgr.cash.domain.TransferType;
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

/** A deposit or withdrawal as entered by the user (amount positive, scale 2). */
@Entity
@Table(name = "cash_transfer")
public class CashTransferEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TransferType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** True once the transfer has been edited (it has an audit record). */
    @Column(name = "has_audit_log", nullable = false)
    private boolean hasAuditLog;

    protected CashTransferEntity() {
    }

    public CashTransferEntity(long accountId, TransferType type, BigDecimal amount, Instant occurredAt, String remark,
                              Instant createdAt) {
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.occurredAt = occurredAt;
        this.remark = remark;
        this.createdAt = createdAt;
    }

    /** FEAT-005 edit (Q24): type, amount, time and remark; the id (entry order) stays. */
    public void update(TransferType type, BigDecimal amount, Instant occurredAt, String remark) {
        this.type = type;
        this.amount = amount;
        this.occurredAt = occurredAt;
        this.remark = remark;
    }

    /** Called only by the edit path, in the transaction that writes this transfer's UPDATE audit record. */
    public void markAudited() {
        this.hasAuditLog = true;
    }

    public boolean hasAuditLog() {
        return hasAuditLog;
    }

    public Long getId() {
        return id;
    }

    public long getAccountId() {
        return accountId;
    }

    public TransferType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getRemark() {
        return remark;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
