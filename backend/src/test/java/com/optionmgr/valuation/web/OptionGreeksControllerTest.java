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
import java.util.LinkedHashMap;
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
 * FEAT-018 acceptance criteria 1–9 (backend) through {@code GET /api/option-positions} with the in-memory fake CBOE
 * and a movable clock. Scenario (requirement): 2026-11-23 (Monday) 10:00 fetch, quote time 09:45; account A: P1 STO 1
 * QRTV 2026-12-18 50P, P2 BTO 2 QRTV 55C, P3 STO 1 IXQZ 65C, P4 STO 1 QRTV 47.5P (never in the chain); account B: P5
 * STO 3 QRTV 50P. Fictional tickers. (AC10 is frontend only.)
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
@Import(UnrealizedPnlControllerTest.ClockConfig.class)
class OptionGreeksControllerTest {

    static final String P1 = "QRTV 2026-12-18 50 P";
    static final String P2 = "QRTV 2026-12-18 55 C";
    static final String P3 = "IXQZ 2026-12-18 65 C";
    static final String P4 = "QRTV 2026-12-18 47.5 P";
    static final String P6 = "QRTV 2026-11-20 45 P";
    static final String P7 = "QRTV 2026-11-23 52 P";

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
    /** QRTV chain options by short key, in order. */
    final Map<String, String> qrtv = new LinkedHashMap<>();
    String qrtvTimestamp = "2026-11-23 09:45:00";

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
        qrtvTimestamp = "2026-11-23 09:45:00";
    }

    // ---------------------------------------------------------------- scenario and fake data

    void scenario() throws Exception {
        deposit(1, "30000.00");
        Res r = postJson(1, "/api/stock-trades", stock("IXQZ", 100, "5880.00"));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        trade(1, "STO", "QRTV", "2026-12-18", "50", "PUT", 1, "118.70");
        trade(1, "BTO", "QRTV", "2026-12-18", "55", "CALL", 2, "301.30");
        trade(1, "STO", "IXQZ", "2026-12-18", "65", "CALL", 1, "84.35");
        trade(1, "STO", "QRTV", "2026-12-18", "47.5", "PUT", 1, "45.00");
        accountB = newAccount("账户B");
        deposit(accountB, "20000.00");
        trade(accountB, "STO", "QRTV", "2026-12-18", "50", "PUT", 3, "360.00");
        qrtv.put("50P", option("QRTV261218P00050000", "1.20", "1.30", G_50P));
        qrtv.put("55C", option("QRTV261218C00055000", "1.80", "1.90", G_55C));
        apply();
    }

    void apply() {
        Map<String, Reply> cboe = new LinkedHashMap<>();
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", qrtvTimestamp, String.join(",", qrtv.values()))));
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

    void run(String nyLocal) {
        clock.set(UnrealizedPnlControllerTest.ny(nyLocal));
        QuoteFetchService.TickOutcome t = fetches.tick();
        assertThat(t.ran()).as("tick at %s: %s", nyLocal, t.skipReason()).isTrue();
    }

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_ac2_ac3_ac4_accountA() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        JsonNode p1 = greeksOf(1, P1);
        assertOk(p1, "35.12", "-0.3021", "30.21", "0.0512", "-5.12", "-0.0456", "4.56", "0.0711", "-7.11");
        assertThat(p1.get("quoteTime").asText()).isEqualTo("2026-11-23T14:45:00Z");
        assertThat(p1.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:45:00");
        assertThat(p1.get("manualMarkNote").isNull()).isTrue();
        assertThat(p1.has("rho")).isFalse();
        assertThat(p1.has("rhoPerContract")).isFalse();
        assertOk(greeksOf(1, P2), "33.20", "0.2850", "57.00", "0.0480", "9.60", "-0.0390", "-7.80", "0.0650",
                "13.00");
        assertOk(greeksOf(1, P3), "29.00", "0.1835", "-18.35", "0.0313", "-3.13", "-0.0247", "2.47", "0.0503",
                "-5.03");
        assertUnavailable(greeksOf(1, P4), "NO_QUOTE", "无报价");
        assertThat(greeksOf(1, P4).get("quoteTime").isNull()).isTrue();
        // AC4: FEAT-017 unchanged
        assertThat(position(1, P1).get("unrealizedPnl").asText()).isEqualTo("-6.30");
    }

    @Test
    void ac5_accountB() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        assertOk(greeksOf(accountB, P1), "35.12", "-0.3021", "90.63", "0.0512", "-15.36", "-0.0456", "13.68",
                "0.0711", "-21.33");
    }

    @Test
    void ac6_newValuesThenFetchFailureKeepsThem() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        qrtv.put("50P", option("QRTV261218P00050000", "1.20", "1.30",
                greeks("0.3600", "-0.3300", "0.0530", "-0.0470", "0.0720")));
        qrtvTimestamp = "2026-11-23 10:00:00";
        apply();
        run("2026-11-23T10:15:00");
        assertOk(greeksOf(1, P1), "36.00", "-0.3300", "33.00", "0.0530", "-5.30", "-0.0470", "4.70", "0.0720",
                "-7.20");

        Map<String, Reply> failing = new LinkedHashMap<>();
        failing.put("QRTV", Reply.of(500, "boom"));
        fake.configure(new InMemoryQuoteTransport.Sources(true, failing, new LinkedHashMap<>()));
        run("2026-11-23T10:30:00");
        JsonNode p1 = position(1, P1);
        assertThat(p1.at("/optionQuote/staleLabel").asText()).isEqualTo("行情未更新（上次：11-23 10:15）");
        assertOk(p1.get("greeks"), "36.00", "-0.3300", "33.00", "0.0530", "-5.30", "-0.0470", "4.70", "0.0720",
                "-7.20");
    }

    @Test
    void ac7_manualMarkNote_andNeverQuotedStaysNoQuote() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        clock.set(UnrealizedPnlControllerTest.ny("2026-11-23T10:05:00"));
        ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2026-12-18\",\"strike\":\"50\",\"optionType\":\"PUT\","
                        + "\"mark\":\"1.40\"}"));
        JsonNode p1 = greeksOf(1, P1);
        assertOk(p1, "35.12", "-0.3021", "30.21", "0.0512", "-5.12", "-0.0456", "4.56", "0.0711", "-7.11");
        assertThat(p1.get("manualMarkNote").asText()).isEqualTo("Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整");
        assertThat(greeksOf(accountB, P1).get("manualMarkNote").asText()).contains("11-23 09:45");

        ok(delete("/api/market-data/overrides/options?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT"));
        assertThat(greeksOf(1, P1).get("manualMarkNote").isNull()).isTrue();

        ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2026-12-18\",\"strike\":\"47.5\",\"optionType\":\"PUT\","
                        + "\"mark\":\"0.30\"}"));
        JsonNode p4 = position(1, P4);
        assertThat(p4.get("unrealizedStatus").asText()).isEqualTo("OK");
        assertUnavailable(p4.get("greeks"), "NO_QUOTE", "无报价");
    }

    @Test
    void ac8_singleMissingFields() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        qrtv.put("55C", option("QRTV261218C00055000", "1.80", "1.90",
                greeks("0.3320", "0.2850", "null", "-0.0390", "null")));
        apply();
        run("2026-11-23T10:15:00");
        JsonNode p2 = greeksOf(1, P2);
        assertThat(p2.get("status").asText()).isEqualTo("OK");
        assertThat(p2.get("ivPercent").asText()).isEqualTo("33.20");
        assertThat(p2.get("deltaPerContract").asText()).isEqualTo("0.2850");
        assertThat(p2.get("deltaPosition").asText()).isEqualTo("57.00");
        assertThat(p2.get("gammaPerContract").isNull()).isTrue();
        assertThat(p2.get("gammaPosition").isNull()).isTrue();
        assertThat(p2.get("thetaPosition").asText()).isEqualTo("-7.80");
        assertThat(p2.get("vegaPerContract").isNull()).isTrue();
        assertThat(p2.get("vegaPosition").isNull()).isTrue();
    }

    @Test
    void ac9_expiredPendingAndExpirationDay() throws Exception {
        scenario();
        trade(1, "STO", "QRTV", "2026-11-20", "45", "PUT", 1, "60.00");
        trade(1, "STO", "QRTV", "2026-11-23", "52", "PUT", 1, "150.00");
        qrtv.put("45P", option("QRTV261120P00045000", "0.04", "0.06",
                greeks("0.9000", "-0.0100", "0.0100", "-0.0100", "0.0010")));
        qrtvTimestamp = "2026-11-20 09:45:00";
        apply();
        run("2026-11-20T10:00:00");
        qrtv.remove("45P");
        qrtv.put("52P", option("QRTV261123P00052000", "0.50", "0.60",
                greeks("0.4000", "-0.4500", "0.2000", "-0.1500", "0.0100")));
        qrtvTimestamp = "2026-11-23 09:45:00";
        apply();
        run("2026-11-23T10:00:00");

        JsonNode p6 = greeksOf(1, P6);
        assertUnavailable(p6, "EXPIRED_PENDING", "已到期，待处理");
        assertThat(p6.get("quoteTimeLocal").asText()).isEqualTo("2026-11-20T09:45:00");
        JsonNode p7 = greeksOf(1, P7);
        assertThat(p7.get("status").asText()).isEqualTo("OK");
        assertThat(p7.get("deltaPerContract").asText()).isEqualTo("-0.4500");
        assertThat(p7.get("deltaPosition").asText()).isEqualTo("45.00");
    }

    // ---------------------------------------------------------------- helpers

    static void assertOk(JsonNode g, String iv, String dc, String dp, String gc, String gp, String tc, String tp,
                         String vc, String vp) {
        assertThat(g.get("status").asText()).isEqualTo("OK");
        assertThat(g.get("label").isNull()).isTrue();
        assertThat(g.get("ivPercent").asText()).isEqualTo(iv);
        assertThat(g.get("deltaPerContract").asText()).isEqualTo(dc);
        assertThat(g.get("deltaPosition").asText()).isEqualTo(dp);
        assertThat(g.get("gammaPerContract").asText()).isEqualTo(gc);
        assertThat(g.get("gammaPosition").asText()).isEqualTo(gp);
        assertThat(g.get("thetaPerContract").asText()).isEqualTo(tc);
        assertThat(g.get("thetaPosition").asText()).isEqualTo(tp);
        assertThat(g.get("vegaPerContract").asText()).isEqualTo(vc);
        assertThat(g.get("vegaPosition").asText()).isEqualTo(vp);
    }

    static void assertUnavailable(JsonNode g, String status, String label) {
        assertThat(g.get("status").asText()).isEqualTo(status);
        assertThat(g.get("label").asText()).isEqualTo(label);
        for (String f : new String[] {"ivPercent", "deltaPerContract", "deltaPosition", "gammaPerContract",
                "gammaPosition", "thetaPerContract", "thetaPosition", "vegaPerContract", "vegaPosition",
                "manualMarkNote"}) {
            assertThat(g.get(f).isNull()).as(f).isTrue();
        }
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

    ObjectNode stock(String symbol, int shares, String amount) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "BUY");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", "2026-11-19T09:45");
        return n;
    }

    void trade(long account, String action, String symbol, String exp, String strike, String type, int contracts,
               String net) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", "2026-11-19T10:00");
        Res r = postJson(account, "/api/option-trades", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    JsonNode position(long account, String label) throws Exception {
        for (JsonNode p : ok(get("/api/option-positions").header(AccountRef.HEADER, String.valueOf(account)))
                .get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    JsonNode greeksOf(long account, String label) throws Exception {
        return position(account, label).get("greeks");
    }
}
