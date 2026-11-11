package com.optionmgr.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Cash amount conventions (USD): every stored/returned amount has scale {@value #SCALE}.
 * Pure Java, no Spring.
 */
public final class Money {

    public static final int SCALE = 2;
    /** Inputs are validated to at most 2 decimals, so normalizing never actually rounds. */
    public static final RoundingMode ROUNDING = RoundingMode.UNNECESSARY;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    /** Unicode minus sign used in user-facing text (matches the frontend's display format). */
    public static final char MINUS = '−';

    private Money() {
    }

    /**
     * Returns {@code amount} with scale 2.
     *
     * @throws ArithmeticException if it has more than 2 significant decimals
     */
    public static BigDecimal of(BigDecimal amount) {
        return amount.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(String amount) {
        return of(new BigDecimal(amount));
    }

    /** User-facing format: thousands separators, 2 decimals, U+2212 for negatives, e.g. {@code −1,500.00}. */
    public static String display(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        String text = format.format(amount.abs());
        return amount.signum() < 0 ? MINUS + text : text;
    }
}
