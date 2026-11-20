package com.optionmgr.quote.repository;

import com.optionmgr.quote.domain.OptionState;
import com.optionmgr.quote.domain.QuotePrices;
import com.optionmgr.trade.domain.ContractKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** FEAT-016: latest quote (and manual mark) of one contract (V16 option_quote). */
@Entity
@Table(name = "option_quote")
public class OptionQuoteEntity extends OptionQuoteColumns {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fetched_at")
    private Instant fetchedAt;
    @Column(name = "missing_since")
    private Instant missingSince;
    @Column(name = "manual_mark", precision = 19, scale = 6)
    private BigDecimal manualMark;
    @Column(name = "manual_entered_at")
    private Instant manualEnteredAt;

    protected OptionQuoteEntity() {
    }

    public OptionQuoteEntity(ContractKey contract) {
        setContract(contract);
    }

    public OptionState toDomain() {
        return new OptionState(contract(), occCode, bid, ask, lastPrice, theo, iv, delta, gamma, theta, vega, rho,
                volume, openInterest, quoteTime, fetchedAt, missingSince, manualMark, manualEnteredAt, source());
    }

    public void update(OptionState s) {
        setQuote(s);
        fetchedAt = s.fetchedAt();
        missingSince = s.missingSince();
        manualMark = QuotePrices.forStorage(s.manualMark(), 6);
        manualEnteredAt = s.manualEnteredAt();
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Instant getMissingSince() {
        return missingSince;
    }

    public BigDecimal getManualMark() {
        return manualMark;
    }

    public Instant getManualEnteredAt() {
        return manualEnteredAt;
    }
}
