package com.optionmgr.audit.repository;

import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One immutable audit record (Q12): written once, never updated or deleted. JSON columns hold display data. */
@Entity
@Immutable
@Table(name = "audit_record")
public class AuditRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "operated_at", nullable = false)
    private Instant operatedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private AuditOperation operation;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "object_type", nullable = false, length = 20)
    private AuditObjectType objectType;

    @Column(name = "object_id", nullable = false)
    private Long objectId;

    @Column(nullable = false, length = 200)
    private String summary;

    @Column(name = "before_json", length = 10000)
    private String beforeJson;

    @Column(name = "after_json", length = 10000)
    private String afterJson;

    @Column(name = "changes_json", nullable = false, length = 10000)
    private String changesJson;

    @Column(name = "side_effects_json", nullable = false, length = 10000)
    private String sideEffectsJson;

    protected AuditRecordEntity() {
    }

    public AuditRecordEntity(long accountId, Instant operatedAt, AuditOperation operation, AuditObjectType objectType, long objectId,
                             String summary, String beforeJson, String afterJson, String changesJson,
                             String sideEffectsJson) {
        this.accountId = accountId;
        this.operatedAt = operatedAt;
        this.operation = operation;
        this.objectType = objectType;
        this.objectId = objectId;
        this.summary = summary;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.changesJson = changesJson;
        this.sideEffectsJson = sideEffectsJson;
    }

    public Long getId() {
        return id;
    }

    public long getAccountId() {
        return accountId;
    }

    public Instant getOperatedAt() {
        return operatedAt;
    }

    public AuditOperation getOperation() {
        return operation;
    }

    public AuditObjectType getObjectType() {
        return objectType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public String getSummary() {
        return summary;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public String getChangesJson() {
        return changesJson;
    }

    public String getSideEffectsJson() {
        return sideEffectsJson;
    }
}
