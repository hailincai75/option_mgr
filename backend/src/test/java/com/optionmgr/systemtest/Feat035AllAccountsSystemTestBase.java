package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared helpers of the QA system tests for FEAT-035 全部账户合并查看（一）(contract docs/api/FEAT-035.md:
 * {@code X-Account-Id: ALL} on 7 GETs, {@code accountId} / {@code accountName} on position rows, {@code accounts[]} on
 * the cash summary, {@code byAccount[]} on the ad-hoc closure alerts, and the guard errors
 * {@code ALL_ACCOUNTS_READ_ONLY} / {@code ALL_ACCOUNTS_NOT_SUPPORTED}).
 *
 * <p>Reuses the FEAT-016 QA base (profiles {@code test} + {@code e2e}, random port — never 8080, in-memory H2, fake
 * CBOE / Finnhub, settable clock, {@code POST /api/test-support/reset} before / after every test). Never the real
 * CBOE / Finnhub.
 *
 * <p>Requirement scenario S (today 2026-11-23 Mon, the 10:00 pull succeeds; fictional account names and tickers;
 * multiplier 100):
 * <ul>
 *   <li>甲账户 (id 1, the default account renamed): deposit 50,000.00; 转入 IXQZ 100 shares total cost 5,880.00 (price
 *       61.10); A1 = STO 1 QRTV 2026-12-18 50P net 118.70 (mark 1.25); STO 1 QRTV 2026-12-18 48P net 100.00, BTC
 *       11-10 net 40.00 (realized +60.00); A4 = STO 1 IXQZ 2026-11-27 65C net 84.35 (mark 0.85, covered by the 100
 *       shares).</li>
 *   <li>乙账户 (id 2): deposit 20,000.00; 10-15 BTO 1 QRTV 2026-12-18 55C net 50.00, 10-20 STC net 80.00 (realized
 *       +30.00); B3 = STO 1 QRTV 2026-12-18 50P net 120.00 (same contract as A1); B4 = STO 1 QRTV 2026-11-20 45P net
 *       60.00 (expired 3 days, unprocessed).</li>
 * </ul>
 * Single-account cash: 甲 50,263.05 / 5,000.00 / 45,263.05; 乙 20,210.00 / 9,500.00 / 10,710.00.
 *
 * <p>AC 8 ordering (contract "需求问题 3", Q126): A1 / B3 are entered <b>before</b> the fake Finnhub fetch that
 * introduces the 2026-12-18 ad-hoc closure, otherwise their opening would be rejected with 422
 * EXPIRATION_NOT_TRADING_DAY.
 */
abstract class Feat035AllAccountsSystemTestBase extends Feat016QuoteSystemTestBase {

    static final String ALL = "ALL";
    static final String A = "1";
    static final String B = "2";
    static final String A_NAME = "甲账户";
    static final String B_NAME = "乙账户";

    static final String DASHBOARD = "/api/dashboard";
    static final String CASH_SUMMARY = "/api/cash/summary";
    static final String UNREALIZED = "/api/positions/unrealized-summary";
    static final String PNL = "/api/pnl-summary";
    static final String ALERTS = "/api/adhoc-closure-alerts";
    static final String TRADES = "/api/option-trades";
    static final String STOCK_TRADES = "/api/stock-trades";
    static final String TRANSFERS = "/api/cash/transfers";
    static final String CAL_FETCH = "/api/test-support/trading-calendar/fetch";
    static final String LEGACY = "/api/test-support/legacy-option-trades";

    /** The 7 GETs that accept {@code ALL} (contract table). */
    static final List<String> ALL_READS = List.of(DASHBOARD, CASH_SUMMARY, OPTION_POSITIONS, STOCK_POSITIONS,
            UNREALIZED, PNL, ALERTS);

    static final String L_A1 = "QRTV 2026-12-18 50 P";
    static final String L_B3 = L_A1;
    static final String L_A4 = "IXQZ 2026-11-27 65 C";
    static final String L_B4 = "QRTV 2026-11-20 45 P";
    static final String L_48P = "QRTV 2026-12-18 48 P";
    static final String L_55C = "QRTV 2026-12-18 55 C";

    static final String OCC_A1 = "QRTV261218P00050000";
    static final String OCC_A4 = "IXQZ261127C00065000";

    static final String READ_ONLY_CODE = "ALL_ACCOUNTS_READ_ONLY";
    static final String READ_ONLY_MESSAGE = "全部账户模式只读：录入、修改、删除请先选择一个账户";
    static final String NOT_SUPPORTED_CODE = "ALL_ACCOUNTS_NOT_SUPPORTED";
    static final String NOT_SUPPORTED_MESSAGE = "此接口暂不支持全部账户，请选择一个账户";
    static final String GUARD_DETAIL = "X-Account-Id=请选择一个账户";

    static final String CLOSURE_NAME = "Test Closure";

    @Autowired
    JdbcTemplate jdbc;

    /** Ids of the scenario's records by label (A1, A4, A48P, A48P_BTC, B3, B4, B55C, B55C_STC, A_IXQZ, A_DEP, B_DEP). */
    final Map<String, Long> ids = new LinkedHashMap<>();

    // =========================================================================================== data

    long depositId(String amount, String at) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", at);
        n.putNull("remark");
        Resp r = post(TRANSFERS, n.toString());
        assertThat(r.status()).as("deposit %s -> %s", amount, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long transferInId(String symbol, int shares, String amount, String tradeDate) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradeDate", tradeDate);
        n.putNull("note");
        Resp r = post(STOCK_TRADES, n.toString());
        assertThat(r.status()).as("TRANSFER_IN %s -> %s", symbol, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long sellSharesId(String symbol, int shares, String amount, String tradedAtLocal) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "SELL");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", tradedAtLocal);
        n.putNull("note");
        Resp r = post(STOCK_TRADES, n.toString());
        assertThat(r.status()).as("SELL %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long closeId(String action, String symbol, String expiration, String strike, String type, String net, String at) {
        ObjectNode body = trade(action, 1, symbol, expiration, strike, type, net, at);
        body.remove("multiplier");
        Resp r = post(TRADES + "/closes", body.toString());
        assertThat(r.status()).as("POST closes %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void renameAccount(String id, String name) {
        Resp r = noAccount(() -> exchange(HttpMethod.PUT, "/api/accounts/" + id,
                "{\"name\":\"" + name + "\",\"brokerNote\":null}"));
        assertThat(r.status()).as("rename account %s -> %s", id, r.body()).isEqualTo(200);
    }

    /**
     * Scenario S, data only (no sources, no pull). Clock left at 2026-11-23 09:50 New York, API account 甲 ("1").
     */
    void scenarioData() {
        setNow("2026-11-23T09:50");
        renameAccount(A, A_NAME);
        accountId = A;
        ids.put("A_DEP", depositId("50000.00", "2026-10-01T10:00"));
        ids.put("A48P", open("STO", "QRTV", "2026-12-18", "48", "PUT", "100.00", "2026-11-05T10:00"));
        ids.put("A48P_BTC", closeId("BTC", "QRTV", "2026-12-18", "48", "PUT", "40.00", "2026-11-10T10:00"));
        ids.put("A_IXQZ", transferInId("IXQZ", 100, "5880.00", "2026-11-19"));
        ids.put("A1", open("STO", "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-20T10:00"));
        ids.put("A4", open("STO", "IXQZ", "2026-11-27", "65", "CALL", "84.35", "2026-11-20T10:10"));

        String b = createAccount(B_NAME);
        assertThat(b).as("乙账户 gets id 2 after the reset").isEqualTo(B);
        accountB = b;
        accountId = B;
        ids.put("B_DEP", depositId("20000.00", "2026-10-01T10:00"));
        ids.put("B55C", open("BTO", "QRTV", "2026-12-18", "55", "CALL", "50.00", "2026-10-15T10:00"));
        ids.put("B55C_STC", closeId("STC", "QRTV", "2026-12-18", "55", "CALL", "80.00", "2026-10-20T10:00"));
        ids.put("B4", open("STO", "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40"));
        ids.put("B3", open("STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-20T10:20"));
        accountId = A;
    }

    /** QRTV chain: A1 / B3 contract bid 1.20 / ask 1.30 (mark 1.25) with Greeks; underlying 52.40. */
    ObjectNode qrtvChain(ObjectNode... extra) {
        List<ObjectNode> opts = new ArrayList<>();
        opts.add(option(OCC_A1, "bid", "1.20", "ask", "1.30", "theo", "1.24", "iv", "0.3512", "delta", "-0.3021",
                "gamma", "0.0512", "theta", "-0.0456", "vega", "0.0711", "rho", "-0.0123"));
        opts.addAll(List.of(extra));
        return ok(cboeBody("QRTV", "52.40", "2026-11-23 09:45:00", opts));
    }

    /** IXQZ chain: A4 contract bid 0.80 / ask 0.90 (mark 0.85); underlying 61.10. */
    ObjectNode ixqzChain() {
        return ok(cboeBody("IXQZ", "61.10", "2026-11-23 09:45:00",
                List.of(option(OCC_A4, "bid", "0.80", "ask", "0.90", "theo", "0.84", "iv", "0.2810", "delta",
                        "0.2200", "gamma", "0.0610", "theta", "-0.0390", "vega", "0.0310", "rho", "0.0040"))));
    }

    void scenarioSources() {
        sources().cboe("QRTV", qrtvChain()).cboe("IXQZ", ixqzChain()).apply();
    }

    /** Scenario S + sources + the 10:00 pull; clock left at 2026-11-23 10:00, API account 甲. */
    void scenarioS() {
        scenarioData();
        pull();
    }

    void pull() {
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    // ------------------------------------------------------------------ fake Finnhub (AC 8)

    /** NYSE 2026–2027 full-day closures / early closes as published, plus extra full-day closures. */
    ObjectNode finnhubCalendar(String... extraClosedDateNamePairs) {
        String[][] rows = {
                {"New Year's Day", "2026-01-01", ""}, {"Martin Luther King Jr. Day", "2026-01-19", ""},
                {"Washington's Birthday", "2026-02-16", ""}, {"Good Friday", "2026-04-03", ""},
                {"Memorial Day", "2026-05-25", ""}, {"Juneteenth", "2026-06-19", ""},
                {"Independence Day", "2026-07-03", ""}, {"Labor Day", "2026-09-07", ""},
                {"Thanksgiving Day", "2026-11-26", ""}, {"Thanksgiving Day", "2026-11-27", "09:30-13:00"},
                {"Christmas", "2026-12-24", "09:30-13:00"}, {"Christmas", "2026-12-25", ""},
                {"New Year's Day", "2027-01-01", ""}, {"Martin Luther King Jr. Day", "2027-01-18", ""},
                {"Washington's Birthday", "2027-02-15", ""}, {"Good Friday", "2027-03-26", ""},
                {"Memorial Day", "2027-05-31", ""}, {"Juneteenth", "2027-06-18", ""},
                {"Independence Day", "2027-07-05", ""}, {"Labor Day", "2027-09-06", ""},
                {"Thanksgiving Day", "2027-11-25", ""}, {"Thanksgiving Day", "2027-11-26", "09:30-13:00"},
                {"Christmas", "2027-12-24", ""}};
        ObjectNode body = json.createObjectNode();
        ArrayNode data = body.putArray("data");
        for (String[] r : rows) {
            ObjectNode o = data.addObject();
            o.put("eventName", r[0]);
            o.put("atDate", r[1]);
            o.put("tradingHour", r[2]);
            o.put("postMarket", "");
        }
        for (int i = 0; i < extraClosedDateNamePairs.length; i += 2) {
            ObjectNode o = data.addObject();
            o.put("eventName", extraClosedDateNamePairs[i + 1]);
            o.put("atDate", extraClosedDateNamePairs[i]);
            o.put("tradingHour", "");
            o.put("postMarket", "");
        }
        body.put("exchange", "US");
        body.put("timezone", "America/New_York");
        return body;
    }

    /** Fake successful Finnhub fetch (with ad-hoc closure detection) at the current clock. */
    void fetchCalendar(ObjectNode finnhubBody) {
        ObjectNode req = json.createObjectNode();
        req.put("httpStatus", 200);
        req.set("body", finnhubBody);
        Resp r = noAccount(() -> post(CAL_FETCH, req.toString()));
        assertThat(r.status()).as("POST %s -> %s", CAL_FETCH, r.body()).isEqualTo(200);
        assertThat(r.body().get("lastAttemptResult").asText()).isEqualTo("SUCCESS");
    }

    /** Records 2026-12-18 "Test Closure" as an ACTIVE ad-hoc closure (call only after A1 / B3 were entered). */
    void injectDec18Closure() {
        fetchCalendar(finnhubCalendar("2026-12-18", CLOSURE_NAME));
        Resp c = noAccount(() -> get("/api/trading-calendar/adhoc-closures"));
        assertThat(c.status()).isEqualTo(200);
        boolean found = false;
        for (JsonNode x : c.body().get("closures")) {
            if (x.get("date").asText().equals("2026-12-18")) {
                assertThat(x.get("status").asText()).isEqualTo("ACTIVE");
                assertThat(x.get("eventName").asText()).isEqualTo(CLOSURE_NAME);
                found = true;
            }
        }
        assertThat(found).as("2026-12-18 recorded as ad-hoc closure").isTrue();
    }

    // =========================================================================================== reading

    Resp getAs(String account, String path) {
        String saved = accountId;
        accountId = account;
        try {
            return get(path);
        } finally {
            accountId = saved;
        }
    }

    JsonNode okAs(String account, String path) {
        Resp r = getAs(account, path);
        assertThat(r.status()).as("GET %s (X-Account-Id %s) -> %s", path, account, r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new AssertionError("bad JSON in test: " + text, e);
        }
    }

    /** "marketValue unrealizedPnl unrealizedPnlPercent unrealizedStatus unrealizedLabel" of a row (FEAT-017). */
    static String val(JsonNode row) {
        return text(row.get("marketValue")) + " " + text(row.get("unrealizedPnl")) + " "
                + text(row.get("unrealizedPnlPercent")) + " " + row.get("unrealizedStatus").asText() + " "
                + text(row.get("unrealizedLabel"));
    }

    /** "accountId accountName contractLabel" of every option row in response order. */
    static List<String> optionOrder(JsonNode positions) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : positions.get("positions")) {
            out.add(p.get("accountId").asLong() + " " + p.get("accountName").asText() + " "
                    + p.get("contractLabel").asText());
        }
        return out;
    }

    /** "accountId accountName symbol shares" of every stock row in response order. */
    static List<String> stockOrder(JsonNode positions) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : positions.get("positions")) {
            out.add(p.get("accountId").asLong() + " " + p.get("accountName").asText() + " "
                    + p.get("symbol").asText() + " " + p.get("shares").asInt());
        }
        return out;
    }

    /** The option row of {@code account} (accountId) with this contractLabel in a (possibly ALL) response. */
    static JsonNode optRowOf(JsonNode positions, long accountId, String contractLabel) {
        for (JsonNode p : positions.get("positions")) {
            if (p.get("accountId").asLong() == accountId && p.get("contractLabel").asText().equals(contractLabel)) {
                return p;
            }
        }
        throw new AssertionError("no row " + accountId + " " + contractLabel + " in " + positions);
    }

    static JsonNode stockRowOf(JsonNode positions, long accountId, String symbol) {
        for (JsonNode p : positions.get("positions")) {
            if (p.get("accountId").asLong() == accountId && p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        throw new AssertionError("no stock row " + accountId + " " + symbol + " in " + positions);
    }

    /**
     * Q53 / contract "计算不变": every row of the ALL response equals (all fields, all values) the row of the same
     * account and key in the single-account response, and the ALL rows are exactly the union of the single-account
     * rows.
     */
    void assertAllRowsEqualSingleRows(String path, String keyField, List<String> accounts) {
        JsonNode all = okAs(ALL, path);
        int total = 0;
        for (String acc : accounts) {
            JsonNode single = okAs(acc, path);
            for (JsonNode row : single.get("positions")) {
                total++;
                assertThat(row.get("accountId").asLong()).as("single-account row carries its own account id")
                        .isEqualTo(Long.parseLong(acc));
                JsonNode match = null;
                for (JsonNode r : all.get("positions")) {
                    if (r.get("accountId").asLong() == Long.parseLong(acc)
                            && r.get(keyField).asText().equals(row.get(keyField).asText())) {
                        match = r;
                    }
                }
                assertThat(match).as("%s row %s/%s present in ALL", path, acc, row.get(keyField)).isNotNull();
                assertThat(match).as("%s ALL row = single-account row (Q53) for %s/%s", path, acc,
                        row.get(keyField)).isEqualTo(row);
            }
        }
        assertThat(all.get("positions").size()).as("ALL rows = union of the single-account rows").isEqualTo(total);
        if (path.equals(STOCK_POSITIONS)) {
            // Q227: ALL stock positionCount = number of distinct symbols across accounts (rows stay per account)
            assertThat(all.get("positionCount").asInt()).as("stock positionCount = distinct symbols (Q227)")
                    .isEqualTo(distinctSymbols(all));
        } else {
            assertThat(all.get("positionCount").asInt()).as("option positionCount = rows (Q215)").isEqualTo(total);
        }
    }

    /** Number of distinct symbols among the stock rows of a response. */
    static int distinctSymbols(JsonNode stockPositions) {
        java.util.Set<String> s = new java.util.HashSet<>();
        stockPositions.get("positions").forEach(p -> s.add(p.get("symbol").asText()));
        return s.size();
    }

    static final Pattern MONEY = Pattern.compile("-?\\d+\\.\\d{2}");

    /**
     * Contract Q216: {@code all} = field-wise sum of {@code parts} — scale-2 money strings and integer counts are
     * summed exactly; any other value must be equal in every part and in {@code all}. Arrays are not handled here.
     */
    void assertFieldwiseSum(String what, JsonNode all, JsonNode... parts) {
        Iterator<Map.Entry<String, JsonNode>> it = all.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String f = e.getKey();
            JsonNode v = e.getValue();
            String at = what + "." + f;
            if (v.isObject()) {
                JsonNode[] sub = new JsonNode[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    sub[i] = parts[i].get(f);
                }
                assertFieldwiseSum(at, v, sub);
            } else if (v.isIntegralNumber()) {
                long sum = 0;
                for (JsonNode p : parts) {
                    sum += p.get(f).asLong();
                }
                assertThat(v.asLong()).as("%s = sum of accounts", at).isEqualTo(sum);
            } else if (v.isTextual() && MONEY.matcher(v.asText()).matches()) {
                BigDecimal sum = new BigDecimal("0.00");
                for (JsonNode p : parts) {
                    sum = sum.add(new BigDecimal(p.get(f).asText()));
                }
                assertThat(v.asText()).as("%s = exact sum of accounts (scale 2)", at).isEqualTo(sum.toPlainString());
            } else {
                for (JsonNode p : parts) {
                    assertThat(v).as("%s same as in each account", at).isEqualTo(p.get(f));
                }
            }
        }
    }

    // =========================================================================================== guard

    /** Exact guard error of the contract: 400, code, Chinese message, details [{X-Account-Id, 请选择一个账户}]. */
    void assertGuardError(Resp r, String code, String path) {
        assertErrorShape(r, 400, code, path);
        assertThat(r.body().get("message").asText())
                .isEqualTo(code.equals(READ_ONLY_CODE) ? READ_ONLY_MESSAGE : NOT_SUPPORTED_MESSAGE);
        assertThat(details(r)).as("details of %s %s", code, path).containsExactly(GUARD_DETAIL);
    }

    long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Everything observable: row counts of all business tables and the 7 read endpoints as seen by 甲, 乙 and ALL.
     * Must be identical before and after a rejected write.
     */
    Map<String, Object> world() {
        Map<String, Object> w = new LinkedHashMap<>();
        for (String t : List.of("account", "cash_transfer", "cash_ledger_entry", "option_trade", "stock_trade",
                "audit_record")) {
            w.put(t, count(t));
        }
        for (String acc : List.of(A, B, ALL)) {
            for (String p : ALL_READS) {
                w.put(acc + " " + p, okAs(acc, p));
            }
        }
        for (String acc : List.of(A, B)) {
            w.put(acc + " ledger", okAs(acc, "/api/cash/ledger?size=100"));
            w.put(acc + " trades", okAs(acc, TRADES + "?size=100"));
            w.put(acc + " stock-trades", okAs(acc, STOCK_TRADES + "?size=100"));
            w.put(acc + " audit", okAs(acc, "/api/audit-log?size=100"));
        }
        return w;
    }
}
