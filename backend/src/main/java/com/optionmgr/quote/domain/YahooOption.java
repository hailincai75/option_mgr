package com.optionmgr.quote.domain;

import java.math.BigDecimal;

/**
 * FEAT-052: one parsed entry of {@code options[0].calls[]} / {@code puts[]} of a Yahoo options response (fields null
 * when missing or malformed). {@code iv} is a decimal (0.3 = 30 %).
 */
public record YahooOption(OccCode code, BigDecimal bid, BigDecimal ask, BigDecimal last, BigDecimal iv, Long volume,
                          Long openInterest) {
}
