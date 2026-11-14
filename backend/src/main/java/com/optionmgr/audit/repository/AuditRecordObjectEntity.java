package com.optionmgr.audit.repository;

import com.optionmgr.audit.domain.AuditObjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * FEAT-043: a further object an audit record refers to (an expiration adjustment changes several trades), so the
 * object filter of the audit log also finds it. Immutable like the audit record.
 */
@Entity
@Immutable
@Table(name = "audit_record_object")
@IdClass(AuditRecordObjectEntity.Key.class)
public class AuditRecordObjectEntity {

    @Id
    @Column(name = "audit_record_id", nullable = false)
    private Long auditRecordId;

    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "object_type", nullable = false, length = 20)
    private AuditObjectType objectType;

    @Id
    @Column(name = "object_id", nullable = false)
    private Long objectId;

    protected AuditRecordObjectEntity() {
    }

    public AuditRecordObjectEntity(long auditRecordId, AuditObjectType objectType, long objectId) {
        this.auditRecordId = auditRecordId;
        this.objectType = objectType;
        this.objectId = objectId;
    }

    public static class Key implements Serializable {
        private Long auditRecordId;
        private AuditObjectType objectType;
        private Long objectId;

        public Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(auditRecordId, k.auditRecordId)
                    && objectType == k.objectType && Objects.equals(objectId, k.objectId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(auditRecordId, objectType, objectId);
        }
    }
}
