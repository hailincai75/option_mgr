package com.optionmgr.quote.domain;

import java.math.BigDecimal;

/** FEAT-016: one parsed entry of {@code data.options[]} (fields null when missing). */
public record CboeOption(OccCode code, BigDecimal bid, BigDecimal ask, BigDecimal last, BigDecimal theo,
                         BigDecimal iv, BigDecimal delta, BigDecimal gamma, BigDecimal theta, BigDecimal vega,
                         BigDecimal rho, Long volume, Long openInterest) {
}
