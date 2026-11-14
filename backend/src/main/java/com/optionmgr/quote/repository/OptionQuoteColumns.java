package com.optionmgr.quote.repository;

import com.optionmgr.quote.domain.OptionState;
import com.optionmgr.quote.domain.QuotePrices;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** FEAT-016: contract + quote columns shared by option_quote and option_quote_close. */
@MappedSuperclass
public abstract class OptionQuoteColumns {

    @Column(nullable = false, length = 20)
    protected String symbol;
    @Column(name = "expiration_date", nullable = false)
    protected LocalDate expirationDate;
    @Column(nullable = false, precision = 19, scale = 6)
    protected BigDecimal strike;
    @Column(name = "option_type", nullable = false, length = 4)
    protected String optionType;
    @Column(name = "occ_code", length = 40)
    protected String occCode;
    @Column(precision = 19, scale = 6)
    protected BigDecimal bid;
    @Column(precision = 19, scale = 6)
    protected BigDecimal ask;
    @Column(name = "last_price", precision = 19, scale = 6)
    protected BigDecimal lastPrice;
    @Column(precision = 19, scale = 6)
    protected BigDecimal theo;
    @Column(precision = 19, scale = 8)
    protected BigDecimal iv;
    @Column(precision = 19, scale = 8)
    protected BigDecimal delta;
    @Column(precision = 19, scale = 8)
    protected BigDecimal gamma;
    @Column(precision = 19, scale = 8)
    protected BigDecimal theta;
    @Column(precision = 19, scale = 8)
    protected BigDecimal vega;
    @Column(precision = 19, scale = 8)
    protected BigDecimal rho;
    @Column
    protected Long volume;
    @Column(name = "open_interest")
    protected Long openInterest;
    @Column(name = "quote_time")
    protected Instant quoteTime;

    protected void setContract(ContractKey c) {
        symbol = c.symbol();
        expirationDate = c.expirationDate();
        strike = c.strike();
        optionType = c.optionType().name();
    }

    public ContractKey contract() {
        return new ContractKey(symbol, expirationDate, strike, OptionType.valueOf(optionType));
    }

    protected void setQuote(OptionState s) {
        occCode = s.occCode();
        bid = QuotePrices.forStorage(s.bid(), 6);
        ask = QuotePrices.forStorage(s.ask(), 6);
        lastPrice = QuotePrices.forStorage(s.last(), 6);
        theo = QuotePrices.forStorage(s.theo(), 6);
        iv = QuotePrices.forStorage(s.iv(), 8);
        delta = QuotePrices.forStorage(s.delta(), 8);
        gamma = QuotePrices.forStorage(s.gamma(), 8);
        theta = QuotePrices.forStorage(s.theta(), 8);
        vega = QuotePrices.forStorage(s.vega(), 8);
        rho = QuotePrices.forStorage(s.rho(), 8);
        volume = s.volume();
        openInterest = s.openInterest();
        quoteTime = s.quoteTime();
    }

    public String getOccCode() {
        return occCode;
    }

    public BigDecimal getBid() {
        return bid;
    }

    public BigDecimal getAsk() {
        return ask;
    }

    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    public BigDecimal getTheo() {
        return theo;
    }

    public BigDecimal getIv() {
        return iv;
    }

    public BigDecimal getDelta() {
        return delta;
    }

    public BigDecimal getGamma() {
        return gamma;
    }

    public BigDecimal getTheta() {
        return theta;
    }

    public BigDecimal getVega() {
        return vega;
    }

    public BigDecimal getRho() {
        return rho;
    }

    public Long getVolume() {
        return volume;
    }

    public Long getOpenInterest() {
        return openInterest;
    }

    public Instant getQuoteTime() {
        return quoteTime;
    }
}
