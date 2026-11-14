package com.optionmgr.quote.repository;

import com.optionmgr.quote.domain.PriceSource;
import com.optionmgr.quote.domain.QuotePrices;
import com.optionmgr.quote.domain.SourceResult;
import com.optionmgr.quote.domain.UnderlyingState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** FEAT-016: latest data of one underlying symbol (V16 underlying_quote). */
@Entity
@Table(name = "underlying_quote")
public class UnderlyingQuoteEntity {

    @Id
    @Column(length = 20)
    private String symbol;

    @Column(precision = 19, scale = 6)
    private BigDecimal price;
    @Column(precision = 19, scale = 6)
    private BigDecimal bid;
    @Column(precision = 19, scale = 6)
    private BigDecimal ask;
    @Column(name = "prev_close", precision = 19, scale = 6)
    private BigDecimal prevClose;
    @Column(name = "price_source", length = 10)
    private String priceSource;
    @Column(name = "quote_time")
    private Instant quoteTime;
    @Column(name = "last_price_success_at")
    private Instant lastPriceSuccessAt;
    @Column(name = "last_chain_success_at")
    private Instant lastChainSuccessAt;
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
    @Column(name = "last_price_failed", nullable = false)
    private boolean lastPriceFailed;
    @Column(name = "last_chain_failed", nullable = false)
    private boolean lastChainFailed;
    @Column(name = "last_cboe_result", length = 20)
    private String lastCboeResult;
    @Column(name = "last_finnhub_result", length = 20)
    private String lastFinnhubResult;
    @Column(name = "last_failure_detail", length = 500)
    private String lastFailureDetail;
    @Column(name = "manual_price", precision = 19, scale = 6)
    private BigDecimal manualPrice;
    @Column(name = "manual_entered_at")
    private Instant manualEnteredAt;

    protected UnderlyingQuoteEntity() {
    }

    public UnderlyingQuoteEntity(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    public UnderlyingState toDomain() {
        return new UnderlyingState(symbol, price, bid, ask, prevClose,
                priceSource == null ? null : PriceSource.valueOf(priceSource), quoteTime, lastPriceSuccessAt,
                lastChainSuccessAt, lastAttemptAt, lastPriceFailed, lastChainFailed, result(lastCboeResult),
                result(lastFinnhubResult), lastFailureDetail, manualPrice, manualEnteredAt);
    }

    public void update(UnderlyingState s) {
        price = QuotePrices.forStorage(s.price(), 6);
        bid = QuotePrices.forStorage(s.bid(), 6);
        ask = QuotePrices.forStorage(s.ask(), 6);
        prevClose = QuotePrices.forStorage(s.prevClose(), 6);
        priceSource = s.source() == null ? null : s.source().name();
        quoteTime = s.quoteTime();
        lastPriceSuccessAt = s.lastPriceSuccessAt();
        lastChainSuccessAt = s.lastChainSuccessAt();
        lastAttemptAt = s.lastAttemptAt();
        lastPriceFailed = s.priceFailed();
        lastChainFailed = s.chainFailed();
        lastCboeResult = s.lastCboeResult() == null ? null : s.lastCboeResult().name();
        lastFinnhubResult = s.lastFinnhubResult() == null ? null : s.lastFinnhubResult().name();
        String d = s.lastFailureDetail();
        lastFailureDetail = d != null && d.length() > 500 ? d.substring(0, 500) : d;
        manualPrice = QuotePrices.forStorage(s.manualPrice(), 6);
        manualEnteredAt = s.manualEnteredAt();
    }

    private static SourceResult result(String s) {
        return s == null ? null : SourceResult.valueOf(s);
    }
}
