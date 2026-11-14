package com.optionmgr.audit.repository;

import org.springframework.data.jpa.repository.JpaRepository;

/** FEAT-043: further objects of audit records. */
public interface AuditRecordObjectRepository extends JpaRepository<AuditRecordObjectEntity, AuditRecordObjectEntity.Key> {
}
