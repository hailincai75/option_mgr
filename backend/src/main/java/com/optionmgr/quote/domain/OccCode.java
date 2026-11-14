package com.optionmgr.quote.domain;

import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * FEAT-016: an option code from the CBOE chain. The last 15 characters are YYMMDD + C/P + strike x 1000 (8 digits);
 * everything before them is the root, which need not equal the underlying (SPY chains also list {@code B1SPY...},
 * SPX lists SPX / SPXW / SPXO). Pure.
 */
public record OccCode(String code, String root, LocalDate expiration, OptionType optionType, BigDecimal strike) {

    private static final int TAIL = 15;

    public static Optional<OccCode> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.strip();
        if (c.length() <= TAIL) {
            return Optional.empty();
        }
        String root = c.substring(0, c.length() - TAIL);
        String tail = c.substring(c.length() - TAIL);
        String date = tail.substring(0, 6);
        char cp = tail.charAt(6);
        String strike = tail.substring(7);
        if (!date.chars().allMatch(ch -> ch >= '0' && ch <= '9') || !strike.chars().allMatch(ch -> ch >= '0' && ch <= '9')
                || (cp != 'C' && cp != 'P')) {
            return Optional.empty();
        }
        LocalDate expiration;
        try {
            expiration = LocalDate.of(2000 + Integer.parseInt(date.substring(0, 2)),
                    Integer.parseInt(date.substring(2, 4)), Integer.parseInt(date.substring(4, 6)));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return Optional.of(new OccCode(c, root, expiration, cp == 'C' ? OptionType.CALL : OptionType.PUT,
                new BigDecimal(strike).movePointLeft(3)));
    }

    /** The root equals the underlying symbol or ends with it ({@code B1SPY} for {@code SPY}). */
    public boolean rootMatches(String symbol) {
        return root.equals(symbol) || root.endsWith(symbol);
    }
}
