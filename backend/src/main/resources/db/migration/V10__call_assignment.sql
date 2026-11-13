-- V10 (FEAT-041): a short call is assigned. Same ASG option_trade row as FEAT-013 (nothing to change there); it
-- generates one stock_trade row of type 'ASSIGN_SELL' (option_trade_id = the ASG row) whose amount is the current
-- sale proceeds (strike x shares rounded down + premium of the assigned lots - fee, always > 0); the application
-- corrects that amount in the same transaction whenever a replay changes it. Its cash effect is the ASG row's
-- OPTION_ASSIGN ledger entry (+(strike amount - fee), frozen 0).
-- REAL-DATA MIGRATION: widens check constraints only; no existing row or value is changed. The application backs up
-- a file database before running pending migrations (Q60, DatabaseBackupBeforeMigration).

ALTER TABLE stock_trade DROP CONSTRAINT ck_stock_trade_type;
ALTER TABLE stock_trade ADD CONSTRAINT ck_stock_trade_type
    CHECK (type IN ('BUY', 'SELL', 'TRANSFER_IN', 'ASSIGN_BUY', 'ASSIGN_SELL'));

ALTER TABLE stock_trade DROP CONSTRAINT ck_stock_trade_option_trade;
ALTER TABLE stock_trade ADD CONSTRAINT ck_stock_trade_option_trade
    CHECK ((type IN ('ASSIGN_BUY', 'ASSIGN_SELL') AND option_trade_id IS NOT NULL)
        OR (type NOT IN ('ASSIGN_BUY', 'ASSIGN_SELL') AND option_trade_id IS NULL));
