-- V6: "has this record ever been edited" flag behind the hasAuditLog field of list rows (the 历史 link).
-- REAL-DATA MIGRATION: only adds a column and sets it; no existing value is changed. The application backs up a
-- file database before running pending migrations (Q60, DatabaseBackupBeforeMigration).
--
-- From now on the application sets the flag to TRUE in the same transaction as the edit's audit record (only the
-- edited record, never records corrected as a side effect); creation leaves it FALSE.

ALTER TABLE option_trade ADD COLUMN has_audit_log BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE cash_transfer ADD COLUMN has_audit_log BOOLEAN DEFAULT FALSE NOT NULL;

-- Backfill: rows that already have at least one audit record of their own object type, id and account.
UPDATE option_trade t SET has_audit_log = TRUE
WHERE EXISTS (SELECT 1 FROM audit_record a
              WHERE a.object_type = 'OPTION_TRADE' AND a.object_id = t.id AND a.account_id = t.account_id);

UPDATE cash_transfer c SET has_audit_log = TRUE
WHERE EXISTS (SELECT 1 FROM audit_record a
              WHERE a.object_type = 'CASH_TRANSFER' AND a.object_id = c.id AND a.account_id = c.account_id);
