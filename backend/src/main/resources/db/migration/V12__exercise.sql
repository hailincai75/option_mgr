-- V12 (FEAT-014): a long call / put is exercised. An exercise is an option_trade row with action 'EXR' (same id space
-- = entry order as trades) at 16:00 New York of the exercise date, net amount 0; its exercise fee is stored in the
-- existing assignment_fee column (the fee of a record with a stock leg). It generates one stock_trade row of type
-- 'EXERCISE_BUY' (call: amount = current stock cost) or 'EXERCISE_SELL' (put: amount = current sale proceeds), with
-- option_trade_id = the EXR row; the application corrects that amount in the same transaction whenever a replay
-- changes it. Its cash effect is a cash_ledger_entry with source_type 'OPTION_EXERCISE' (no constraint on
-- source_type, nothing to change there).
-- REAL-DATA MIGRATION: widens check constraints and one column; no existing row or value is changed. The application
-- backs up a file database before running pending migrations (Q60, DatabaseBackupBeforeMigration).

ALTER TABLE option_trade DROP CONSTRAINT ck_option_trade_action;
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_action
    CHECK (action IN ('BTO', 'STO', 'BTC', 'STC', 'EXP', 'ASG', 'EXR'));

ALTER TABLE option_trade DROP CONSTRAINT ck_option_trade_net;
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_net
    CHECK ((action NOT IN ('EXP', 'ASG', 'EXR') AND net_amount > 0)
        OR (action IN ('EXP', 'ASG', 'EXR') AND net_amount = 0));

ALTER TABLE option_trade DROP CONSTRAINT ck_option_trade_assignment_fee;
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_assignment_fee
    CHECK ((action IN ('ASG', 'EXR') AND assignment_fee IS NOT NULL AND assignment_fee >= 0)
        OR (action NOT IN ('ASG', 'EXR') AND assignment_fee IS NULL));

ALTER TABLE stock_trade ALTER COLUMN type SET DATA TYPE VARCHAR(16);

ALTER TABLE stock_trade DROP CONSTRAINT ck_stock_trade_type;
ALTER TABLE stock_trade ADD CONSTRAINT ck_stock_trade_type
    CHECK (type IN ('BUY', 'SELL', 'TRANSFER_IN', 'ASSIGN_BUY', 'ASSIGN_SELL', 'EXERCISE_BUY', 'EXERCISE_SELL'));

ALTER TABLE stock_trade DROP CONSTRAINT ck_stock_trade_option_trade;
ALTER TABLE stock_trade ADD CONSTRAINT ck_stock_trade_option_trade
    CHECK ((type IN ('ASSIGN_BUY', 'ASSIGN_SELL', 'EXERCISE_BUY', 'EXERCISE_SELL') AND option_trade_id IS NOT NULL)
        OR (type NOT IN ('ASSIGN_BUY', 'ASSIGN_SELL', 'EXERCISE_BUY', 'EXERCISE_SELL') AND option_trade_id IS NULL));
