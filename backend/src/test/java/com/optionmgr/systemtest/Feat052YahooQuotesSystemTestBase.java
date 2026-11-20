package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpMethod;

/**
 * Shared helpers of the QA system tests for FEAT-052 行情数据源改用 Yahoo Finance (contract docs/api/FEAT-052.md).
 *
 * <p>Reuses the FEAT-016 … FEAT-019 QA bases (profiles {@code test} + {@code e2e}, random port — never 8080,
 * in-memory fake sources, settable clock, {@code POST /api/test-support/reset} before / after every test). The
 * {@code test} / {@code e2e} profiles default to {@code option-source=cboe} and {@code r = 0.00}; every FEAT-052 test
 * switches to Yahoo explicitly through {@code PUT /api/test-support/market-data/settings} (here in {@link #useYahoo()},
 * run before each test). Fake Yahoo responses are shaped like the real {@code /v7/finance/options/{SYMBOL}} JSON of
 * the requirement's 可行性探测结果 ({@code optionChain.result[0]} with {@code underlyingSymbol}, {@code expirationDates},
 * {@code strikes}, {@code quote}, {@code options[0].calls / puts}; Unix seconds; IV as a fraction; no Greeks). The real
 * Yahoo / CBOE / Finnhub are never called.
 *
 * <p>Requirement scenario (today 2026-11-20 Fri, EST = UTC-5, r = 0.00 %, multiplier 100; fictional tickers):
 * account A (default account 1): P1 STO 1 QRTV 2027-02-19 50P (net 380.00), P2 STO 1 QRTV 2026-12-18 45P (net 70.00).
 * Account B: P3 BTO 2 QRTV 2027-02-19 50C (net 800.00); 100 IXQZ shares (转入, cost 6000.00), no IXQZ options. Fake
 * Yahoo: QRTV 50.00 at 11-20 10:00; 2027-02-19 chain 50P 3.90 / 4.10 / last 3.95 / IV 0.3000, 50C 4.00 / 4.20 / IV
 * 0.3000; 2026-12-18 chain 45P 0.60 / 0.70 / IV 0.3500; IXQZ 61.10. First pull at 10:00.
 */
abstract class Feat052YahooQuotesSystemTestBase extends Feat019PortfolioGreeksSystemTestBase {

    static final String SETTINGS = "/api/test-support/market-data/settings";

    static final LocalDate TODAY = LocalDate.parse("2026-11-20");
    static final String FEB = "2027-02-19";
    static final String DEC = "2026-12-18";

    static final String Y_P1 = "QRTV 2027-02-19 50 P";
    static final String Y_P2 = "QRTV 2026-12-18 45 P";
    static final String Y_P3 = "QRTV 2027-02-19 50 C";
    static final String Y_P4 = "QRTV 2027-02-19 45 P";

    static final String OCC_Y_P1 = "QRTV270219P00050000";
    static final String OCC_Y_P2 = "QRTV261218P00045000";
    static final String OCC_Y_P3 = "QRTV270219C00050000";
    static final String OCC_Y_P4 = "QRTV270219P00045000";
    /** Not held: present in the chain, must not be stored. */
    static final String OCC_Y_55C = "QRTV270219C00055000";
    static final String OCC_Y_55P = "QRTV270219P00055000";

    static final String CRUMB = "AbCdEfGhIjK";

    /** Requirement AC 1 / 3 / 4 (r = 0, T = 0.25): shown Greeks of P1 / P3 and of P2 (contract table). */
    static final String Y_P1_G = okG("30.00", "47.01", "-0.4701", "-5.30", "0.0530", "1.63", "-0.0163", "-9.95",
            "0.0995");
    static final String Y_P3_G = okG("30.00", "105.98", "0.5299", "10.61", "0.0530", "-3.27", "-0.0163", "19.89",
            "0.0995");
    static final String Y_P2_G = okG("35.00", "12.91", "-0.1291", "-4.32", "0.0432", "1.81", "-0.0181", "-2.93",
            "0.0293");

    // =========================================================================================== settings

    @BeforeEach
    void useYahoo() {
        JsonNode s = settings("YAHOO", "0.00");
        assertThat(s.get("optionSource").asText()).isEqualTo("YAHOO");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("0.00");
    }

    Resp settingsResp(String rawBody) {
        return noAccount(() -> exchange(HttpMethod.PUT, SETTINGS, rawBody));
    }

    /** PUT settings ({@code null} = JSON null = back to the configuration value); asserts 200 and returns the body. */
    JsonNode settings(String optionSource, String ratePercent) {
        ObjectNode n = json.createObjectNode();
        if (optionSource == null) {
            n.putNull("optionSource");
        } else {
            n.put("optionSource", optionSource);
        }
        if (ratePercent == null) {
            n.putNull("riskFreeRatePercent");
        } else {
            n.put("riskFreeRatePercent", ratePercent);
        }
        Resp r = settingsResp(n.toString());
        assertThat(r.status()).as("PUT %s %s -> %s", SETTINGS, n, r.body()).isEqualTo(200);
        assertThat(fieldNames(r.body())).containsExactlyInAnyOrder("optionSource", "riskFreeRatePercent");
        return r.body();
    }

    // =========================================================================================== time

    /** Yahoo / Unix seconds of a New York local time. */
    static long epochNy(String nyLocal) {
        return ny(nyLocal).getEpochSecond();
    }

    /** The {@code date} / {@code expirationDates} value of an expiration: 00:00 UTC of that day (contract). */
    static long dateSec(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    // =========================================================================================== fake Yahoo bodies

    /**
     * One Yahoo option contract, real field set ({@code contractSymbol, strike, currency, lastPrice, change,
     * percentChange, volume, openInterest, bid, ask, contractSize, expiration, lastTradeDate, impliedVolatility,
     * inTheMoney}); a {@code null} bid / ask / last / iv is omitted (= missing). Strike and expiration are derived from
     * the OCC code.
     */
    ObjectNode yc(String occ, String bid, String ask, String last, String iv) {
        String tail = occ.substring(occ.length() - 15);
        LocalDate exp = LocalDate.parse("20" + tail.substring(0, 2) + "-" + tail.substring(2, 4) + "-"
                + tail.substring(4, 6));
        BigDecimal strike = new BigDecimal(tail.substring(7)).movePointLeft(3);
        ObjectNode c = json.createObjectNode();
        c.put("contractSymbol", occ);
        c.put("strike", strike);
        c.put("currency", "USD");
        if (last != null) {
            c.put("lastPrice", d(last));
        }
        c.put("change", d("0.0"));
        c.put("percentChange", d("0.0"));
        c.put("volume", 12);
        c.put("openInterest", 340);
        if (bid != null) {
            c.put("bid", d(bid));
        }
        if (ask != null) {
            c.put("ask", d(ask));
        }
        c.put("contractSize", "REGULAR");
        c.put("expiration", dateSec(exp.toString()));
        c.put("lastTradeDate", epochNy("2026-11-19T15:58"));
        if (iv != null) {
            c.put("impliedVolatility", d(iv));
        }
        c.put("inTheMoney", false);
        return c;
    }

    /** The {@code quote} object of a Yahoo response ({@code price} null = omitted). */
    ObjectNode yQuote(String symbol, String price, String nyTime) {
        ObjectNode q = json.createObjectNode();
        q.put("language", "en-US");
        q.put("region", "US");
        q.put("quoteType", "EQUITY");
        q.put("currency", "USD");
        q.put("marketState", "REGULAR");
        q.put("symbol", symbol);
        if (price != null) {
            q.put("regularMarketPrice", d(price));
            if (d(price).signum() > 0) {
                q.put("bid", d(price).subtract(d("0.02")));
                q.put("ask", d(price).add(d("0.02")));
            }
        }
        q.put("regularMarketPreviousClose", d("49.60"));
        if (nyTime != null) {
            q.put("regularMarketTime", epochNy(nyTime));
        }
        return q;
    }

    /**
     * A whole Yahoo body {@code {optionChain:{result:[{…}], error:null}}} for one expiration ({@code chainDate}; null =
     * no options block), listing {@code expirationDates}; the contracts are split into calls / puts by their OCC code.
     */
    ObjectNode yBody(String symbol, String price, String nyTime, List<String> expirationDates, String chainDate,
                     List<ObjectNode> contracts) {
        ObjectNode body = json.createObjectNode();
        ObjectNode chain = body.putObject("optionChain");
        ArrayNode result = chain.putArray("result");
        chain.putNull("error");
        ObjectNode r = result.addObject();
        r.put("underlyingSymbol", symbol);
        ArrayNode exps = r.putArray("expirationDates");
        expirationDates.forEach(e -> exps.add(dateSec(e)));
        ArrayNode strikes = r.putArray("strikes");
        r.put("hasMiniOptions", false);
        r.set("quote", yQuote(symbol, price, nyTime));
        ArrayNode options = r.putArray("options");
        if (chainDate != null) {
            ObjectNode o = options.addObject();
            o.put("expirationDate", dateSec(chainDate));
            o.put("hasMiniOptions", false);
            ArrayNode calls = o.putArray("calls");
            ArrayNode puts = o.putArray("puts");
            for (ObjectNode c : contracts) {
                String occ = c.get("contractSymbol").asText();
                (occ.charAt(occ.length() - 9) == 'C' ? calls : puts).add(c);
                strikes.add(c.get("strike"));
            }
        }
        return body;
    }

    /** Scenario expiration list of QRTV (the Yahoo list includes all listed expirations). */
    static final List<String> QRTV_EXPS = List.of("2026-11-27", DEC, "2027-01-15", FEB);

    ObjectNode qrtvFeb(String price, String nyTime, ObjectNode... contracts) {
        return yBody("QRTV", price, nyTime, QRTV_EXPS, FEB, List.of(contracts));
    }

    ObjectNode qrtvDec(String price, String nyTime, ObjectNode... contracts) {
        return yBody("QRTV", price, nyTime, QRTV_EXPS, DEC, List.of(contracts));
    }

    /** Stock-only symbol: Yahoo still attaches the nearest chain (ignored by the backend). */
    ObjectNode ixqzBody(String price, String nyTime) {
        return yBody("IXQZ", price, nyTime, List.of("2026-11-27", DEC), "2026-11-27",
                List.of(yc("IXQZ261127C00060000", "1.50", "1.60", "1.55", "0.2800")));
    }

    // scenario contracts at 10:00
    ObjectNode p1Y() {
        return yc(OCC_Y_P1, "3.90", "4.10", "3.95", "0.3000");
    }

    ObjectNode p3Y() {
        return yc(OCC_Y_P3, "4.00", "4.20", "4.10", "0.3000");
    }

    ObjectNode p2Y() {
        return yc(OCC_Y_P2, "0.60", "0.70", "0.62", "0.3500");
    }

    /** The 10:00 2027-02-19 chain (P1, P3 + two contracts nobody holds). */
    ObjectNode febOk() {
        return ok(qrtvFeb("50.00", "2026-11-20T10:00", p1Y(), p3Y(), yc(OCC_Y_55C, "1.80", "1.90", "1.85", "0.2800"),
                yc(OCC_Y_55P, "6.10", "6.30", "6.20", "0.3100")));
    }

    ObjectNode decOk() {
        return ok(qrtvDec("50.00", "2026-11-20T10:00", p2Y(), yc("QRTV261218P00040000", "0.10", "0.15", "0.12",
                "0.4000")));
    }

    ObjectNode ixqzOkY() {
        return ok(ixqzBody("61.10", "2026-11-20T10:00"));
    }

    /** A replies array (one reply per request, the last one repeats). */
    ArrayNode seq(ObjectNode... replies) {
        ArrayNode a = json.createArrayNode();
        for (ObjectNode r : replies) {
            a.add(r);
        }
        return a;
    }

    ObjectNode crumbOk() {
        return rawBody(CRUMB);
    }

    /** Fluent builder of the whole PUT sources body incl. the FEAT-052 {@code yahoo} block. */
    final class YSources {
        boolean keyPresent = true;
        JsonNode handshake;
        final Map<String, ObjectNode> cboe = new LinkedHashMap<>();
        final Map<String, ObjectNode> finnhub = new LinkedHashMap<>();
        final Map<String, ObjectNode> symbols = new LinkedHashMap<>();

        YSources handshake(JsonNode reply) {
            handshake = reply;
            return this;
        }

        YSources date(String symbol, String isoDate, JsonNode reply) {
            ObjectNode s = symbols.computeIfAbsent(symbol, k -> json.createObjectNode());
            ObjectNode byDate = s.has("byDate") ? (ObjectNode) s.get("byDate") : s.putObject("byDate");
            byDate.set(isoDate, reply);
            return this;
        }

        YSources dflt(String symbol, JsonNode reply) {
            symbols.computeIfAbsent(symbol, k -> json.createObjectNode()).set("default", reply);
            return this;
        }

        YSources cboe(String symbol, ObjectNode reply) {
            cboe.put(symbol, reply);
            return this;
        }

        YSources finnhub(String symbol, ObjectNode reply) {
            finnhub.put(symbol, reply);
            return this;
        }

        YSources noKey() {
            keyPresent = false;
            return this;
        }

        void apply() {
            ObjectNode n = json.createObjectNode();
            n.put("finnhubKeyPresent", keyPresent);
            ObjectNode c = n.putObject("cboe");
            cboe.forEach(c::set);
            ObjectNode f = n.putObject("finnhub");
            finnhub.forEach(f::set);
            ObjectNode y = n.putObject("yahoo");
            if (handshake != null) {
                y.set("handshake", handshake);
            }
            ObjectNode s = y.putObject("symbols");
            symbols.forEach(s::set);
            Resp r = noAccount(() -> exchange(HttpMethod.PUT, SOURCES, n.toString()));
            assertThat(r.status()).as("PUT %s -> %s", SOURCES, r.body()).isEqualTo(204);
        }
    }

    YSources ysources() {
        return new YSources();
    }

    /** The scenario's fake Yahoo (handshake OK, both QRTV dates and IXQZ OK; no Finnhub entries). */
    YSources scenarioYahoo() {
        return ysources().handshake(crumbOk()).date("QRTV", FEB, febOk()).date("QRTV", DEC, decOk())
                .dflt("IXQZ", ixqzOkY());
    }

    ObjectNode finnhubAt(String c, String nyLocal) {
        return finnhubOk(c, nyLocal);
    }

    // =========================================================================================== scenario

    /** The requirement scenario (data only). Clock left at 2026-11-20 09:50 New York, API account A ("1"). */
    void scenario052() {
        setNow("2026-11-20T09:50");
        accountId = "1";
        deposit("20000.00", "2026-11-16T10:00");
        openN("STO", 1, "QRTV", FEB, "50", "PUT", "380.00", "2026-11-19T10:00");
        openN("STO", 1, "QRTV", DEC, "45", "PUT", "70.00", "2026-11-19T10:05");
        accountB = createAccount("账户B");
        accountId = accountB;
        deposit("20000.00", "2026-11-16T10:00");
        openN("BTO", 2, "QRTV", FEB, "50", "CALL", "800.00", "2026-11-19T10:10");
        transferInShares("IXQZ", 100, "6000.00", "2026-11-18");
        accountId = "1";
    }

    /** Scenario + fake Yahoo + the 10:00 pull (must succeed for both symbols); clock left at 10:00. */
    JsonNode scenarioPulled052() {
        scenario052();
        scenarioYahoo().apply();
        JsonNode t = tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null YAHOO",
                "QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        return t;
    }

    // =========================================================================================== reading

    /**
     * "SYMBOL result optionSource yahooResult cboeResult finnhubResult underlyingPriceSource" per result (shape asserted
     * by the FEAT-016 base).
     */
    static List<String> yres(JsonNode runBody) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : runBody.get("results")) {
            out.add(r.get("symbol").asText() + " " + r.get("result").asText() + " " + r.get("optionSource").asText()
                    + " " + text(r.get("yahooResult")) + " " + text(r.get("cboeResult")) + " "
                    + text(r.get("finnhubResult")) + " " + text(r.get("underlyingPriceSource")));
        }
        return out;
    }

    static JsonNode result(JsonNode runBody, String symbol) {
        for (JsonNode r : runBody.get("results")) {
            if (r.get("symbol").asText().equals(symbol)) {
                return r;
            }
        }
        throw new AssertionError("no result for " + symbol + " in " + runBody);
    }

    /**
     * Every recorded request, in order: Yahoo as "YAHOO HANDSHAKE" / "YAHOO OPTIONS QRTV 2026-12-18" / "YAHOO OPTIONS
     * IXQZ -" (no date), CBOE / Finnhub as "CBOE QRTV" / "FINNHUB QRTV" (kind and expirationDate null).
     */
    List<String> allRequests() {
        Resp r = noAccount(() -> get(REQUESTS));
        assertThat(r.status()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        for (JsonNode q : r.body().get("requests")) {
            assertThat(fieldNames(q)).containsExactlyInAnyOrder("source", "kind", "symbol", "expirationDate", "at");
            String source = q.get("source").asText();
            if (source.equals("YAHOO")) {
                String kind = q.get("kind").asText();
                assertThat(kind).isIn("HANDSHAKE", "OPTIONS");
                if (kind.equals("HANDSHAKE")) {
                    assertThat(q.get("symbol").isNull()).isTrue();
                    assertThat(q.get("expirationDate").isNull()).isTrue();
                    out.add("YAHOO HANDSHAKE");
                } else {
                    out.add("YAHOO OPTIONS " + q.get("symbol").asText() + " "
                            + (q.get("expirationDate").isNull() ? "-" : q.get("expirationDate").asText()));
                }
            } else {
                assertThat(q.get("kind").isNull()).as("kind null for %s", source).isTrue();
                assertThat(q.get("expirationDate").isNull()).isTrue();
                out.add(source + " " + q.get("symbol").asText());
            }
        }
        return out;
    }

    /** "bid/ask mark markBasis source quoteTimeLocal contractStatus" of an optionQuote. */
    static String shownY(JsonNode q) {
        return text(q.get("bid")) + "/" + text(q.get("ask")) + " " + text(q.get("mark")) + " "
                + text(q.get("markBasis")) + " " + text(q.get("source")) + " " + text(q.get("quoteTimeLocal")) + " "
                + q.get("contractStatus").asText();
    }

    /** "price source quoteTimeLocal fetchFailed staleLabel" of an underlying / stock quote. */
    static String shownU(JsonNode u) {
        return text(u.get("price")) + " " + text(u.get("source")) + " " + text(u.get("quoteTimeLocal")) + " "
                + u.get("fetchFailed").asBoolean() + " " + text(u.get("staleLabel"));
    }

    /** The close snapshot of a date. */
    JsonNode snapshot(String isoDate) {
        for (JsonNode s : stored().get("closeSnapshots")) {
            if (s.get("date").asText().equals(isoDate)) {
                return s;
            }
        }
        throw new AssertionError("no close snapshot " + isoDate);
    }

    static JsonNode snapContract(JsonNode snap, String occOrNull, String expiration, String strike, String type) {
        for (JsonNode c : snap.get("contracts")) {
            if (c.get("expirationDate").asText().equals(expiration)
                    && new BigDecimal(c.get("strike").asText()).compareTo(new BigDecimal(strike)) == 0
                    && c.get("optionType").asText().equals(type)) {
                return c;
            }
        }
        throw new AssertionError("no snapshot contract " + expiration + " " + strike + " " + type);
    }

    static void num(JsonNode node, String field, String expected) {
        assertThat(node.get(field)).as("%s present", field).isNotNull();
        assertThat(node.get(field).isNull()).as("%s not null: %s", field, node).isFalse();
        assertThat(new BigDecimal(node.get(field).asText())).as(field).isEqualByComparingTo(expected);
    }

    /** Stored raw Greek within 1e-8 of the independent double recomputation (last digit may differ by rounding). */
    static void approx(JsonNode node, String field, String expected) {
        assertThat(node.get(field).isNull()).as("%s not null: %s", field, node).isFalse();
        assertThat(new BigDecimal(node.get(field).asText()).doubleValue()).as(field)
                .isCloseTo(Double.parseDouble(expected), within(1.1e-8));
    }

    static void allGreeksNull(JsonNode storedContract) {
        for (String k : GREEKS) {
            assertThat(storedContract.get(k).isNull()).as("stored %s null: %s", k, storedContract).isTrue();
        }
    }
}
