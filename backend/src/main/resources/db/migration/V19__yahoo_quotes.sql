-- V19 (FEAT-052): Yahoo Finance as option data source (Q261). REAL-DATA MIGRATION: no stored quote value changes.
-- * underlying_quote: price_source may be YAHOO; last_yahoo_result = the Yahoo outcome of the last attempt.
-- * option_quote: quote_source = source of the stored quote (CBOE / YAHOO; NULL = never quoted). Every existing
--   quote came from CBOE (rule 9), so quoted rows get 'CBOE'.
-- * option_quote_close: quote_source of each snapshot row; all history is CBOE.

ALTER TABLE underlying_quote DROP CONSTRAINT ck_underlying_quote_source;
ALTER TABLE underlying_quote ADD CONSTRAINT ck_underlying_quote_source
    CHECK (price_source IS NULL OR price_source IN ('CBOE', 'FINNHUB', 'YAHOO'));
ALTER TABLE underlying_quote ADD COLUMN last_yahoo_result VARCHAR(20);

ALTER TABLE option_quote ADD COLUMN quote_source VARCHAR(10);
UPDATE option_quote SET quote_source = 'CBOE' WHERE quote_time IS NOT NULL;
ALTER TABLE option_quote ADD CONSTRAINT ck_option_quote_source
    CHECK ((quote_source IS NULL OR quote_source IN ('CBOE', 'YAHOO')) AND ((quote_time IS NULL) = (quote_source IS NULL)));

ALTER TABLE option_quote_close ADD COLUMN quote_source VARCHAR(10);
UPDATE option_quote_close SET quote_source = 'CBOE';
