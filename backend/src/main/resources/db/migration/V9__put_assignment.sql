-- V9 (FEAT-013): a short put is assigned. An assignment is an option_trade row with action 'ASG' (same id space =
-- entry order as trades) at 16:00 New York of the assignment date, net amount 0, plus its optional assignment fee.
-- It generates one stock_trade row of type 'ASSIGN_BUY' (option_trade_id = the ASG row) whose amount is the current
-- stock cost (strike x shares rounded up - premium of the assigned lots + fee); the application corrects that amount
-- in the same transaction whenever a replay changes it. Its cash effect is a cash_ledger_entry with source_type
-- 'OPTION_ASSIGN' (no constraint on source_type, nothing to change there).
-- REAL-DATA MIGRATION: widens check constraints and adds nullable columns; no existing row or value is changed. The
-- application backs up a file database before running pending migrations (Q60, DatabaseBackupBeforeMigration).

ALTER TABLE option_trade DROP CONSTRAINT ck_option_trade_action;
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_action
    CHECK (action IN ('BTO', 'STO', 'BTC', 'STC', 'EXP', 'ASG'));

ALTER TABLE option_trade DROP CONSTRAINT ck_option_trade_net;
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_net
    CHECK ((action NOT IN ('EXP', 'ASG') AND net_amount > 0) OR (action IN ('EXP', 'ASG') AND net_amount = 0));

ALTER TABLE option_trade ADD COLUMN assignment_fee NUMERIC(19, 2);
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_assignment_fee
    CHECK ((action = 'ASG' AND assignment_fee IS NOT NULL AND assignment_fee >= 0)
        OR (action <> 'ASG' AND assignment_fee IS NULL));

ALTER TABLE stock_trade DROP CONSTRAINT ck_stock_trade_type;
ALTER TABLE stock_trade ADD CONSTRAINT ck_stock_trade_type
    CHECK (type IN ('BUY', 'SELL', 'TRANSFER_IN', 'ASSIGN_BUY'));

ALTER TABLE stock_trade ADD COLUMN option_trade_id BIGINT;
ALTER TABLE stock_trade ADD CONSTRAINT fk_stock_trade_option_trade
    FOREIGN KEY (option_trade_id) REFERENCES option_trade (id);
ALTER TABLE stock_trade ADD CONSTRAINT uq_stock_trade_option_trade UNIQUE (option_trade_id);
ALTER TABLE stock_trade ADD CONSTRAINT ck_stock_trade_option_trade
    CHECK ((type = 'ASSIGN_BUY' AND option_trade_id IS NOT NULL) OR (type <> 'ASSIGN_BUY' AND option_trade_id IS NULL));
