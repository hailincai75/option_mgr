package com.optionmgr.audit.repository;

import com.optionmgr.audit.domain.AuditObjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRecordRepository extends JpaRepository<AuditRecordEntity, Long> {

    Page<AuditRecordEntity> findByAccountId(Long accountId, Pageable pageable);

    Page<AuditRecordEntity> findByAccountIdAndObjectType(Long accountId, AuditObjectType objectType, Pageable pageable);

    /**
     * Records of one object: its own records plus those that list it as a further object (FEAT-043: an expiration
     * adjustment of several trades).
     */
    @Query(value = "SELECT a FROM AuditRecordEntity a WHERE a.accountId = :accountId AND a.objectType = :objectType"
            + " AND (a.objectId = :objectId OR EXISTS (SELECT 1 FROM AuditRecordObjectEntity o"
            + " WHERE o.auditRecordId = a.id AND o.objectType = :objectType AND o.objectId = :objectId))",
            countQuery = "SELECT COUNT(a) FROM AuditRecordEntity a WHERE a.accountId = :accountId"
                    + " AND a.objectType = :objectType AND (a.objectId = :objectId OR EXISTS (SELECT 1"
                    + " FROM AuditRecordObjectEntity o WHERE o.auditRecordId = a.id AND o.objectType = :objectType"
                    + " AND o.objectId = :objectId))")
    Page<AuditRecordEntity> findByObject(@Param("accountId") Long accountId,
                                         @Param("objectType") AuditObjectType objectType,
                                         @Param("objectId") Long objectId, Pageable pageable);
}
