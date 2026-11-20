package com.optionmgr.valuation.domain;

import com.optionmgr.quote.domain.QuoteLabels;
import com.optionmgr.quote.domain.QuoteSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * FEAT-018 per-row IV / Greeks from the stored CBOE values (pure Java). CBOE gives per-share, long-side values: iv as
 * a decimal, theta per calendar day, vega per 1 IV percentage point (verified 2026-11-14). Rules: Q163 (position
 * value = per contract x contracts x multiplier x direction), Q164 (per-contract value not flipped), Q166 (per
 * contract 4 decimals, position 2, IV percent 2, HALF_UP, position from the unrounded value), Q170 (manual mark:
 * CBOE values with a note; never quoted = no quote), Q171 (expired, unhandled = not shown; the expiration day is),
 * Q172 (a missing field only blanks itself).
 */
public final class GreeksValuation {

    static final int PER_CONTRACT_SCALE = 4;
    static final int POSITION_SCALE = 2;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private GreeksValuation() {
    }

    /**
     * The stored CBOE quote of a contract as the row sees it.
     *
     * @param quoted    the contract had a CBOE quote (FEAT-016 contract status is not NEVER_QUOTED)
     * @param manual    the contract mark is manually overridden
     * @param quoteTime CBOE quote time of the stored values
     */
    public record Quote(boolean quoted, boolean manual, Instant quoteTime, BigDecimal iv, BigDecimal delta,
                        BigDecimal gamma, BigDecimal theta, BigDecimal vega, QuoteSource source) {

        public static final Quote NONE = new Quote(false, false, null, null, null, null, null, null, null);

        /** FEAT-018 form: a quoted contract's values are CBOE values. */
        public Quote(boolean quoted, boolean manual, Instant quoteTime, BigDecimal iv, BigDecimal delta,
                     BigDecimal gamma, BigDecimal theta, BigDecimal vega) {
            this(quoted, manual, quoteTime, iv, delta, gamma, theta, vega, quoted ? QuoteSource.CBOE : null);
        }
    }

    public static RowGreeks option(boolean longPosition, int contracts, int multiplier, LocalDate expirationDate,
                                   LocalDate today, Quote quote) {
        Quote q = quote == null ? Quote.NONE : quote;
        if (expirationDate.isBefore(today)) {
            return unavailable(GreeksStatus.EXPIRED_PENDING, q.quoteTime(), q.quoted() ? q.source() : null);
        }
        if (!q.quoted()) {
            return unavailable(GreeksStatus.NO_QUOTE, q.quoteTime(), null);
        }
        BigDecimal size = BigDecimal.valueOf((long) contracts * multiplier);
        if (!longPosition) {
            size = size.negate();
        }
        BigDecimal iv = q.iv() == null ? null : q.iv().movePointRight(2).setScale(POSITION_SCALE, ROUNDING);
        // FEAT-052: the note names how the Greeks were obtained (Yahoo: computed by the backend)
        String note = q.manual() && q.quoteTime() != null
                ? (q.source() == QuoteSource.YAHOO ? "Greeks 按 Yahoo 报价计算（" : "Greeks 按 CBOE 报价（")
                        + QuoteLabels.shortTime(q.quoteTime()) + "），未随手动 Mark 调整"
                : null;
        return new RowGreeks(GreeksStatus.OK, iv, greek(q.delta(), size), greek(q.gamma(), size),
                greek(q.theta(), size), greek(q.vega(), size), q.quoteTime(), note, q.source());
    }

    static RowGreeks.Greek greek(BigDecimal raw, BigDecimal size) {
        if (raw == null) {
            return RowGreeks.Greek.NONE;
        }
        return new RowGreeks.Greek(raw.setScale(PER_CONTRACT_SCALE, ROUNDING),
                raw.multiply(size).setScale(POSITION_SCALE, ROUNDING));
    }

    private static RowGreeks unavailable(GreeksStatus status, Instant quoteTime, QuoteSource source) {
        return new RowGreeks(status, null, RowGreeks.Greek.NONE, RowGreeks.Greek.NONE, RowGreeks.Greek.NONE,
                RowGreeks.Greek.NONE, quoteTime, null, source);
    }
}
