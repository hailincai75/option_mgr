package com.optionmgr.valuation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * FEAT-019 Greeks per underlying and for the whole account (Q174–Q181). Sums the already rounded FEAT-018 position
 * values of the OK rows (Q181), adds the stock delta (1 per share), and values the net delta at the displayed
 * underlying price. Pure Java; all texts are built here.
 */
public record GreeksSummary(List<Group> groups, Portfolio portfolio) {

    static final int SCALE = 2;
    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
    static final String NO_UNDERLYING_PRICE = "无标的价";
    static final String MANUAL_PRICE_NOTE = "按手动标的价";

    /** One option position row of the account: its symbol and its FEAT-018 Greeks. */
    public record OptionRow(String symbol, RowGreeks greeks) {
    }

    /** The displayed underlying price ({@code null} = none) and whether it is a manual override. */
    public record Underlying(BigDecimal price, boolean manual) {
        public static final Underlying NONE = new Underlying(null, false);
    }

    public enum ExposureStatus {
        OK, NO_UNDERLYING_PRICE, NO_DELTA;

        public String label() {
            return this == NO_UNDERLYING_PRICE ? GreeksSummary.NO_UNDERLYING_PRICE : null;
        }
    }

    /** How many included rows lack each Greek's position value (Q172). */
    public record Missing(int delta, int gamma, int theta, int vega) {
        static final Missing NONE = new Missing(0, 0, 0, 0);

        Missing plus(Missing o) {
            return new Missing(delta + o.delta, gamma + o.gamma, theta + o.theta, vega + o.vega);
        }
    }

    public record Group(String symbol, BigDecimal optionDelta, BigDecimal stockDelta, BigDecimal netDelta,
                        BigDecimal gamma, BigDecimal theta, BigDecimal vega, BigDecimal underlyingPrice,
                        boolean underlyingPriceManual, String underlyingPriceLabel, BigDecimal dollarExposure,
                        ExposureStatus exposureStatus, String exposureLabel, String manualPriceNote,
                        int optionRowCount, int includedCount, int noQuoteCount, int expiredPendingCount,
                        Missing missing, String excludedLabel, String missingLabel) {
    }

    public record Portfolio(BigDecimal dollarExposure, BigDecimal theta, BigDecimal vega, int noQuoteCount,
                            int expiredPendingCount, int noUnderlyingPriceCount, Missing missing,
                            String excludedLabel, String noUnderlyingPriceLabel, String missingLabel) {
    }

    /**
     * @param options    all option position rows of the account (excluded ones too)
     * @param shares     current shares per symbol (stock positions)
     * @param underlying displayed underlying price per symbol
     */
    public static GreeksSummary of(List<OptionRow> options, Map<String, Long> shares,
                                   Function<String, Underlying> underlying) {
        Map<String, List<RowGreeks>> bySymbol = new TreeMap<>();
        for (OptionRow r : options) {
            bySymbol.computeIfAbsent(r.symbol(), s -> new ArrayList<>()).add(r.greeks());
        }
        TreeSet<String> symbols = new TreeSet<>(bySymbol.keySet());
        shares.forEach((s, n) -> {
            if (n != null && n != 0) {
                symbols.add(s);
            }
        });

        List<Group> groups = new ArrayList<>();
        BigDecimal exposure = ZERO;
        BigDecimal theta = ZERO;
        BigDecimal vega = ZERO;
        int noQuote = 0;
        int expired = 0;
        int noPrice = 0;
        Missing missing = Missing.NONE;
        for (String symbol : symbols) {
            Underlying u = underlying.apply(symbol);
            Group g = group(symbol, bySymbol.getOrDefault(symbol, List.of()), shares.getOrDefault(symbol, 0L),
                    u == null ? Underlying.NONE : u);
            groups.add(g);
            if (g.exposureStatus() == ExposureStatus.OK) {
                exposure = exposure.add(g.dollarExposure());
            } else if (g.exposureStatus() == ExposureStatus.NO_UNDERLYING_PRICE) {
                noPrice++;
            }
            if (g.theta() != null) {
                theta = theta.add(g.theta());
            }
            if (g.vega() != null) {
                vega = vega.add(g.vega());
            }
            noQuote += g.noQuoteCount();
            expired += g.expiredPendingCount();
            missing = missing.plus(g.missing());
        }
        Missing portfolioMissing = new Missing(missing.delta(), 0, missing.theta(), missing.vega());
        Portfolio p = new Portfolio(exposure, theta, vega, noQuote, expired, noPrice, portfolioMissing,
                UnrealizedSummary.excludedLabel(noQuote, expired),
                noPrice == 0 ? null : noPrice + " 个标的无标的价，美元敞口未计入",
                missingLabel(portfolioMissing));
        return new GreeksSummary(List.copyOf(groups), p);
    }

    static Group group(String symbol, List<RowGreeks> rows, long shareCount, Underlying u) {
        BigDecimal delta = null;
        BigDecimal gamma = null;
        BigDecimal theta = null;
        BigDecimal vega = null;
        int included = 0;
        int noQuote = 0;
        int expired = 0;
        int md = 0;
        int mg = 0;
        int mt = 0;
        int mv = 0;
        for (RowGreeks r : rows) {
            switch (r.status()) {
                case NO_QUOTE -> noQuote++;
                case EXPIRED_PENDING -> expired++;
                case OK -> {
                    included++;
                    BigDecimal d = position(r.delta());
                    BigDecimal ga = position(r.gamma());
                    BigDecimal t = position(r.theta());
                    BigDecimal v = position(r.vega());
                    if (d == null) {
                        md++;
                    } else {
                        delta = add(delta, d);
                    }
                    if (ga == null) {
                        mg++;
                    } else {
                        gamma = add(gamma, ga);
                    }
                    if (t == null) {
                        mt++;
                    } else {
                        theta = add(theta, t);
                    }
                    if (v == null) {
                        mv++;
                    } else {
                        vega = add(vega, v);
                    }
                }
            }
        }
        BigDecimal stockDelta = BigDecimal.valueOf(shareCount).setScale(SCALE, RoundingMode.UNNECESSARY);
        BigDecimal net = delta == null && shareCount == 0 ? null : (delta == null ? ZERO : delta).add(stockDelta);

        ExposureStatus status;
        BigDecimal exposure = null;
        if (net == null) {
            status = ExposureStatus.NO_DELTA;
        } else if (u.price() == null) {
            status = ExposureStatus.NO_UNDERLYING_PRICE;
        } else {
            status = ExposureStatus.OK;
            exposure = net.multiply(u.price()).setScale(SCALE, RoundingMode.HALF_UP);
        }
        Missing missing = new Missing(md, mg, mt, mv);
        return new Group(symbol, delta, stockDelta, net, gamma, theta, vega, u.price(), u.manual(),
                u.price() == null ? NO_UNDERLYING_PRICE : null, exposure, status, status.label(),
                status == ExposureStatus.OK && u.manual() ? MANUAL_PRICE_NOTE : null,
                rows.size(), included, noQuote, expired, missing,
                UnrealizedSummary.excludedLabel(noQuote, expired), missingLabel(missing));
    }

    private static BigDecimal position(RowGreeks.Greek g) {
        return g == null ? null : g.position();
    }

    private static BigDecimal add(BigDecimal sum, BigDecimal v) {
        return sum == null ? v : sum.add(v);
    }

    /**
     * {@code Gamma、Vega：1 个持仓缺值；Theta：2 个持仓缺值，未计入}: Greeks in Delta, Gamma, Theta, Vega order, those with
     * the same count merged, segments in order of their first Greek; {@code null} when nothing is missing.
     */
    static String missingLabel(Missing m) {
        Map<Integer, List<String>> byCount = new LinkedHashMap<>();
        String[] names = {"Delta", "Gamma", "Theta", "Vega"};
        int[] counts = {m.delta(), m.gamma(), m.theta(), m.vega()};
        for (int i = 0; i < names.length; i++) {
            if (counts[i] > 0) {
                byCount.computeIfAbsent(counts[i], c -> new ArrayList<>()).add(names[i]);
            }
        }
        if (byCount.isEmpty()) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        byCount.forEach((count, list) -> segments.add(String.join("、", list) + "：" + count + " 个持仓缺值"));
        return String.join("；", segments) + "，未计入";
    }
}
