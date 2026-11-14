-- V13 (FEAT-009): US market holidays fetched from Finnhub, and the state of the fetch.
-- market_holiday holds only Finnhub data (one row per returned date, early closes included); years not present are
-- derived from Jollyday NYSE rules at run time and never stored. A successful fetch replaces whole years (Q127).
-- market_holiday_fetch_status is a single row (id = 1). The API key is never stored.
-- REAL-DATA MIGRATION: new tables only; no existing table or row is changed.

CREATE TABLE market_holiday (
    holiday_date DATE                        NOT NULL,
    event_name   VARCHAR(200)                NOT NULL,
    -- NULL = closed all day; otherwise the (early-close) trading hours, e.g. '09:30-13:00'
    trading_hour VARCHAR(50),
    fetched_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_market_holiday PRIMARY KEY (holiday_date)
);

CREATE TABLE market_holiday_fetch_status (
    id                  SMALLINT                    NOT NULL,
    last_attempt_at     TIMESTAMP(6) WITH TIME ZONE,
    last_attempt_result VARCHAR(20),
    last_failure_detail VARCHAR(200),
    last_success_at     TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_market_holiday_fetch_status PRIMARY KEY (id),
    CONSTRAINT ck_market_holiday_fetch_status_single CHECK (id = 1),
    CONSTRAINT ck_market_holiday_fetch_status_result CHECK (last_attempt_result IS NULL OR last_attempt_result IN
        ('SUCCESS', 'NO_KEY', 'NETWORK_ERROR', 'HTTP_ERROR', 'INVALID_RESPONSE'))
);

INSERT INTO market_holiday_fetch_status (id) VALUES (1);
