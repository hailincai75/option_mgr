package com.optionmgr.quote.repository;

import com.optionmgr.quote.domain.QuotePrices;
import com.optionmgr.quote.domain.UnderlyingState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** FEAT-016 Q142: the 16:20 close snapshot of one underlying on one day (V16 underlying_quote_close). */
@Entity
@Table(name = "underlying_quote_close")
public class UnderlyingQuoteCloseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;
    @Column(nullable = false, length = 20)
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
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected UnderlyingQuoteCloseEntity() {
    }

    public UnderlyingQuoteCloseEntity(LocalDate date, String symbol) {
        this.snapshotDate = date;
        this.symbol = symbol;
    }

    public void update(UnderlyingState s, Instant at) {
        price = QuotePrices.forStorage(s.price(), 6);
        bid = QuotePrices.forStorage(s.bid(), 6);
        ask = QuotePrices.forStorage(s.ask(), 6);
        prevClose = QuotePrices.forStorage(s.prevClose(), 6);
        priceSource = s.source() == null ? null : s.source().name();
        quoteTime = s.quoteTime();
        capturedAt = at;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getBid() {
        return bid;
    }

    public BigDecimal getAsk() {
        return ask;
    }

    public BigDecimal getPrevClose() {
        return prevClose;
    }

    public String getPriceSource() {
        return priceSource;
    }

    public Instant getQuoteTime() {
        return quoteTime;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
