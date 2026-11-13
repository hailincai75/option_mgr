package com.optionmgr.audit.repository;

import com.optionmgr.audit.domain.AuditObjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRecordRepository extends JpaRepository<AuditRecordEntity, Long> {

    Page<AuditRecordEntity> findByAccountId(Long accountId, Pageable pageable);

    Page<AuditRecordEntity> findByAccountIdAndObjectType(Long accountId, AuditObjectType objectType, Pageable pageable);

    Page<AuditRecordEntity> findByAccountIdAndObjectTypeAndObjectId(Long accountId, AuditObjectType objectType,
                                                                    Long objectId, Pageable pageable);
}
