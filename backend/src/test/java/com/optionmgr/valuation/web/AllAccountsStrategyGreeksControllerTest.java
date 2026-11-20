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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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
 * FEAT-047 acceptance 1–10 (backend) with scenario G of the requirement: today 2026-11-23 (Monday) 10:00 New York,
 * fetch OK, ZZA 50.00, ZZB 40.00; 甲账户 (id 1) and 乙账户 (id 2). Fictional tickers. {@code underlying-summary} and
 * {@code greeks-summary} accept {@code X-Account-Id: ALL}: summary groups per account + symbol (Q224 A), Greeks per
 * symbol across accounts (Q224 B, Q233 A).
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
@Import(UnrealizedPnlControllerTest.ClockConfig.class)
class AllAccountsStrategyGreeksControllerTest {

    static final String SUMMARY = "/api/positions/underlying-summary";
    static final String GREEKS = "/api/positions/greeks-summary";
    static final String EXPIRED = "1 个持仓已到期待处理，未计入";

    // expected summary groups (scenario G, contract docs/api/FEAT-047.md)
    static final String A_ZZA = "1 甲账户 ZZA | 1 39.70 1 39.70 0 0.00 | 4940.00 225.35 265.05 85.35 | 1 true 2 0 0 null";
    static final String B_ZZA = "2 乙账户 ZZA | 1 80.00 1 80.00 0 0.00 | -220.00 10.00 90.00 230.00 | 1 false 1 0 0 null";
    static final String A_ZZB = "1 甲账户 ZZB | 0 0.00 0 0.00 0 0.00 | -110.00 10.00 10.00 120.00 | 1 false 1 0 0 null";
    static final String B_ZZB = "2 乙账户 ZZB | 0 0.00 0 0.00 0 0.00 | null null 0.00 50.00 | 1 false 0 0 1 "
            + EXPIRED;

    // expected Greeks groups
    static final String ALL_ZZA = "ZZA 10.00 100.00 110.00 -10.00 8.00 -16.00 | 50.00 false 5500.00 OK null | 2 2 0 0 null";
    static final String ALL_ZZB = "ZZB 30.00 0.00 30.00 -5.00 4.00 -4.50 | 40.00 false 1200.00 OK null | 2 1 0 1 "
            + EXPIRED;
    static final String ALL_PORTFOLIO = "6700.00 12.00 -20.50 | 0 1 0 | " + EXPIRED;

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

    @BeforeEach
    void setUp() {
        clean();
        at("2026-11-23T09:00:00");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    void clean() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM expiration_adjustment");
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.update("UPDATE account SET name = '默认账户', name_key = '默认账户', broker_note = NULL WHERE id = 1");
        jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 2");
        jdbc.update("DELETE FROM underlying_quote");
        jdbc.update("DELETE FROM option_quote");
        jdbc.update("DELETE FROM underlying_quote_close");
        jdbc.update("DELETE FROM option_quote_close");
        jdbc.update("UPDATE market_data_status SET last_attempt_at = NULL, last_success_at = NULL, last_refresh_at = NULL");
        fake.reset();
    }

    // ---------------------------------------------------------------- scenario G

    void accountA() throws Exception {
        rename(1, "甲账户");
        deposit(1, "50000.00");
        transferIn(1, "ZZA", 100, "4800.00", "2026-10-01");                                         // S1
        trade(1, "", "STO", "ZZA", "2026-11-20", "52", "CALL", 1, "60.35", "2026-10-20T10:00");      // H1
        trade(1, "/closes", "BTC", "ZZA", "2026-11-20", "52", "CALL", 1, "20.65", "2026-10-30T10:00"); // H2
        trade(1, "", "STO", "ZZA", "2026-12-18", "55", "CALL", 1, "85.35", "2026-11-02T10:00");      // P1
        trade(1, "", "STO", "ZZB", "2026-12-18", "38", "PUT", 1, "120.00", "2026-11-05T10:00");      // P2
    }

    void accountB() throws Exception {
        assertThat(newAccount("乙账户")).isEqualTo(2);
        deposit(2, "20000.00");
        trade(2, "", "STO", "ZZA", "2026-11-13", "47", "PUT", 1, "80.00", "2026-10-27T10:00");       // H3
        expire(2, "ZZA", "2026-11-13", "47", "PUT");
        trade(2, "", "STO", "ZZA", "2026-12-18", "45", "PUT", 2, "230.00", "2026-11-05T10:00");      // P3
        trade(2, "", "STO", "ZZB", "2026-11-20", "36", "PUT", 1, "50.00", "2026-11-02T10:00");       // P4
    }

    void scenarioG() throws Exception {
        accountA();
        accountB();
        fetch();
    }

    void fetch() {
        Map<String, Reply> cboe = new LinkedHashMap<>();
        cboe.put("ZZA", Reply.of(200, chain("ZZA", "50.00",
                option("ZZA261218C00055000", "0.55", "0.65", "0.2000", "0.0400", "-0.0300", "0.0600") + ","
                        + option("ZZA261218P00045000", "1.05", "1.15", "-0.1500", "0.0300", "-0.0250", "0.0500"))));
        cboe.put("ZZB", Reply.of(200, chain("ZZB", "40.00",
                option("ZZB261218P00038000", "1.05", "1.15", "-0.3000", "0.0500", "-0.0400", "0.0450"))));
        fake.configure(new InMemoryQuoteTransport.Sources(true, cboe, new HashMap<>()));
        at("2026-11-23T10:00:00");
        QuoteFetchService.TickOutcome t = fetches.tick();
        assertThat(t.ran()).as("tick: %s", t.skipReason()).isTrue();
    }

    static String option(String code, String bid, String ask, String delta, String gamma, String theta,
                         String vega) {
        return "{\"option\":\"" + code + "\",\"bid\":" + bid + ",\"ask\":" + ask + ",\"iv\":0.3000,\"delta\":" + delta
                + ",\"gamma\":" + gamma + ",\"theta\":" + theta + ",\"vega\":" + vega + ",\"rho\":-0.0100,"
                + "\"last_trade_price\":1.00,\"volume\":10,\"open_interest\":100}";
    }

    static String chain(String symbol, String price, String options) {
        return "{\"timestamp\":\"2026-11-23 09:45:00\",\"data\":{\"symbol\":\"" + symbol + "\",\"current_price\":"
                + price + ",\"bid\":0,\"ask\":0,\"prev_day_close\":" + price + ",\"options\":[" + options + "]}}";
    }

    // ---------------------------------------------------------------- AC 1, 2: summary cards

    @Test
    void ac1_ac2_summaryAllOneGroupPerAccountAndSymbol() throws Exception {
        scenarioG();
        JsonNode s = ok(SUMMARY, "ALL");
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(groups(s)).containsExactly(A_ZZA, B_ZZA, A_ZZB, B_ZZB);
        JsonNode g = s.get("groups").get(0);
        assertThat(g.get("accountId").isNumber()).isTrue();
        assertThat(g.get("marketValue").isTextual()).isTrue();
        assertThat(g.at("/realized/realizedPnl").isTextual()).isTrue();
        assertThat(s.has("total")).isFalse();
    }

    @Test
    void ac2_singleAccountUnchangedWithAccountFields() throws Exception {
        scenarioG();
        JsonNode all = ok(SUMMARY, "ALL");
        JsonNode a = ok(SUMMARY, "1");
        assertThat(groups(a)).containsExactly(A_ZZA, A_ZZB);
        assertThat(a.get("groups").get(0)).isEqualTo(all.get("groups").get(0));
        assertThat(a.get("groups").get(1)).isEqualTo(all.get("groups").get(2));
        assertThat(groups(ok(SUMMARY, "2"))).containsExactly(B_ZZA, B_ZZB);
    }

    // ---------------------------------------------------------------- AC 3: consistency

    @Test
    void ac3_summaryGroupsAddUpToAllAccountTotals() throws Exception {
        scenarioG();
        JsonNode s = ok(SUMMARY, "ALL");
        BigDecimal mv = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        int included = 0;
        int expired = 0;
        BigDecimal zzaRealized = BigDecimal.ZERO;
        int zzaClosed = 0;
        for (JsonNode g : s.get("groups")) {
            if (!g.get("marketValue").isNull()) {
                mv = mv.add(new BigDecimal(g.get("marketValue").asText()));
                pnl = pnl.add(new BigDecimal(g.get("unrealizedPnl").asText()));
            }
            included += g.get("includedCount").asInt();
            expired += g.get("expiredPendingCount").asInt();
            if (g.get("symbol").asText().equals("ZZA")) {
                zzaRealized = zzaRealized.add(new BigDecimal(g.at("/realized/realizedPnl").asText()));
                zzaClosed += g.at("/realized/closedCount").asInt();
            }
        }
        JsonNode total = ok("/api/positions/unrealized-summary", "ALL").get("total");
        assertThat(mv.toPlainString()).isEqualTo(total.get("marketValue").asText()).isEqualTo("4610.00");
        assertThat(pnl.toPlainString()).isEqualTo(total.get("unrealizedPnl").asText()).isEqualTo("245.35");
        assertThat(included).isEqualTo(total.get("includedCount").asInt());
        assertThat(expired).isEqualTo(total.get("expiredPendingCount").asInt()).isEqualTo(1);

        JsonNode zza = null;
        for (JsonNode r : ok("/api/pnl-summary", "ALL").get("bySymbol")) {
            if (r.get("symbol").asText().equals("ZZA")) {
                zza = r;
            }
        }
        assertThat(zza).isNotNull();
        assertThat(zzaClosed).isEqualTo(zza.get("closedCount").asInt()).isEqualTo(2);
        assertThat(zzaRealized.toPlainString()).isEqualTo(zza.get("realizedPnl").asText()).isEqualTo("119.70");

        // netPremium = sum of openingNetTotal of the option-position rows with the same account + symbol
        Map<String, BigDecimal> premium = new HashMap<>();
        for (JsonNode p : ok("/api/option-positions", "ALL").get("positions")) {
            premium.merge(p.get("accountId").asLong() + " " + p.get("symbol").asText(),
                    new BigDecimal(p.get("openingNetTotal").asText()), BigDecimal::add);
        }
        for (JsonNode g : s.get("groups")) {
            assertThat(g.get("netPremium").asText())
                    .isEqualTo(premium.get(g.get("accountId").asLong() + " " + g.get("symbol").asText())
                            .toPlainString());
        }
    }

    // ---------------------------------------------------------------- AC 5, 6: Greeks merged per symbol

    @Test
    void ac5_ac6_greeksAllMergedPerSymbol() throws Exception {
        scenarioG();
        JsonNode s = ok(GREEKS, "ALL");
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(greeks(s)).containsExactly(ALL_ZZA, ALL_ZZB);
        assertThat(portfolio(s)).isEqualTo(ALL_PORTFOLIO);
        JsonNode g = s.get("groups").get(0);
        assertThat(g.has("accountId")).isFalse();
        assertThat(g.has("accountName")).isFalse();
        assertThat(g.get("dollarExposure").isTextual()).isTrue();
    }

    @Test
    void ac6_singleAccountsUnchanged_bZzbNoDeltaButOkInAll() throws Exception {
        scenarioG();
        JsonNode a = ok(GREEKS, "1");
        assertThat(greeks(a)).containsExactly(
                "ZZA -20.00 100.00 80.00 -4.00 3.00 -6.00 | 50.00 false 4000.00 OK null | 1 1 0 0 null",
                "ZZB 30.00 0.00 30.00 -5.00 4.00 -4.50 | 40.00 false 1200.00 OK null | 1 1 0 0 null");
        assertThat(portfolio(a)).isEqualTo("5200.00 7.00 -10.50 | 0 0 0 | null");

        JsonNode b = ok(GREEKS, "2");
        List<String> rows = greeks(b);
        assertThat(rows.get(0)).isEqualTo(
                "ZZA 30.00 0.00 30.00 -6.00 5.00 -10.00 | 50.00 false 1500.00 OK null | 1 1 0 0 null");
        JsonNode zzb = b.get("groups").get(1);
        assertThat(zzb.get("symbol").asText()).isEqualTo("ZZB");
        assertThat(zzb.get("exposureStatus").asText()).isEqualTo("NO_DELTA");
        for (String f : new String[] {"optionDelta", "netDelta", "gamma", "theta", "vega", "dollarExposure"}) {
            assertThat(zzb.get(f).isNull()).as(f).isTrue();
        }
        assertThat(zzb.get("expiredPendingCount").asInt()).isEqualTo(1);
        assertThat(portfolio(b)).isEqualTo("1500.00 5.00 -10.00 | 0 1 0 | " + EXPIRED);

        // merged: ZZB is OK under ALL (P2 of 甲 included, P4 counted as expired pending)
        assertThat(ok(GREEKS, "ALL").get("groups").get(1).get("exposureStatus").asText()).isEqualTo("OK");
    }

    // ---------------------------------------------------------------- AC 7: manual underlying price under ALL

    @Test
    void ac7_manualUnderlyingPriceUnderAll_thenCleared() throws Exception {
        scenarioG();
        at("2026-11-23T10:05:00");
        ObjectNode n = json.createObjectNode();
        n.put("price", "52.00");
        call(put("/api/market-data/overrides/underlyings/ZZA"), "ALL", n, 200);

        JsonNode s = ok(GREEKS, "ALL");
        assertThat(greeks(s)).containsExactly(
                "ZZA 10.00 100.00 110.00 -10.00 8.00 -16.00 | 52.00 true 5720.00 OK 按手动标的价 | 2 2 0 0 null",
                ALL_ZZB);
        assertThat(s.at("/portfolio/dollarExposure").asText()).isEqualTo("6920.00");
        // option rows of the cards keep their values; only 甲's stock row follows the underlying price
        List<String> cards = groups(ok(SUMMARY, "ALL"));
        assertThat(cards.subList(1, 4)).containsExactly(B_ZZA, A_ZZB, B_ZZB);
        assertThat(cards.get(0)).contains("| 5140.00 425.35 465.05 85.35 |");

        call(delete("/api/market-data/overrides/underlyings/ZZA"), "ALL", null, 204);
        s = ok(GREEKS, "ALL");
        assertThat(greeks(s)).containsExactly(ALL_ZZA, ALL_ZZB);
        assertThat(portfolio(s)).isEqualTo(ALL_PORTFOLIO);
        assertThat(groups(ok(SUMMARY, "ALL"))).containsExactly(A_ZZA, B_ZZA, A_ZZB, B_ZZB);
    }

    // ---------------------------------------------------------------- AC 8: after a write in one account

    @Test
    void ac8_closeP1InAccountA_thenReadAll() throws Exception {
        scenarioG();
        at("2026-11-23T10:30:00");
        trade(1, "/closes", "BTC", "ZZA", "2026-12-18", "55", "CALL", 1, "60.65", "2026-11-23T10:30");
        assertThat(groups(ok(SUMMARY, "ALL"))).containsExactly(
                "1 甲账户 ZZA | 2 64.40 2 64.40 0 0.00 | 5000.00 200.00 264.40 null | 0 true 1 0 0 null",
                B_ZZA, A_ZZB, B_ZZB);
        JsonNode s = ok(GREEKS, "ALL");
        assertThat(greeks(s)).containsExactly(
                "ZZA 30.00 100.00 130.00 -6.00 5.00 -10.00 | 50.00 false 6500.00 OK null | 1 1 0 0 null", ALL_ZZB);
        assertThat(portfolio(s)).isEqualTo("7700.00 9.00 -14.50 | 0 1 0 | " + EXPIRED);
    }

    // ---------------------------------------------------------------- AC 9: nothing held

    @Test
    void ac9_noPositionsAnywhere() throws Exception {
        rename(1, "甲账户");
        newAccount("乙账户");
        assertThat(ok(SUMMARY, "ALL").get("groups")).isEmpty();
        JsonNode s = ok(GREEKS, "ALL");
        assertThat(s.get("groups")).isEmpty();
        assertThat(portfolio(s)).isEqualTo("0.00 0.00 0.00 | 0 0 0 | null");
    }

    // ---------------------------------------------------------------- AC 10: backend rules

    @Test
    void ac10_newEmptyAccountChangesNothing_renameIsReflected() throws Exception {
        scenarioG();
        JsonNode summary = ok(SUMMARY, "ALL");
        JsonNode greeks = ok(GREEKS, "ALL");
        assertThat(newAccount("丙账户")).isEqualTo(3);
        assertThat(ok(SUMMARY, "ALL")).isEqualTo(summary);
        assertThat(ok(GREEKS, "ALL")).isEqualTo(greeks);

        rename(2, "乙新名");
        assertThat(ok(SUMMARY, "ALL").get("groups").get(1).get("accountName").asText()).isEqualTo("乙新名");
    }

    @Test
    void ac10_onlyOneAccount_allEqualsThatAccount() throws Exception {
        accountA();
        fetch();
        JsonNode one = ok(SUMMARY, "1");
        assertThat(ok(SUMMARY, "ALL")).isEqualTo(one);
        assertThat(groups(one)).containsExactly(A_ZZA, A_ZZB);
        assertThat(ok(GREEKS, "ALL")).isEqualTo(ok(GREEKS, "1"));
    }

    @Test
    void ac10_noLongerNotSupported_writesStillRejected_errorsUnchanged() throws Exception {
        scenarioG();
        call(get(SUMMARY), "all ", null, 200);
        call(get(GREEKS), " All", null, 200);
        assertThat(call(get("/api/audit-log"), "ALL", null, 400).get("code").asText())
                .isEqualTo("ALL_ACCOUNTS_NOT_SUPPORTED");
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "ZZA");
        n.put("expirationDate", "2026-12-18");
        n.put("strike", "55");
        n.put("optionType", "CALL");
        n.put("action", "BTC");
        n.put("contracts", 1);
        n.put("netAmount", "60.65");
        n.put("tradedAtLocal", "2026-11-23T10:00");
        for (String url : List.of("/api/option-trades", "/api/option-trades/closes")) {
            assertThat(call(post(url), "ALL", n, 400).get("code").asText()).as(url)
                    .isEqualTo("ALL_ACCOUNTS_READ_ONLY");
        }
        for (String url : List.of(SUMMARY, GREEKS)) {
            assertThat(call(get(url), null, null, 400).get("code").asText()).isEqualTo("ACCOUNT_REQUIRED");
            assertThat(call(get(url), "abc", null, 400).get("code").asText()).isEqualTo("BAD_REQUEST");
            assertThat(call(get(url), "999", null, 404).get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(call(post(url), "ALL", null, 405).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        }
    }

    // ---------------------------------------------------------------- helpers

    static String text(JsonNode n) {
        return n == null || n.isNull() ? "null" : n.asText();
    }

    static List<String> groups(JsonNode s) {
        List<String> out = new ArrayList<>();
        for (JsonNode g : s.get("groups")) {
            JsonNode r = g.get("realized");
            out.add(g.get("accountId").asLong() + " " + g.get("accountName").asText() + " " + g.get("symbol").asText()
                    + " | " + r.get("closedCount").asInt() + " " + text(r.get("realizedPnl"))
                    + " " + r.at("/option/closedCount").asInt() + " " + text(r.at("/option/realizedPnl"))
                    + " " + r.at("/stock/closedCount").asInt() + " " + text(r.at("/stock/realizedPnl"))
                    + " | " + text(g.get("marketValue")) + " " + text(g.get("unrealizedPnl")) + " "
                    + text(g.get("totalPnl")) + " " + text(g.get("netPremium"))
                    + " | " + g.get("optionPositionCount").asInt() + " " + g.get("hasStockPosition").asBoolean() + " "
                    + g.get("includedCount").asInt() + " " + g.get("noQuoteCount").asInt() + " "
                    + g.get("expiredPendingCount").asInt() + " " + text(g.get("excludedLabel")));
        }
        return out;
    }

    static List<String> greeks(JsonNode s) {
        List<String> out = new ArrayList<>();
        for (JsonNode g : s.get("groups")) {
            out.add(g.get("symbol").asText() + " " + text(g.get("optionDelta")) + " " + text(g.get("stockDelta")) + " "
                    + text(g.get("netDelta")) + " " + text(g.get("gamma")) + " " + text(g.get("theta")) + " "
                    + text(g.get("vega")) + " | " + text(g.get("underlyingPrice")) + " "
                    + g.get("underlyingPriceManual").asBoolean() + " " + text(g.get("dollarExposure")) + " "
                    + text(g.get("exposureStatus")) + " " + text(g.get("manualPriceNote"))
                    + " | " + g.get("optionRowCount").asInt() + " " + g.get("includedCount").asInt() + " "
                    + g.get("noQuoteCount").asInt() + " " + g.get("expiredPendingCount").asInt() + " "
                    + text(g.get("excludedLabel")));
        }
        return out;
    }

    static String portfolio(JsonNode s) {
        JsonNode p = s.get("portfolio");
        return text(p.get("dollarExposure")) + " " + text(p.get("theta")) + " " + text(p.get("vega")) + " | "
                + p.get("noQuoteCount").asInt() + " " + p.get("expiredPendingCount").asInt() + " "
                + p.get("noUnderlyingPriceCount").asInt() + " | " + text(p.get("excludedLabel"));
    }

    void at(String nyLocal) {
        clock.set(UnrealizedPnlControllerTest.ny(nyLocal));
    }

    JsonNode ok(String url, String account) throws Exception {
        return call(get(url), account, null, 200);
    }

    JsonNode call(MockHttpServletRequestBuilder req, String account, ObjectNode body, int expected) throws Exception {
        if (account != null) {
            req.header(AccountRef.HEADER, account);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expected);
        return raw.isEmpty() ? null : json.readTree(raw);
    }

    long newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        return call(post("/api/accounts"), null, n, 201).get("id").asLong();
    }

    void rename(long id, String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        call(put("/api/accounts/" + id), null, n, 200);
    }

    void deposit(long account, String amount) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", "2026-09-30T09:00");
        call(post("/api/cash/transfers"), String.valueOf(account), n, 201);
    }

    void transferIn(long account, String symbol, int shares, String amount, String date) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradeDate", date);
        call(post("/api/stock-trades"), String.valueOf(account), n, 201);
    }

    void trade(long account, String path, String action, String symbol, String exp, String strike, String type,
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
        call(post("/api/option-trades" + path), String.valueOf(account), n, 201);
    }

    void expire(long account, String symbol, String exp, String strike, String type) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", 1);
        call(post("/api/option-trades/expirations"), String.valueOf(account), n, 201);
    }
}
