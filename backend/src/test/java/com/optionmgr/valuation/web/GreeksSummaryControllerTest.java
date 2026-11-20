package com.optionmgr.valuation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.quote.service.InMemoryQuoteTransport;
import com.optionmgr.quote.service.InMemoryQuoteTransport.Reply;
import com.optionmgr.quote.service.QuoteFetchService;
import com.optionmgr.valuation.web.UnrealizedPnlControllerTest.MovableClock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-019 acceptance criteria 1–9 (backend part of 10) through {@code GET /api/positions/greeks-summary} with the
 * in-memory fake CBOE and a movable clock. Scenario as FEAT-018 (2026-11-23 Monday 10:00 fetch, QRTV 52.40, IXQZ
 * 61.10); account A also holds 100 IXQZ, account B 100 QRTV. Fictional tickers.
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
@Import(UnrealizedPnlControllerTest.ClockConfig.class)
class GreeksSummaryControllerTest {

    static final String URL = "/api/positions/greeks-summary";
    static final String G_50P = greeks("0.3512", "-0.3021", "0.0512", "-0.0456", "0.0711");
    static final String G_55C = greeks("0.3320", "0.2850", "0.0480", "-0.0390", "0.0650");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MovableClock clock;
    @Autowired
    InMemoryQuoteTransport fake;
    @Autowired
    QuoteFetchService fetches;

    long accountB;
    final Map<String, String> qrtv = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        clean();
        clock.set(UnrealizedPnlControllerTest.ny("2026-11-20T09:00:00"));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    void clean() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.update("DELETE FROM underlying_quote");
        jdbc.update("DELETE FROM option_quote");
        jdbc.update("DELETE FROM underlying_quote_close");
        jdbc.update("DELETE FROM option_quote_close");
        jdbc.update("UPDATE market_data_status SET last_attempt_at = NULL, last_success_at = NULL, last_refresh_at = NULL");
        fake.reset();
        qrtv.clear();
    }

    // ---------------------------------------------------------------- scenario and fake data

    void scenario() throws Exception {
        deposit(1, "30000.00");
        stock(1, "BUY", "IXQZ", 100, "5880.00", "2026-11-19T09:45");
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-12-18", "50", "PUT", 1, "118.70", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "BTO", "QRTV", "2026-12-18", "55", "CALL", 2, "301.30", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "STO", "IXQZ", "2026-12-18", "65", "CALL", 1, "84.35", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-12-18", "47.5", "PUT", 1, "45.00", "2026-11-19T10:00");
        accountB = newAccount("账户B");
        deposit(accountB, "20000.00");
        trade(accountB, "/api/option-trades", "STO", "QRTV", "2026-12-18", "50", "PUT", 3, "360.00",
                "2026-11-19T10:00");
        stock(accountB, "BUY", "QRTV", 100, "5000.00", "2026-11-19T09:45");
        qrtv.put("50P", option("QRTV261218P00050000", "1.20", "1.30", G_50P));
        qrtv.put("55C", option("QRTV261218C00055000", "1.80", "1.90", G_55C));
        apply();
    }

    void apply() {
        Map<String, Reply> cboe = new LinkedHashMap<>();
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 09:45:00",
                String.join(",", qrtv.values()))));
        cboe.put("IXQZ", Reply.of(200, chain("IXQZ", "61.10", "2026-11-23 09:45:00",
                option("IXQZ261218C00065000", "0.80", "0.90",
                        greeks("0.2900", "0.18345", "0.03125", "-0.0247", "0.0503")))));
        fake.configure(new InMemoryQuoteTransport.Sources(true, cboe, new LinkedHashMap<>()));
    }

    static String greeks(String iv, String delta, String gamma, String theta, String vega) {
        return "\"iv\":" + iv + ",\"delta\":" + delta + ",\"gamma\":" + gamma + ",\"theta\":" + theta
                + ",\"vega\":" + vega + ",\"rho\":-0.0123";
    }

    static String option(String code, String bid, String ask, String greeks) {
        return "{\"option\":\"" + code + "\",\"bid\":" + bid + ",\"ask\":" + ask + "," + greeks
                + ",\"last_trade_price\":1.22,\"volume\":120,\"open_interest\":3400}";
    }

    static String chain(String symbol, String price, String timestamp, String options) {
        return "{\"timestamp\":\"" + timestamp + "\",\"data\":{\"symbol\":\"" + symbol + "\",\"current_price\":" + price
                + ",\"bid\":0,\"ask\":0,\"prev_day_close\":50.00,\"options\":[" + options + "]}}";
    }

    void at(String nyLocal) {
        clock.set(UnrealizedPnlControllerTest.ny(nyLocal));
    }

    void run(String nyLocal) {
        at(nyLocal);
        QuoteFetchService.TickOutcome t = fetches.tick();
        assertThat(t.ran()).as("tick at %s: %s", nyLocal, t.skipReason()).isTrue();
    }

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_ac2_ac3_accountA() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        JsonNode s = summary(1);
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(symbols(s)).containsExactly("IXQZ", "QRTV");
        JsonNode q = group(s, "QRTV");
        assertNumbers(q, "87.21", "0.00", "87.21", "4.48", "-3.24", "5.89");
        assertExposure(q, "52.40", false, "4569.80");
        assertThat(q.get("optionRowCount").asInt()).isEqualTo(3);
        assertThat(q.get("includedCount").asInt()).isEqualTo(2);
        assertThat(q.get("noQuoteCount").asInt()).isEqualTo(1);
        assertThat(q.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价，未计入");
        assertThat(q.get("missingLabel").isNull()).isTrue();

        JsonNode x = group(s, "IXQZ");
        assertNumbers(x, "-18.35", "100.00", "81.65", "-3.13", "2.47", "-5.03");
        assertExposure(x, "61.10", false, "4988.82");
        assertThat(x.get("excludedLabel").isNull()).isTrue();

        JsonNode p = s.get("portfolio");
        assertPortfolio(p, "9558.62", "-0.77", "0.86");
        assertThat(p.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价，未计入");
        assertThat(p.get("noUnderlyingPriceLabel").isNull()).isTrue();
        assertThat(p.get("missingLabel").isNull()).isTrue();
        assertThat(p.has("netDelta")).isFalse();
        assertThat(p.has("gamma")).isFalse();
    }

    @Test
    void ac4_accountB() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        JsonNode s = summary(accountB);
        assertThat(symbols(s)).containsExactly("QRTV");
        JsonNode q = group(s, "QRTV");
        assertNumbers(q, "90.63", "100.00", "190.63", "-15.36", "13.68", "-21.33");
        assertExposure(q, "52.40", false, "9989.01");
        assertPortfolio(s.get("portfolio"), "9989.01", "13.68", "-21.33");
        assertThat(s.at("/portfolio/excludedLabel").isNull()).isTrue();
        assertThat(s.at("/portfolio/missingLabel").isNull()).isTrue();
    }

    @Test
    void ac5_manualUnderlyingPrice_survivesFailure_thenCleared() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        at("2026-11-23T10:05:00");
        ok(put("/api/market-data/overrides/underlyings/QRTV").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"52.00\"}"));
        assertManualQrtv();

        Map<String, Reply> failing = new LinkedHashMap<>();
        failing.put("QRTV", Reply.of(500, "boom"));
        fake.configure(new InMemoryQuoteTransport.Sources(true, failing, new LinkedHashMap<>()));
        run("2026-11-23T11:00:00");
        assertManualQrtv();

        ok(delete("/api/market-data/overrides/underlyings/QRTV"));
        JsonNode q = group(summary(1), "QRTV");
        assertExposure(q, "52.40", false, "4569.80");
        assertThat(q.get("manualPriceNote").isNull()).isTrue();
        assertPortfolio(summary(1).get("portfolio"), "9558.62", "-0.77", "0.86");
        assertExposure(group(summary(accountB), "QRTV"), "52.40", false, "9989.01");
    }

    void assertManualQrtv() throws Exception {
        JsonNode a = summary(1);
        JsonNode q = group(a, "QRTV");
        assertExposure(q, "52.00", true, "4534.92");
        assertThat(q.get("manualPriceNote").asText()).isEqualTo("按手动标的价");
        assertNumbers(q, "87.21", "0.00", "87.21", "4.48", "-3.24", "5.89");
        assertThat(group(a, "IXQZ").get("manualPriceNote").isNull()).isTrue();
        assertPortfolio(a.get("portfolio"), "9523.74", "-0.77", "0.86");
        JsonNode b = group(summary(accountB), "QRTV");
        assertExposure(b, "52.00", true, "9912.76");
        assertThat(b.get("manualPriceNote").asText()).isEqualTo("按手动标的价");
    }

    @Test
    void ac6_singleMissingGreeks() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        qrtv.put("55C", option("QRTV261218C00055000", "1.80", "1.90",
                greeks("0.3320", "0.2850", "null", "-0.0390", "null")));
        apply();
        run("2026-11-23T11:00:00");
        JsonNode s = summary(1);
        JsonNode q = group(s, "QRTV");
        assertNumbers(q, "87.21", "0.00", "87.21", "-5.12", "-3.24", "-7.11");
        assertExposure(q, "52.40", false, "4569.80");
        assertThat(q.get("gammaMissingCount").asInt()).isEqualTo(1);
        assertThat(q.get("vegaMissingCount").asInt()).isEqualTo(1);
        assertThat(q.get("deltaMissingCount").asInt()).isZero();
        assertThat(q.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价，未计入");
        assertThat(q.get("missingLabel").asText()).isEqualTo("Gamma、Vega：1 个持仓缺值，未计入");
        JsonNode p = s.get("portfolio");
        assertPortfolio(p, "9558.62", "-0.77", "-12.14");
        assertThat(p.get("vegaMissingCount").asInt()).isEqualTo(1);
        assertThat(p.has("gammaMissingCount")).isFalse();
        assertThat(p.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价，未计入");
        assertThat(p.get("missingLabel").asText()).isEqualTo("Vega：1 个持仓缺值，未计入");
    }

    @Test
    void ac7_expiredPendingAndExpirationDay() throws Exception {
        scenario();
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-11-20", "45", "PUT", 1, "60.00", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-11-23", "52", "PUT", 1, "150.00", "2026-11-19T10:00");
        qrtv.put("52P", option("QRTV261123P00052000", "0.50", "0.60",
                greeks("0.4000", "-0.4500", "0.1200", "-0.0800", "0.0150")));
        apply();
        run("2026-11-23T10:00:00");
        JsonNode s = summary(1);
        JsonNode q = group(s, "QRTV");
        assertNumbers(q, "132.21", "0.00", "132.21", "-7.52", "4.76", "4.39");
        assertExposure(q, "52.40", false, "6927.80");
        assertThat(q.get("optionRowCount").asInt()).isEqualTo(5);
        assertThat(q.get("expiredPendingCount").asInt()).isEqualTo(1);
        assertThat(q.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");
        JsonNode p = s.get("portfolio");
        assertPortfolio(p, "11916.62", "7.23", "-0.64");
        assertThat(p.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");
    }

    @Test
    void ac8_noUnderlyingPrice_thenManual() throws Exception {
        at("2026-11-23T10:00:00");
        long c = newAccount("账户C");
        deposit(c, "10000.00");
        stock(c, "BUY", "IXQZ", 100, "5880.00", "2026-11-19T09:45");
        JsonNode s = summary(c);
        JsonNode x = group(s, "IXQZ");
        for (String f : new String[] {"optionDelta", "gamma", "theta", "vega", "underlyingPrice", "dollarExposure",
                "manualPriceNote", "excludedLabel", "missingLabel"}) {
            assertThat(x.get(f).isNull()).as(f).isTrue();
        }
        assertThat(x.get("stockDelta").asText()).isEqualTo("100.00");
        assertThat(x.get("netDelta").asText()).isEqualTo("100.00");
        assertThat(x.get("underlyingPriceLabel").asText()).isEqualTo("无标的价");
        assertThat(x.get("exposureStatus").asText()).isEqualTo("NO_UNDERLYING_PRICE");
        assertThat(x.get("exposureLabel").asText()).isEqualTo("无标的价");
        assertThat(x.get("optionRowCount").asInt()).isZero();
        JsonNode p = s.get("portfolio");
        assertPortfolio(p, "0.00", "0.00", "0.00");
        assertThat(p.get("noUnderlyingPriceCount").asInt()).isEqualTo(1);
        assertThat(p.get("noUnderlyingPriceLabel").asText()).isEqualTo("1 个标的无标的价，美元敞口未计入");

        ok(put("/api/market-data/overrides/underlyings/IXQZ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"60.00\"}"));
        s = summary(c);
        x = group(s, "IXQZ");
        assertExposure(x, "60.00", true, "6000.00");
        assertThat(x.get("manualPriceNote").asText()).isEqualTo("按手动标的价");
        assertPortfolio(s.get("portfolio"), "6000.00", "0.00", "0.00");
        assertThat(s.at("/portfolio/noUnderlyingPriceCount").asInt()).isZero();
        assertThat(s.at("/portfolio/noUnderlyingPriceLabel").isNull()).isTrue();
    }

    @Test
    void ac9_closingRemovesValues_andGroups() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        at("2026-11-23T10:10:00");
        trade(1, "/api/option-trades/closes", "STC", "QRTV", "2026-12-18", "55", "CALL", 2, "368.70",
                "2026-11-23T10:10");
        JsonNode s = summary(1);
        JsonNode q = group(s, "QRTV");
        assertNumbers(q, "30.21", "0.00", "30.21", "-5.12", "4.56", "-7.11");
        assertExposure(q, "52.40", false, "1583.00");
        assertPortfolio(s.get("portfolio"), "6571.82", "7.03", "-12.14");

        trade(1, "/api/option-trades/closes", "BTC", "IXQZ", "2026-12-18", "65", "CALL", 1, "86.65",
                "2026-11-23T10:10");
        stock(1, "SELL", "IXQZ", 100, "6110.00", "2026-11-23T10:10");
        assertThat(symbols(summary(1))).containsExactly("QRTV");
    }

    @Test
    void ac10_noFilterParameter_emptyAccount_andErrors() throws Exception {
        long empty = newAccount("空账户");
        JsonNode s = summary(empty);
        assertThat(s.get("groups")).isEmpty();
        assertPortfolio(s.get("portfolio"), "0.00", "0.00", "0.00");
        for (String f : new String[] {"excludedLabel", "noUnderlyingPriceLabel", "missingLabel"}) {
            assertThat(s.get("portfolio").get(f).isNull()).as(f).isTrue();
        }

        Res r = call(get(URL));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("ACCOUNT_REQUIRED");
        r = call(get(URL).header(AccountRef.HEADER, "999"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        r = call(post(URL).header(AccountRef.HEADER, "1"));
        assertThat(r.status()).isEqualTo(405);
    }

    /** FEAT-047 Q233 A: merged exposure = merged net delta x price, rounded once (not the sum of rounded ones). */
    @Test
    void feat047_allAccountsMergedExposureRoundedOnce() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        assertExposure(group(summary(1), "QRTV"), "52.40", false, "4569.80");
        assertExposure(group(summary(accountB), "QRTV"), "52.40", false, "9989.01");
        JsonNode s = ok(get(URL).header(AccountRef.HEADER, "ALL"));
        assertThat(symbols(s)).containsExactly("IXQZ", "QRTV");
        JsonNode q = group(s, "QRTV");
        // 87.21 + 190.63 = 277.84; x 52.40 = 14558.816 -> 14558.82 (per-account sum 4569.80 + 9989.01 = 14558.81)
        assertNumbers(q, "177.84", "100.00", "277.84", "-10.88", "10.44", "-15.44");
        assertExposure(q, "52.40", false, "14558.82");
        assertThat(q.get("optionRowCount").asInt()).isEqualTo(4);
        assertThat(q.get("noQuoteCount").asInt()).isEqualTo(1);
        assertExposure(group(s, "IXQZ"), "61.10", false, "4988.82");
        assertPortfolio(s.get("portfolio"), "19547.64", "12.91", "-20.47");
    }

    @Test
    void groupValuesEqualPositionRows() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        JsonNode rows = ok(get("/api/option-positions").header(AccountRef.HEADER, "1")).get("positions");
        java.math.BigDecimal delta = java.math.BigDecimal.ZERO;
        String price = null;
        for (JsonNode p : rows) {
            if (p.get("symbol").asText().equals("QRTV")) {
                price = p.at("/underlyingQuote/price").asText();
                if (p.at("/greeks/status").asText().equals("OK")) {
                    delta = delta.add(new java.math.BigDecimal(p.at("/greeks/deltaPosition").asText()));
                }
            }
        }
        assertThat(group(summary(1), "QRTV").get("optionDelta").asText()).isEqualTo(delta.toPlainString());
        assertThat(group(summary(1), "QRTV").get("underlyingPrice").asText()).isEqualTo(price);
    }

    // ---------------------------------------------------------------- helpers

    static void assertNumbers(JsonNode g, String optionDelta, String stockDelta, String net, String gamma,
                              String theta, String vega) {
        assertThat(g.get("optionDelta").asText()).isEqualTo(optionDelta);
        assertThat(g.get("stockDelta").asText()).isEqualTo(stockDelta);
        assertThat(g.get("netDelta").asText()).isEqualTo(net);
        assertThat(g.get("gamma").asText()).isEqualTo(gamma);
        assertThat(g.get("theta").asText()).isEqualTo(theta);
        assertThat(g.get("vega").asText()).isEqualTo(vega);
    }

    static void assertExposure(JsonNode g, String price, boolean manual, String exposure) {
        assertThat(g.get("underlyingPrice").asText()).isEqualTo(price);
        assertThat(g.get("underlyingPriceManual").asBoolean()).isEqualTo(manual);
        assertThat(g.get("underlyingPriceLabel").isNull()).isTrue();
        assertThat(g.get("dollarExposure").asText()).isEqualTo(exposure);
        assertThat(g.get("exposureStatus").asText()).isEqualTo("OK");
        assertThat(g.get("exposureLabel").isNull()).isTrue();
    }

    static void assertPortfolio(JsonNode p, String exposure, String theta, String vega) {
        assertThat(p.get("dollarExposure").asText()).isEqualTo(exposure);
        assertThat(p.get("theta").asText()).isEqualTo(theta);
        assertThat(p.get("vega").asText()).isEqualTo(vega);
    }

    static List<String> symbols(JsonNode s) {
        List<String> out = new ArrayList<>();
        s.get("groups").forEach(g -> out.add(g.get("symbol").asText()));
        return out;
    }

    static JsonNode group(JsonNode s, String symbol) {
        for (JsonNode g : s.get("groups")) {
            if (g.get("symbol").asText().equals(symbol)) {
                return g;
            }
        }
        throw new AssertionError("no group " + symbol + " in " + s);
    }

    JsonNode summary(long account) throws Exception {
        return ok(get(URL).header(AccountRef.HEADER, String.valueOf(account)));
    }

    record Res(int status, JsonNode body, String raw) {
    }

    Res call(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Res(r.getResponse().getStatus(), raw.isEmpty() ? null : json.readTree(raw), raw);
    }

    JsonNode ok(MockHttpServletRequestBuilder req) throws Exception {
        Res r = call(req);
        assertThat(r.status()).as(r.raw()).isBetween(200, 204);
        return r.body();
    }

    Res postJson(long account, String url, ObjectNode body) throws Exception {
        return call(post(url).header(AccountRef.HEADER, String.valueOf(account)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    long newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        Res r = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(n)));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void deposit(long account, String amount) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", "2026-11-19T09:00");
        Res r = postJson(account, "/api/cash/transfers", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    void stock(long account, String type, String symbol, int shares, String amount, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", time);
        Res r = postJson(account, "/api/stock-trades", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    void trade(long account, String url, String action, String symbol, String exp, String strike, String type,
               int contracts, String net, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", time);
        Res r = postJson(account, url, n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }
}
