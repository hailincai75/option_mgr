-- V11 (FEAT-039): covered calls.
-- 1. option_trade.coverage_status: every STO call lot is CHECKED (it occupies shares) or LEGACY (entered before
--    FEAT-039, not occupying shares until the application includes it). Every existing STO call becomes LEGACY; the
--    application evaluates them once at startup (automatic inclusion, Q101). NULL for every other record.
-- 2. entry_seq on option_trade and stock_trade: one entry order across both tables, so records of the two tables at
--    the same instant replay in the order they were entered (Q19). New rows take the next value of entry_seq_gen;
--    existing rows get negative values (before every new row) ranked by (created_at, table, id).
-- REAL-DATA MIGRATION: adds columns and sets them; no existing value is changed. The application backs up a file
-- database before running pending migrations (Q60, DatabaseBackupBeforeMigration).

ALTER TABLE option_trade ADD COLUMN coverage_status VARCHAR(8);
UPDATE option_trade SET coverage_status = 'LEGACY' WHERE action = 'STO' AND option_type = 'CALL';
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_coverage
    CHECK ((action = 'STO' AND option_type = 'CALL' AND coverage_status IN ('CHECKED', 'LEGACY'))
        OR ((action <> 'STO' OR option_type <> 'CALL') AND coverage_status IS NULL));

ALTER TABLE option_trade ADD COLUMN entry_seq BIGINT;
ALTER TABLE stock_trade ADD COLUMN entry_seq BIGINT;

UPDATE option_trade o SET entry_seq =
    (SELECT COUNT(*) FROM option_trade o2
      WHERE o2.created_at < o.created_at OR (o2.created_at = o.created_at AND o2.id <= o.id))
    + (SELECT COUNT(*) FROM stock_trade s WHERE s.created_at < o.created_at)
    - 1000000000000;
UPDATE stock_trade s SET entry_seq =
    (SELECT COUNT(*) FROM option_trade o WHERE o.created_at <= s.created_at)
    + (SELECT COUNT(*) FROM stock_trade s2
        WHERE s2.created_at < s.created_at OR (s2.created_at = s.created_at AND s2.id <= s.id))
    - 1000000000000;

CREATE SEQUENCE entry_seq_gen START WITH 1;
ALTER TABLE option_trade ALTER COLUMN entry_seq SET DEFAULT NEXT VALUE FOR entry_seq_gen;
ALTER TABLE option_trade ALTER COLUMN entry_seq SET NOT NULL;
ALTER TABLE stock_trade ALTER COLUMN entry_seq SET DEFAULT NEXT VALUE FOR entry_seq_gen;
ALTER TABLE stock_trade ALTER COLUMN entry_seq SET NOT NULL;

CREATE INDEX ix_option_trade_account_symbol ON option_trade (account_id, symbol, option_type, action);
