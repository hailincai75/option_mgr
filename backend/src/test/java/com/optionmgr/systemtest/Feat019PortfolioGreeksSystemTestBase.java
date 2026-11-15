package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared helpers of the QA system tests for FEAT-019 组合 Greeks 汇总 (contract docs/api/FEAT-019.md).
 *
 * <p>Reuses the FEAT-016 / 017 / 018 QA bases (profiles {@code test} + {@code e2e}, random port — never 8080,
 * in-memory fake CBOE / Finnhub, settable clock, reset before / after every test). The scenario is the one of FEAT-018
 * ({@link Feat018GreeksSystemTestBase#scenarioPulled018()}): today 2026-11-23 Mon, 10:00 pull succeeds, QRTV 52.40,
 * IXQZ 61.10. Row position values (FEAT-018, verified): P1 +30.21 / −5.12 / +4.56 / −7.11; P2 +57.00 / +9.60 / −7.80 /
 * +13.00; P3 −18.35 / −3.13 / +2.47 / −5.03; P4 无报价; P5 (account B, 3 contracts) +90.63 / −15.36 / +13.68 / −21.33.
 * Account A holds 100 IXQZ shares (covering P3). FEAT-019 adds QRTV 100 shares to account B
 * ({@link #scenario019()}).
 *
 * <p>Every read of the summary ({@link #greeksSummary(String)}) asserts the contract shape and recomputes the whole
 * response independently from {@code GET /api/option-positions} (each row's {@code greeks}) and
 * {@code GET /api/stock-positions} (shares, {@code quote}) — Q181: the group values are the exact sums of the already
 * rounded row position values, exposure = net Delta × displayed underlying price HALF_UP 2, portfolio = sums of the
 * rounded group values; labels rebuilt from the counts with the contract's wording.
 */
abstract class Feat019PortfolioGreeksSystemTestBase extends Feat018GreeksSystemTestBase {

    static final String GREEKS_SUMMARY = "/api/positions/greeks-summary";

    static final List<String> TOP_FIELDS = List.of("asOfDate", "groups", "portfolio");
    static final List<String> GROUP_FIELDS = List.of("symbol", "optionDelta", "stockDelta", "netDelta", "gamma",
            "theta", "vega", "underlyingPrice", "underlyingPriceManual", "underlyingPriceLabel", "dollarExposure",
            "exposureStatus", "exposureLabel", "manualPriceNote", "optionRowCount", "includedCount", "noQuoteCount",
            "expiredPendingCount", "deltaMissingCount", "gammaMissingCount", "thetaMissingCount", "vegaMissingCount",
            "excludedLabel", "missingLabel");
    static final List<String> PORTFOLIO_FIELDS = List.of("dollarExposure", "theta", "vega", "noQuoteCount",
            "expiredPendingCount", "noUnderlyingPriceCount", "deltaMissingCount", "thetaMissingCount",
            "vegaMissingCount", "excludedLabel", "noUnderlyingPriceLabel", "missingLabel");

    static final String NO_PRICE = "无标的价";
    static final String MANUAL_PRICE = "按手动标的价";

    /** Greek key in the row's greeks object → display name used in missingLabel. */
    static final String[][] GREEK_NAMES = {{"delta", "Delta"}, {"gamma", "Gamma"}, {"theta", "Theta"},
            {"vega", "Vega"}};

    // =========================================================================================== scenario

    /**
     * FEAT-019 scenario: FEAT-018 scenario + account B holds QRTV 100 shares (转入 2026-11-19). Clock 09:50, API account
     * A, nothing pulled.
     */
    void scenario019() {
        scenario018();
        accountId = accountB;
        transferInShares("QRTV", 100, "5200.00", "2026-11-19");
        accountId = "1";
    }

    /** {@link #scenario019()} + sources + the 10:00 pull; clock left at 10:00. */
    void scenarioPulled019(ObjectNode... extraQrtvOptions) {
        scenario019();
        sources018(extraQrtvOptions);
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    // =========================================================================================== reading

    Resp greeksSummaryResp(String account) {
        String saved = accountId;
        accountId = account;
        try {
            return get(GREEKS_SUMMARY);
        } finally {
            accountId = saved;
        }
    }

    /**
     * GET the Greeks summary of {@code account}: 200, contract shape, and equal to the independent recomputation from
     * the positions endpoints (fetched right after, nothing changes in between in these tests).
     */
    JsonNode greeksSummary(String account) {
        Resp r = greeksSummaryResp(account);
        assertThat(r.status()).as("GET %s (account %s) -> %s", GREEKS_SUMMARY, account, r.body()).isEqualTo(200);
        JsonNode s = r.body();
        assertSummaryShape019(s);
        assertMatchesPositions(account, s);
        return s;
    }

    JsonNode group(JsonNode summary, String symbol) {
        for (JsonNode g : summary.get("groups")) {
            if (g.get("symbol").asText().equals(symbol)) {
                return g;
            }
        }
        throw new AssertionError("no group " + symbol + " in " + summary);
    }

    static List<String> symbols(JsonNode summary) {
        List<String> out = new ArrayList<>();
        summary.get("groups").forEach(g -> out.add(g.get("symbol").asText()));
        return out;
    }

    /**
     * What the summary table row shows (raw backend strings):
     * "optionDelta stockDelta netDelta | price priceLabel manual | exposure status exposureLabel note | gamma theta vega
     * | excludedLabel | missingLabel".
     */
    static String row(JsonNode g) {
        return text(g.get("optionDelta")) + " " + text(g.get("stockDelta")) + " " + text(g.get("netDelta"))
                + " | " + text(g.get("underlyingPrice")) + " " + text(g.get("underlyingPriceLabel")) + " "
                + g.get("underlyingPriceManual").asBoolean()
                + " | " + text(g.get("dollarExposure")) + " " + g.get("exposureStatus").asText() + " "
                + text(g.get("exposureLabel")) + " " + text(g.get("manualPriceNote"))
                + " | " + text(g.get("gamma")) + " " + text(g.get("theta")) + " " + text(g.get("vega"))
                + " | " + text(g.get("excludedLabel")) + " | " + text(g.get("missingLabel"));
    }

    /** Expected {@link #row} of an OK group with an automatic price and no notes. */
    static String okRow(String optDelta, String stockDelta, String netDelta, String price, String exposure,
                        String gamma, String theta, String vega, String excluded, String missing) {
        return optDelta + " " + stockDelta + " " + netDelta + " | " + price + " null false | " + exposure
                + " OK null null | " + gamma + " " + theta + " " + vega + " | " + excluded + " | " + missing;
    }

    /** Expected {@link #row} of an OK group with a manual underlying price. */
    static String manualRow(String optDelta, String stockDelta, String netDelta, String price, String exposure,
                            String gamma, String theta, String vega, String excluded, String missing) {
        return optDelta + " " + stockDelta + " " + netDelta + " | " + price + " null true | " + exposure
                + " OK null " + MANUAL_PRICE + " | " + gamma + " " + theta + " " + vega + " | " + excluded + " | "
                + missing;
    }

    /** "optionRowCount included/noQuote/expiredPending missing d/g/t/v" of a group. */
    static String counts(JsonNode g) {
        return g.get("optionRowCount").asInt() + " " + g.get("includedCount").asInt() + "/"
                + g.get("noQuoteCount").asInt() + "/" + g.get("expiredPendingCount").asInt() + " missing "
                + g.get("deltaMissingCount").asInt() + "/" + g.get("gammaMissingCount").asInt() + "/"
                + g.get("thetaMissingCount").asInt() + "/" + g.get("vegaMissingCount").asInt();
    }

    /** "exposure theta vega | excludedLabel | noUnderlyingPriceLabel | missingLabel" of the portfolio. */
    static String pf(JsonNode summary) {
        JsonNode p = summary.get("portfolio");
        return p.get("dollarExposure").asText() + " " + p.get("theta").asText() + " " + p.get("vega").asText()
                + " | " + text(p.get("excludedLabel")) + " | " + text(p.get("noUnderlyingPriceLabel")) + " | "
                + text(p.get("missingLabel"));
    }

    /** "noQuote/expiredPending/noUnderlyingPrice missing d/t/v" of the portfolio. */
    static String pfCounts(JsonNode summary) {
        JsonNode p = summary.get("portfolio");
        return p.get("noQuoteCount").asInt() + "/" + p.get("expiredPendingCount").asInt() + "/"
                + p.get("noUnderlyingPriceCount").asInt() + " missing " + p.get("deltaMissingCount").asInt() + "/"
                + p.get("thetaMissingCount").asInt() + "/" + p.get("vegaMissingCount").asInt();
    }

    /** Snapshot of everything numeric / textual in the summary (for "unchanged" comparisons). */
    static String all(JsonNode summary) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode g : summary.get("groups")) {
            sb.append(g.get("symbol").asText()).append(": ").append(row(g)).append(" [").append(counts(g))
                    .append("]\n");
        }
        sb.append("portfolio: ").append(pf(summary)).append(" [").append(pfCounts(summary)).append(']');
        return sb.toString();
    }

    // =========================================================================================== labels

    /** FEAT-017 wording (shared with this contract). */
    static String excludedLabel(int noQuote, int expiredPending) {
        if (noQuote == 0 && expiredPending == 0) {
            return null;
        }
        if (expiredPending == 0) {
            return noQuote + " 个持仓无报价，未计入";
        }
        if (noQuote == 0) {
            return expiredPending + " 个持仓已到期待处理，未计入";
        }
        return noQuote + " 个持仓无报价、" + expiredPending + " 个持仓已到期待处理，未计入";
    }

    /**
     * Contract: in the order Delta, Gamma, Theta, Vega, Greeks with the same missing count are merged with "、" into
     * one segment "&lt;names&gt;：&lt;n&gt; 个持仓缺值"; segments joined with "；"; then "，未计入". (Same counts are
     * merged even when not adjacent, segment order = first appearance — only adjacent cases occur in these tests.)
     */
    static String missingLabel(Map<String, Integer> countsByName) {
        Map<Integer, List<String>> segs = new LinkedHashMap<>();
        countsByName.forEach((name, c) -> {
            if (c > 0) {
                segs.computeIfAbsent(c, k -> new ArrayList<>()).add(name);
            }
        });
        if (segs.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        segs.forEach((c, names) -> parts.add(String.join("、", names) + "：" + c + " 个持仓缺值"));
        return String.join("；", parts) + "，未计入";
    }

    static String noUnderlyingPriceLabel(int n) {
        return n == 0 ? null : n + " 个标的无标的价，美元敞口未计入";
    }

    // =========================================================================================== shape

    static void assertMoney(JsonNode n, String what, boolean nullable) {
        assertThat(n).as("%s present", what).isNotNull();
        if (n.isNull()) {
            assertThat(nullable).as("%s must not be null", what).isTrue();
            return;
        }
        assertThat(n.isTextual()).as("%s is a JSON string: %s", what, n).isTrue();
        String s = n.asText();
        assertThat(s).as("%s has no '+' / ','", what).doesNotContain("+").doesNotContain(",");
        assertThat(s).as("%s no negative zero", what).isNotEqualTo("-0.00");
        assertThat(d(s).scale()).as("%s scale 2: %s", what, s).isEqualTo(2);
    }

    void assertSummaryShape019(JsonNode s) {
        assertThat(fieldNames(s)).as("top-level fields").containsExactlyInAnyOrderElementsOf(TOP_FIELDS);
        assertThat(s.get("asOfDate").isTextual()).isTrue();
        assertThat(s.get("groups").isArray()).as("groups is an array").isTrue();
        assertThat(s.get("portfolio").isObject()).as("portfolio never null").isTrue();

        List<String> syms = symbols(s);
        assertThat(syms).as("groups sorted by symbol (String.compareTo)").isSorted().doesNotHaveDuplicates();

        for (JsonNode g : s.get("groups")) {
            String sym = g.get("symbol").asText();
            assertThat(fieldNames(g)).as("group fields of %s", sym).containsExactlyInAnyOrderElementsOf(GROUP_FIELDS);
            for (String f : List.of("optionDelta", "gamma", "theta", "vega", "netDelta", "dollarExposure")) {
                assertMoney(g.get(f), sym + "." + f, true);
            }
            assertMoney(g.get("stockDelta"), sym + ".stockDelta", false);
            for (String f : List.of("optionRowCount", "includedCount", "noQuoteCount", "expiredPendingCount",
                    "deltaMissingCount", "gammaMissingCount", "thetaMissingCount", "vegaMissingCount")) {
                assertThat(g.get(f).isInt()).as("%s.%s is a number", sym, f).isTrue();
                assertThat(g.get(f).asInt()).isGreaterThanOrEqualTo(0);
            }
            assertThat(g.get("underlyingPriceManual").isBoolean()).as("%s.underlyingPriceManual boolean", sym)
                    .isTrue();
            assertThat(g.get("optionRowCount").asInt()).as("%s optionRowCount = included + noQuote + expired", sym)
                    .isEqualTo(g.get("includedCount").asInt() + g.get("noQuoteCount").asInt()
                            + g.get("expiredPendingCount").asInt());

            JsonNode price = g.get("underlyingPrice");
            if (price.isNull()) {
                assertThat(text(g.get("underlyingPriceLabel"))).isEqualTo(NO_PRICE);
            } else {
                assertThat(price.isTextual()).as("%s.underlyingPrice is a string", sym).isTrue();
                assertThat(price.asText()).doesNotContain("+");
                assertThat(d(price.asText()).scale()).as("FEAT-016 price format: ≥ 2 decimals").isGreaterThanOrEqualTo(2);
                assertThat(g.get("underlyingPriceLabel").isNull()).isTrue();
            }

            String st = g.get("exposureStatus").asText();
            assertThat(st).isIn("OK", "NO_UNDERLYING_PRICE", "NO_DELTA");
            switch (st) {
                case "NO_DELTA" -> {
                    assertThat(g.get("netDelta").isNull()).as("NO_DELTA ⇔ netDelta null (%s)", sym).isTrue();
                    assertThat(g.get("dollarExposure").isNull()).isTrue();
                    assertThat(g.get("exposureLabel").isNull()).isTrue();
                    assertThat(g.get("manualPriceNote").isNull()).isTrue();
                }
                case "NO_UNDERLYING_PRICE" -> {
                    assertThat(g.get("netDelta").isNull()).as("netDelta present (%s)", sym).isFalse();
                    assertThat(price.isNull()).isTrue();
                    assertThat(g.get("dollarExposure").isNull()).isTrue();
                    assertThat(text(g.get("exposureLabel"))).isEqualTo(NO_PRICE);
                    assertThat(g.get("manualPriceNote").isNull()).isTrue();
                }
                default -> {
                    assertThat(g.get("netDelta").isNull()).isFalse();
                    assertThat(price.isNull()).isFalse();
                    assertThat(g.get("exposureLabel").isNull()).isTrue();
                    BigDecimal exp = d(g.get("netDelta").asText()).multiply(d(price.asText()))
                            .setScale(2, RoundingMode.HALF_UP);
                    assertThat(text(g.get("dollarExposure"))).as("%s exposure = netDelta × price HALF_UP", sym)
                            .isEqualTo(exp.toPlainString());
                    assertThat(text(g.get("manualPriceNote"))).as("%s manualPriceNote", sym)
                            .isEqualTo(g.get("underlyingPriceManual").asBoolean() ? MANUAL_PRICE : null);
                }
            }
            // netDelta = optionDelta (null → 0) + stockDelta; null only when no option delta and no shares
            BigDecimal stock = d(g.get("stockDelta").asText());
            if (g.get("optionDelta").isNull() && stock.signum() == 0) {
                assertThat(g.get("netDelta").isNull()).as("%s netDelta null (no delta at all)", sym).isTrue();
            } else {
                BigDecimal opt = g.get("optionDelta").isNull() ? BigDecimal.ZERO : d(g.get("optionDelta").asText());
                assertThat(text(g.get("netDelta"))).as("%s netDelta = optionDelta + stockDelta", sym)
                        .isEqualTo(opt.add(stock).setScale(2).toPlainString());
            }
            assertThat(text(g.get("excludedLabel"))).as("%s excludedLabel", sym)
                    .isEqualTo(excludedLabel(g.get("noQuoteCount").asInt(), g.get("expiredPendingCount").asInt()));
            Map<String, Integer> miss = new LinkedHashMap<>();
            for (String[] kn : GREEK_NAMES) {
                miss.put(kn[1], g.get(kn[0] + "MissingCount").asInt());
            }
            assertThat(text(g.get("missingLabel"))).as("%s missingLabel", sym).isEqualTo(missingLabel(miss));
        }

        // portfolio
        JsonNode p = s.get("portfolio");
        assertThat(fieldNames(p)).as("portfolio fields").containsExactlyInAnyOrderElementsOf(PORTFOLIO_FIELDS);
        for (String f : List.of("dollarExposure", "theta", "vega")) {
            assertMoney(p.get(f), "portfolio." + f, false);
        }
        BigDecimal exp = BigDecimal.ZERO.setScale(2);
        BigDecimal theta = BigDecimal.ZERO.setScale(2);
        BigDecimal vega = BigDecimal.ZERO.setScale(2);
        int nq = 0;
        int ep = 0;
        int np = 0;
        int dm = 0;
        int tm = 0;
        int vm = 0;
        for (JsonNode g : s.get("groups")) {
            if (g.get("exposureStatus").asText().equals("OK")) {
                exp = exp.add(d(g.get("dollarExposure").asText()));
            }
            if (g.get("exposureStatus").asText().equals("NO_UNDERLYING_PRICE")) {
                np++;
            }
            if (!g.get("theta").isNull()) {
                theta = theta.add(d(g.get("theta").asText()));
            }
            if (!g.get("vega").isNull()) {
                vega = vega.add(d(g.get("vega").asText()));
            }
            nq += g.get("noQuoteCount").asInt();
            ep += g.get("expiredPendingCount").asInt();
            dm += g.get("deltaMissingCount").asInt();
            tm += g.get("thetaMissingCount").asInt();
            vm += g.get("vegaMissingCount").asInt();
        }
        assertThat(p.get("dollarExposure").asText()).as("portfolio exposure = Σ OK group exposures")
                .isEqualTo(exp.toPlainString());
        assertThat(p.get("theta").asText()).as("portfolio theta = Σ non-null group theta")
                .isEqualTo(theta.toPlainString());
        assertThat(p.get("vega").asText()).as("portfolio vega = Σ non-null group vega").isEqualTo(vega.toPlainString());
        assertThat(pfCounts(s)).as("portfolio counts = Σ groups")
                .isEqualTo(nq + "/" + ep + "/" + np + " missing " + dm + "/" + tm + "/" + vm);
        assertThat(text(p.get("excludedLabel"))).isEqualTo(excludedLabel(nq, ep));
        assertThat(text(p.get("noUnderlyingPriceLabel"))).isEqualTo(noUnderlyingPriceLabel(np));
        Map<String, Integer> pm = new LinkedHashMap<>();
        pm.put("Delta", dm);
        pm.put("Theta", tm);
        pm.put("Vega", vm);
        assertThat(text(p.get("missingLabel"))).as("portfolio missingLabel (Delta / Theta / Vega only)")
                .isEqualTo(missingLabel(pm));
    }

    // =========================================================================================== recomputation

    /** Expected group values recomputed from the two positions endpoints. */
    private static final class Acc {
        final BigDecimal[] sum = new BigDecimal[4];
        final int[] missing = new int[4];
        int rows;
        int ok;
        int noQuote;
        int expired;
        long shares;
        String price;
        boolean priceSeen;
        boolean manual;
    }

    /**
     * Q181 / contract consistency: groups = every symbol with an option row or a stock position of this account; group
     * Greeks = exact sums of the OK rows' (already rounded) position values from {@code GET /api/option-positions};
     * counts per status and per missing field; stockDelta = shares; underlyingPrice / manual = the rows'
     * {@code underlyingQuote} / the stock row's {@code quote}.
     */
    void assertMatchesPositions(String account, JsonNode s) {
        JsonNode opt = optionPositions(account);
        assertThat(s.get("asOfDate").asText()).as("asOfDate same as option-positions")
                .isEqualTo(opt.get("asOfDate").asText());
        Map<String, Acc> by = new TreeMap<>();
        for (JsonNode r : opt.get("positions")) {
            String sym = r.get("symbol").asText();
            Acc a = by.computeIfAbsent(sym, k -> new Acc());
            a.rows++;
            JsonNode g = greeksOf(r);
            switch (g.get("status").asText()) {
                case "OK" -> {
                    a.ok++;
                    for (int i = 0; i < 4; i++) {
                        JsonNode pos = g.get(GREEK_NAMES[i][0] + "Position");
                        if (pos.isNull()) {
                            a.missing[i]++;
                        } else {
                            a.sum[i] = (a.sum[i] == null ? BigDecimal.ZERO.setScale(2) : a.sum[i])
                                    .add(d(pos.asText()));
                        }
                    }
                }
                case "NO_QUOTE" -> a.noQuote++;
                default -> a.expired++;
            }
            notePrice(a, r.get("underlyingQuote"), sym);
        }
        for (JsonNode st : stockPositions(account).get("positions")) {
            String sym = st.get("symbol").asText();
            Acc a = by.computeIfAbsent(sym, k -> new Acc());
            a.shares += st.get("shares").asLong();
            notePrice(a, st.get("quote"), sym);
        }
        assertThat(symbols(s)).as("groups = symbols with option or stock positions of account %s", account)
                .containsExactlyElementsOf(by.keySet());
        for (Map.Entry<String, Acc> e : by.entrySet()) {
            String sym = e.getKey();
            Acc a = e.getValue();
            JsonNode g = group(s, sym);
            String[] keys = {"optionDelta", "gamma", "theta", "vega"};
            for (int i = 0; i < 4; i++) {
                assertThat(text(g.get(keys[i]))).as("%s.%s = Σ OK rows' %sPosition (null if none)", sym, keys[i],
                        GREEK_NAMES[i][0]).isEqualTo(a.sum[i] == null ? null : a.sum[i].toPlainString());
            }
            assertThat(text(g.get("stockDelta"))).as("%s.stockDelta = shares", sym)
                    .isEqualTo(BigDecimal.valueOf(a.shares).setScale(2).toPlainString());
            assertThat(text(g.get("underlyingPrice"))).as("%s.underlyingPrice = displayed underlying price", sym)
                    .isEqualTo(a.price);
            assertThat(g.get("underlyingPriceManual").asBoolean()).as("%s.underlyingPriceManual", sym)
                    .isEqualTo(a.manual);
            assertThat(counts(g)).as("%s counts", sym).isEqualTo(a.rows + " " + a.ok + "/" + a.noQuote + "/"
                    + a.expired + " missing " + a.missing[0] + "/" + a.missing[1] + "/" + a.missing[2] + "/"
                    + a.missing[3]);
            String expectedStatus = g.get("netDelta").isNull() ? "NO_DELTA"
                    : a.price == null ? "NO_UNDERLYING_PRICE" : "OK";
            assertThat(g.get("exposureStatus").asText()).as("%s exposureStatus (NO_DELTA before price)", sym)
                    .isEqualTo(expectedStatus);
        }
    }

    private static void notePrice(Acc a, JsonNode quote, String sym) {
        String p = text(quote.get("price"));
        boolean m = quote.get("manual").asBoolean();
        if (a.priceSeen) {
            assertThat(p).as("%s same underlying price on every row", sym).isEqualTo(a.price);
            assertThat(m).isEqualTo(a.manual);
        }
        a.priceSeen = true;
        a.price = p;
        a.manual = m;
    }

    /** Parses the contract's example JSON (for whole-response equality). */
    JsonNode parse(String jsonText) {
        try {
            return json.readTree(jsonText);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
