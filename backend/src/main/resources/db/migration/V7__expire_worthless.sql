-- V7 (FEAT-012): expire worthless. An expiry is an option_trade row with action 'EXP' (same id space = entry order
-- as trades), recorded at the expiration cutoff (16:00 New York) with net_amount 0. Its frozen-cash release (short
-- put) is a cash_ledger_entry with source_type 'OPTION_EXPIRE' (no constraint on source_type, nothing to change).
-- REAL-DATA MIGRATION: only widens two check constraints; no existing row or value is changed. The application backs
-- up a file database before running pending migrations (Q60, DatabaseBackupBeforeMigration).

ALTER TABLE option_trade DROP CONSTRAINT ck_option_trade_action;
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_action
    CHECK (action IN ('BTO', 'STO', 'BTC', 'STC', 'EXP'));

-- Trades keep a positive net amount; an expiry has exactly 0.
ALTER TABLE option_trade DROP CONSTRAINT ck_option_trade_net;
ALTER TABLE option_trade ADD CONSTRAINT ck_option_trade_net
    CHECK ((action <> 'EXP' AND net_amount > 0) OR (action = 'EXP' AND net_amount = 0));
