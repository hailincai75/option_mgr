package com.optionmgr.quote.domain;

import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * FEAT-052 rule 5 (Q262-Q264): Black-Scholes Greeks of a European option (approximation for US equity options), no
 * dividends (q = 0), a fixed continuously compounded rate r. Stored units are those of FEAT-018 (per share, long
 * side): delta, gamma, theta per calendar day (annual / 365), vega per 1 IV percentage point (annual / 100).
 *
 * <p>Inputs and outputs are {@link BigDecimal}; only the transcendental math (ln, exp, sqrt, the normal CDF) runs in
 * {@code double}. Greeks are analytics, not money or prices: this is the documented exception to the project's "no
 * double" rule (docs/architecture.md). Each result is rounded HALF_UP to 8 decimals (the DECIMAL(19, 8) columns).
 * Pure.
 */
public final class BlackScholes {

    /** The four stored Greeks; a field is null when it cannot be computed. */
    public record Greeks(BigDecimal delta, BigDecimal gamma, BigDecimal theta, BigDecimal vega) {
        public static final Greeks NONE = new Greeks(null, null, null, null);
    }

    public static final int SCALE = 8;
    /** Options stop trading at 16:00 New York on the expiration day. */
    public static final LocalTime EXPIRY_TIME = LocalTime.of(16, 0);
    private static final double SECONDS_PER_YEAR = 365.0 * 86400.0;
    private static final double INV_SQRT_2PI = 0.3989422804014327;

    private BlackScholes() {
    }

    /**
     * T in years: (expiration day 16:00 New York - quote time) in real seconds / (365 x 86400). Daylight saving is
     * taken into account by the zone rules.
     */
    public static double yearsToExpiry(Instant quoteTime, LocalDate expiration) {
        Instant expiry = MarketTime.toInstant(expiration.atTime(EXPIRY_TIME));
        return Duration.between(quoteTime, expiry).getSeconds() / SECONDS_PER_YEAR;
    }

    /**
     * Greeks of a contract.
     *
     * @param s          underlying price (same response)
     * @param k          strike
     * @param sigma      implied volatility as a decimal (0.30 = 30 %)
     * @param r          risk-free rate as a decimal (0.04 = 4 %)
     * @param quoteTime  quote time of the response
     * @param expiration expiration date
     * @param call       call (else put)
     * @return all null when sigma, s, k or T is missing or &le; 0; otherwise each non-finite result alone is null
     */
    public static Greeks greeks(BigDecimal s, BigDecimal k, BigDecimal sigma, BigDecimal r, Instant quoteTime,
                                LocalDate expiration, boolean call) {
        if (!QuotePrices.positive(s) || !QuotePrices.positive(k) || !QuotePrices.positive(sigma) || r == null
                || quoteTime == null || expiration == null) {
            return Greeks.NONE;
        }
        double t = yearsToExpiry(quoteTime, expiration);
        if (!(t > 0)) {
            return Greeks.NONE;
        }
        return greeks(s.doubleValue(), k.doubleValue(), sigma.doubleValue(), r.doubleValue(), t, call);
    }

    static Greeks greeks(double s, double k, double sigma, double r, double t, boolean call) {
        double sqrtT = Math.sqrt(t);
        double sigmaSqrtT = sigma * sqrtT;
        double d1 = (Math.log(s / k) + (r + sigma * sigma / 2) * t) / sigmaSqrtT;
        double d2 = d1 - sigmaSqrtT;
        double pdf = INV_SQRT_2PI * Math.exp(-d1 * d1 / 2);
        double discountedStrike = r * k * Math.exp(-r * t);
        double delta = call ? cdf(d1) : cdf(d1) - 1;
        double gamma = pdf / (s * sigmaSqrtT);
        double decay = -s * pdf * sigma / (2 * sqrtT);
        double thetaYear = call ? decay - discountedStrike * cdf(d2) : decay + discountedStrike * cdf(-d2);
        double vegaYear = s * pdf * sqrtT;
        return new Greeks(round(delta), round(gamma), round(thetaYear / 365), round(vegaYear / 100));
    }

    private static BigDecimal round(double x) {
        return Double.isFinite(x) ? BigDecimal.valueOf(x).setScale(SCALE, RoundingMode.HALF_UP) : null;
    }

    /**
     * Standard normal CDF, Hart (1968) double-precision algorithm as given by G. West, "Better approximations to
     * cumulative normal functions" (2005); absolute error below 1e-14 (verified against a high-precision series in
     * BlackScholesTest).
     */
    public static double cdf(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        double z = Math.abs(x);
        double tail;
        if (z > 37) {
            tail = 0;
        } else {
            double e = Math.exp(-z * z / 2);
            if (z < 7.07106781186547) {
                double n = 3.52624965998911E-02 * z + 0.700383064443688;
                n = n * z + 6.37396220353165;
                n = n * z + 33.912866078383;
                n = n * z + 112.079291497871;
                n = n * z + 221.213596169931;
                n = n * z + 220.206867912376;
                double d = 8.83883476483184E-02 * z + 1.75566716318264;
                d = d * z + 16.064177579207;
                d = d * z + 86.7807322029461;
                d = d * z + 296.564248779674;
                d = d * z + 637.333633378831;
                d = d * z + 793.826512519948;
                d = d * z + 440.413735824752;
                tail = e * n / d;
            } else {
                double b = z + 0.65;
                b = z + 4 / b;
                b = z + 3 / b;
                b = z + 2 / b;
                b = z + 1 / b;
                tail = e / b / 2.506628274631;
            }
        }
        return x > 0 ? 1 - tail : tail;
    }
}
