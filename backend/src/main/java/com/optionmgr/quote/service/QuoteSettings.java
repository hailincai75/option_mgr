package com.optionmgr.quote.service;

import com.optionmgr.quote.domain.QuoteSource;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * FEAT-052: the effective option source and risk-free rate. They come from {@link QuoteProperties} (read at
 * startup); the e2e test support may override them ({@code PUT /api/test-support/market-data/settings}), which is
 * like changing the configuration and restarting. Changing the source drops the Yahoo session.
 */
@Service
public class QuoteSettings {

    /** The effective values. */
    public record Values(QuoteSource optionSource, BigDecimal riskFreeRatePercent) {
    }

    private final QuoteProperties props;
    private final YahooSession session;
    private volatile Values override;

    public QuoteSettings(QuoteProperties props, YahooSession session) {
        this.props = props;
        this.session = session;
    }

    public Values current() {
        Values o = override;
        return o != null ? o : configured();
    }

    public QuoteSource optionSource() {
        return current().optionSource();
    }

    public BigDecimal riskFreeRatePercent() {
        return current().riskFreeRatePercent();
    }

    /** r as a decimal (4.00 % -&gt; 0.04). */
    public BigDecimal riskFreeRate() {
        return riskFreeRatePercent().movePointLeft(2);
    }

    /** Test support: null fields = the configured value. */
    public Values set(QuoteSource source, BigDecimal ratePercent) {
        Values c = configured();
        Values next = new Values(source == null ? c.optionSource() : source,
                ratePercent == null ? c.riskFreeRatePercent() : ratePercent);
        if (next.optionSource() != current().optionSource()) {
            session.clear();
        }
        override = next;
        return next;
    }

    /** Back to the configured values. */
    public void reset() {
        set(null, null);
        override = null;
    }

    private Values configured() {
        return new Values(props.optionSourceValue(), props.riskFreeRatePercent());
    }
}
