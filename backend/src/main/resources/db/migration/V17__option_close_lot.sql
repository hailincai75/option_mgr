-- V17 (FEAT-051, Q255): the lots (opening trades) each BTC / STC closes - its stored selection. EXP / ASG / EXR have
-- no rows (still matched FIFO on read, Q260). An open is selected by at most one close. V18 fills it for existing
-- closes with their current FIFO match.
-- REAL-DATA MIGRATION: new table only; no existing table or row is changed.

CREATE TABLE option_close_lot (
    close_trade_id BIGINT NOT NULL,
    lot_trade_id   BIGINT NOT NULL,
    account_id     BIGINT NOT NULL,
    CONSTRAINT pk_option_close_lot PRIMARY KEY (close_trade_id, lot_trade_id),
    CONSTRAINT uq_option_close_lot_lot UNIQUE (lot_trade_id),
    CONSTRAINT fk_option_close_lot_close FOREIGN KEY (close_trade_id) REFERENCES option_trade (id),
    CONSTRAINT fk_option_close_lot_lot FOREIGN KEY (lot_trade_id) REFERENCES option_trade (id),
    CONSTRAINT fk_option_close_lot_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX ix_option_close_lot_account ON option_close_lot (account_id);
