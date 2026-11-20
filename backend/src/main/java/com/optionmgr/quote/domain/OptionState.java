package com.optionmgr.quote.domain;

import com.optionmgr.trade.domain.ContractKey;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * FEAT-016: stored latest quote of one contract. {@code quoteTime} null = never quoted (the row may exist only for a
 * manual mark); {@code missingSince} non-null = no longer in the source (Q143). FEAT-052: {@code source} = where the
 * stored quote came from (null exactly when never quoted); it decides the mark rule.
 */
public record OptionState(ContractKey contract, String occCode, BigDecimal bid, BigDecimal ask, BigDecimal last,
                          BigDecimal theo, BigDecimal iv, BigDecimal delta, BigDecimal gamma, BigDecimal theta,
                          BigDecimal vega, BigDecimal rho, Long volume, Long openInterest, Instant quoteTime,
                          Instant fetchedAt, Instant missingSince, BigDecimal manualMark, Instant manualEnteredAt,
                          QuoteSource source) {

    public OptionState {
        if (quoteTime == null) {
            source = null;
        } else if (source == null) {
            source = QuoteSource.CBOE;
        }
    }

    /** FEAT-016 form: a quoted state is a CBOE quote. */
    public OptionState(ContractKey contract, String occCode, BigDecimal bid, BigDecimal ask, BigDecimal last,
                       BigDecimal theo, BigDecimal iv, BigDecimal delta, BigDecimal gamma, BigDecimal theta,
                       BigDecimal vega, BigDecimal rho, Long volume, Long openInterest, Instant quoteTime,
                       Instant fetchedAt, Instant missingSince, BigDecimal manualMark, Instant manualEnteredAt) {
        this(contract, occCode, bid, ask, last, theo, iv, delta, gamma, theta, vega, rho, volume, openInterest,
                quoteTime, fetchedAt, missingSince, manualMark, manualEnteredAt, QuoteSource.CBOE);
    }

    public static OptionState empty(ContractKey contract) {
        return new OptionState(contract, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    public OptionState withManual(BigDecimal mark, Instant at) {
        return new OptionState(contract, occCode, bid, ask, last, theo, iv, delta, gamma, theta, vega, rho, volume,
                openInterest, quoteTime, fetchedAt, missingSince, mark, mark == null ? null : at, source);
    }

    /** Same quote, {@code missingSince} replaced. */
    public OptionState withMissingSince(Instant since) {
        return new OptionState(contract, occCode, bid, ask, last, theo, iv, delta, gamma, theta, vega, rho, volume,
                openInterest, quoteTime, fetchedAt, since, manualMark, manualEnteredAt, source);
    }

    public boolean quoted() {
        return quoteTime != null;
    }

    public boolean manual() {
        return manualMark != null;
    }

    /** By the stored quote's source: CBOE Q139, Yahoo Q271. */
    public QuotePrices.Mark automaticMark() {
        return quoted() ? QuotePrices.mark(source, bid, ask, last, theo) : QuotePrices.Mark.NONE;
    }

    public ContractStatus status() {
        if (!quoted()) {
            return ContractStatus.NEVER_QUOTED;
        }
        return missingSince == null ? ContractStatus.QUOTED : ContractStatus.MISSING_FROM_SOURCE;
    }
}
