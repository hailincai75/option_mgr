package com.optionmgr.audit.repository;

import com.optionmgr.audit.domain.AuditObjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRecordRepository extends JpaRepository<AuditRecordEntity, Long> {

    Page<AuditRecordEntity> findByObjectType(AuditObjectType objectType, Pageable pageable);

    Page<AuditRecordEntity> findByObjectTypeAndObjectId(AuditObjectType objectType, Long objectId, Pageable pageable);
}
