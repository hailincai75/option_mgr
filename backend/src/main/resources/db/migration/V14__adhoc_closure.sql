-- V14 (FEAT-042): ad-hoc market closures detected in successful Finnhub fetches (full-day closures on a weekday that
-- were treated as trading days before). One row per date, one set for all accounts (Q53). Q127: a closure removed
-- by a later fetch is REVOKED (kept, no longer alerted); it becomes ACTIVE again if it reappears. detected_on never
-- changes after the first detection.
-- REAL-DATA MIGRATION: new table only; no existing table or row is changed, nothing is backfilled (the first
-- successful fetch after the upgrade detects closures against the data stored before it).

CREATE TABLE adhoc_closure (
    closure_date DATE         NOT NULL,
    event_name   VARCHAR(200) NOT NULL,
    detected_on  DATE         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    revoked_on   DATE,
    CONSTRAINT pk_adhoc_closure PRIMARY KEY (closure_date),
    CONSTRAINT ck_adhoc_closure_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_adhoc_closure_revoked CHECK ((status = 'ACTIVE' AND revoked_on IS NULL)
        OR (status = 'REVOKED' AND revoked_on IS NOT NULL))
);
