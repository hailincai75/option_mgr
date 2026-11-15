package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpMethod;

/**
 * Shared helpers of the QA system tests for FEAT-047 全部账户合并查看（三）(contract docs/api/FEAT-047.md):
 * {@code GET /api/positions/underlying-summary} and {@code GET /api/positions/greeks-summary} accept
 * {@code X-Account-Id: ALL}. The summary has one group per account + symbol (Q224 A) with new {@code accountId} /
 * {@code accountName} (single-account requests too); the Greeks summary merges each symbol across accounts (Q224 B,
 * structure unchanged, no account fields, Q234 A), merged exposure = merged net Delta × price HALF_UP 2 (Q233 A).
 *
 * <p>Reuses the FEAT-035 / 046 QA bases (profiles {@code test} + {@code e2e}, random port — never 8080, in-memory H2,
 * fake CBOE / Finnhub only, settable clock, reset before / after every test; fictional account names and tickers).
 *
 * <p>Requirement scenario G (today 2026-11-23 Mon, the 10:00 pull succeeds; multiplier 100; ZZA 50.00, ZZB 40.00):
 * <pre>
 *  甲账户 (id 1)  deposit 50,000.00 (10-01)
 *    S1 转入 ZZA 100 shares, total cost 4,800.00 (2026-10-01)
 *    H1 STO 1 ZZA 2026-11-20 52C net 60.35 (10-20) / H2 BTC net 20.65 (10-30) → +39.70
 *    P1 STO 1 ZZA 2026-12-18 55C net 85.35 (11-02)   mark 0.60  Δ 0.2000 Γ 0.0400 Θ −0.0300 V 0.0600
 *    P2 STO 1 ZZB 2026-12-18 38P net 120.00 (11-05)  mark 1.10  Δ −0.3000 Γ 0.0500 Θ −0.0400 V 0.0450
 *  乙账户 (id 2)  deposit 20,000.00 (10-01)
 *    H3 STO 1 ZZA 2026-11-13 47P net 80.00 (10-27), expired worthless → +80.00
 *    P3 STO 2 ZZA 2026-12-18 45P net 230.00 (11-05)  mark 1.10  Δ −0.1500 Γ 0.0300 Θ −0.0250 V 0.0500
 *    P4 STO 1 ZZB 2026-11-20 36P net 50.00 (11-02), expired, not processed
 * </pre>
 */
abstract class Feat047AllAccountsStrategyGreeksSystemTestBase extends Feat046AllAccountsListsSystemTestBase {

    static final String UNDERLYING = "/api/positions/underlying-summary";
    static final String GREEKS = "/api/positions/greeks-summary";

    static final List<String> U_TOP_FIELDS = List.of("asOfDate", "groups");
    static final List<String> U_GROUP_FIELDS = List.of("accountId", "accountName", "symbol", "realized",
            "marketValue", "unrealizedPnl", "totalPnl", "netPremium", "optionPositionCount", "hasStockPosition",
            "includedCount", "noQuoteCount", "expiredPendingCount", "excludedLabel");

    static final List<String> G_TOP_FIELDS = List.of("asOfDate", "groups", "portfolio");
    /** FEAT-019 group fields, unchanged by FEAT-047 (no account fields, Q234 A). */
    static final List<String> G_GROUP_FIELDS = List.of("symbol", "optionDelta", "stockDelta", "netDelta", "gamma",
            "theta", "vega", "underlyingPrice", "underlyingPriceManual", "underlyingPriceLabel", "dollarExposure",
            "exposureStatus", "exposureLabel", "manualPriceNote", "optionRowCount", "includedCount", "noQuoteCount",
            "expiredPendingCount", "deltaMissingCount", "gammaMissingCount", "thetaMissingCount", "vegaMissingCount",
            "excludedLabel", "missingLabel");
    static final List<String> G_PORTFOLIO_FIELDS = List.of("dollarExposure", "theta", "vega", "noQuoteCount",
            "expiredPendingCount", "noUnderlyingPriceCount", "deltaMissingCount", "thetaMissingCount",
            "vegaMissingCount", "excludedLabel", "noUnderlyingPriceLabel", "missingLabel");

    static final String LABEL_1_EXPIRED = "1 个持仓已到期待处理，未计入";
    static final String MANUAL_PRICE_NOTE = "按手动标的价";
    static final String NO_PRICE = "无标的价";

    static final String C_P1 = "ZZA 2026-12-18 55 C";
    static final String C_P2 = "ZZB 2026-12-18 38 P";
    static final String C_P3 = "ZZA 2026-12-18 45 P";
    static final String C_P4 = "ZZB 2026-11-20 36 P";
    static final String C_H1 = "ZZA 2026-11-20 52 C";
    static final String C_H3 = "ZZA 2026-11-13 47 P";

    static final String OCC_P1 = "ZZA261218C00055000";
    static final String OCC_P2 = "ZZB261218P00038000";
    static final String OCC_P3 = "ZZA261218P00045000";

    // ---- expected summary group lines (format: see ugLine) — requirement AC 1 / 2, contract example
    static final String U_A_ZZA = "1 甲账户 ZZA | 1 39.70 (1 39.70 · 0 0.00) | mv 4940.00 | upnl 225.35 | total 265.05"
            + " | prem 85.35 | opt 1 stock true | 2/0/0 | null";
    static final String U_B_ZZA = "2 乙账户 ZZA | 1 80.00 (1 80.00 · 0 0.00) | mv -220.00 | upnl 10.00 | total 90.00"
            + " | prem 230.00 | opt 1 stock false | 1/0/0 | null";
    static final String U_A_ZZB = "1 甲账户 ZZB | 0 0.00 (0 0.00 · 0 0.00) | mv -110.00 | upnl 10.00 | total 10.00"
            + " | prem 120.00 | opt 1 stock false | 1/0/0 | null";
    static final String U_B_ZZB = "2 乙账户 ZZB | 0 0.00 (0 0.00 · 0 0.00) | mv null | upnl null | total 0.00"
            + " | prem 50.00 | opt 1 stock false | 0/0/1 | " + LABEL_1_EXPIRED;
    /** AC 8: 甲 ZZA after BTC 1 P1 net 60.65. */
    static final String U_A_ZZA_AFTER_BTC = "1 甲账户 ZZA | 2 64.40 (2 64.40 · 0 0.00) | mv 5000.00 | upnl 200.00"
            + " | total 264.40 | prem null | opt 0 stock true | 1/0/0 | null";

    /** The contract's {@code ALL} underlying-summary example (AC 1, 2), verbatim. */
    static final String U_ALL_JSON = """
            {
              "asOfDate": "2026-11-23",
              "groups": [
                { "accountId": 1, "accountName": "甲账户", "symbol": "ZZA",
                  "realized": { "closedCount": 1, "realizedPnl": "39.70",
                    "option": { "closedCount": 1, "realizedPnl": "39.70" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
                  "marketValue": "4940.00", "unrealizedPnl": "225.35", "totalPnl": "265.05", "netPremium": "85.35",
                  "optionPositionCount": 1, "hasStockPosition": true,
                  "includedCount": 2, "noQuoteCount": 0, "expiredPendingCount": 0, "excludedLabel": null },
                { "accountId": 2, "accountName": "乙账户", "symbol": "ZZA",
                  "realized": { "closedCount": 1, "realizedPnl": "80.00",
                    "option": { "closedCount": 1, "realizedPnl": "80.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
                  "marketValue": "-220.00", "unrealizedPnl": "10.00", "totalPnl": "90.00", "netPremium": "230.00",
                  "optionPositionCount": 1, "hasStockPosition": false,
                  "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0, "excludedLabel": null },
                { "accountId": 1, "accountName": "甲账户", "symbol": "ZZB",
                  "realized": { "closedCount": 0, "realizedPnl": "0.00",
                    "option": { "closedCount": 0, "realizedPnl": "0.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
                  "marketValue": "-110.00", "unrealizedPnl": "10.00", "totalPnl": "10.00", "netPremium": "120.00",
                  "optionPositionCount": 1, "hasStockPosition": false,
                  "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0, "excludedLabel": null },
                { "accountId": 2, "accountName": "乙账户", "symbol": "ZZB",
                  "realized": { "closedCount": 0, "realizedPnl": "0.00",
                    "option": { "closedCount": 0, "realizedPnl": "0.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
                  "marketValue": null, "unrealizedPnl": null, "totalPnl": "0.00", "netPremium": "50.00",
                  "optionPositionCount": 1, "hasStockPosition": false,
                  "includedCount": 0, "noQuoteCount": 0, "expiredPendingCount": 1,
                  "excludedLabel": "1 个持仓已到期待处理，未计入" }
              ]
            }
            """;

    /** The contract's {@code ALL} greeks-summary example (AC 5, 6), verbatim. */
    static final String G_ALL_JSON = """
            {
              "asOfDate": "2026-11-23",
              "groups": [
                { "symbol": "ZZA", "optionDelta": "10.00", "stockDelta": "100.00", "netDelta": "110.00",
                  "gamma": "-10.00", "theta": "8.00", "vega": "-16.00",
                  "underlyingPrice": "50.00", "underlyingPriceManual": false, "underlyingPriceLabel": null,
                  "dollarExposure": "5500.00", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
                  "optionRowCount": 2, "includedCount": 2, "noQuoteCount": 0, "expiredPendingCount": 0,
                  "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                  "excludedLabel": null, "missingLabel": null },
                { "symbol": "ZZB", "optionDelta": "30.00", "stockDelta": "0.00", "netDelta": "30.00",
                  "gamma": "-5.00", "theta": "4.00", "vega": "-4.50",
                  "underlyingPrice": "40.00", "underlyingPriceManual": false, "underlyingPriceLabel": null,
                  "dollarExposure": "1200.00", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
                  "optionRowCount": 2, "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 1,
                  "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                  "excludedLabel": "1 个持仓已到期待处理，未计入", "missingLabel": null }
              ],
              "portfolio": {
                "dollarExposure": "6700.00", "theta": "12.00", "vega": "-20.50",
                "noQuoteCount": 0, "expiredPendingCount": 1, "noUnderlyingPriceCount": 0,
                "deltaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                "excludedLabel": "1 个持仓已到期待处理，未计入", "noUnderlyingPriceLabel": null, "missingLabel": null
              }
            }
            """;

    // =========================================================================================== data

    long openN(String action, int contracts, String symbol, String expiration, String strike, String type,
               String net, String at) {
        ObjectNode body = trade(action, contracts, symbol, expiration, strike, type, net, at);
        Resp r = post(TRADES, body.toString());
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    Resp closeResp(String action, int contracts, String symbol, String expiration, String strike, String type,
                   String net, String at) {
        ObjectNode body = trade(action, contracts, symbol, expiration, strike, type, net, at);
        body.remove("multiplier");
        return post(TRADES + "/closes", body.toString());
    }

    void closeN(String action, int contracts, String symbol, String expiration, String strike, String type,
                String net, String at) {
        Resp r = closeResp(action, contracts, symbol, expiration, strike, type, net, at);
        assertThat(r.status()).as("POST closes %s %s -> %s", action, symbol, r.body()).isEqualTo(201);
    }

    void expireWorthless(String symbol, String expiration, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        Resp r = post(TRADES + "/expirations", n.toString());
        assertThat(r.status()).as("POST expirations %s -> %s", n, r.body()).isEqualTo(201);
    }

    /**
     * Scenario G, data only (no sources, no pull). Clock left at 2026-11-23 09:50 New York, API account 甲 ("1").
     */
    void scenarioGData() {
        setNow("2026-11-23T09:50");
        renameAccount(A, A_NAME);
        String b = createAccount(B_NAME);
        assertThat(b).as("乙账户 gets id 2 after the reset").isEqualTo(B);
        accountB = b;

        accountId = A;
        depositId("50000.00", "2026-10-01T09:00");
        transferInId("ZZA", 100, "4800.00", "2026-10-01");                                   // S1
        openN("STO", 1, "ZZA", "2026-11-20", "52", "CALL", "60.35", "2026-10-20T10:00");     // H1
        closeN("BTC", 1, "ZZA", "2026-11-20", "52", "CALL", "20.65", "2026-10-30T10:00");    // H2
        openN("STO", 1, "ZZA", "2026-12-18", "55", "CALL", "85.35", "2026-11-02T10:00");     // P1
        openN("STO", 1, "ZZB", "2026-12-18", "38", "PUT", "120.00", "2026-11-05T10:00");     // P2

        accountId = B;
        depositId("20000.00", "2026-10-01T09:00");
        openN("STO", 1, "ZZA", "2026-11-13", "47", "PUT", "80.00", "2026-10-27T10:00");      // H3
        expireWorthless("ZZA", "2026-11-13", "47", "PUT", 1);                                // H3 作废
        openN("STO", 2, "ZZA", "2026-12-18", "45", "PUT", "230.00", "2026-11-05T10:00");     // P3
        openN("STO", 1, "ZZB", "2026-11-20", "36", "PUT", "50.00", "2026-11-02T10:00");      // P4 (expired, pending)
        accountId = A;
    }

    ObjectNode zzaOption(String occ, String bid, String ask, String delta, String gamma, String theta, String vega) {
        return option(occ, "bid", bid, "ask", ask, "theo", null, "iv", "0.3000", "delta", delta, "gamma", gamma,
                "theta", theta, "vega", vega, "rho", "0.0010");
    }

    /** Fake CBOE of scenario G: ZZA 50.00 (P1 mark 0.60, P3 mark 1.10), ZZB 40.00 (P2 mark 1.10). */
    Sources sourcesG() {
        return sources()
                .cboe("ZZA", ok(cboeBody("ZZA", "50.00", "2026-11-23 09:45:00", List.of(
                        zzaOption(OCC_P1, "0.55", "0.65", "0.2000", "0.0400", "-0.0300", "0.0600"),
                        zzaOption(OCC_P3, "1.05", "1.15", "-0.1500", "0.0300", "-0.0250", "0.0500")))))
                .cboe("ZZB", ok(cboeBody("ZZB", "40.00", "2026-11-23 09:45:00", List.of(
                        zzaOption(OCC_P2, "1.05", "1.15", "-0.3000", "0.0500", "-0.0400", "0.0450")))));
    }

    /** Scenario G + sources + the 10:00 pull; clock left at 2026-11-23 10:00, API account 甲. */
    void scenarioG() {
        scenarioGData();
        sourcesG().apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    /** PUT the manual underlying price with the given X-Account-Id (Q218: ALL works, not account scoped). */
    Resp putUnderlyingPriceAs(String account, String symbol, String price) {
        String saved = accountId;
        accountId = account;
        try {
            return exchange(HttpMethod.PUT, OVERRIDE_UNDERLYINGS + symbol, "{\"price\":\"" + price + "\"}");
        } finally {
            accountId = saved;
        }
    }

    Resp deleteUnderlyingPriceAs(String account, String symbol) {
        String saved = accountId;
        accountId = account;
        try {
            return exchange(HttpMethod.DELETE, OVERRIDE_UNDERLYINGS + symbol, null);
        } finally {
            accountId = saved;
        }
    }

    // =========================================================================================== reading

    /** GET the underlying summary with this scope; 200, contract shape asserted. */
    JsonNode underlyingOf(String scope) {
        JsonNode body = okAs(scope, UNDERLYING);
        assertUnderlyingShape(scope, body);
        return body;
    }

    /** GET the Greeks summary with this scope; 200, contract shape and recomputation from the positions asserted. */
    JsonNode greeksOf(String scope) {
        JsonNode body = okAs(scope, GREEKS);
        assertGreeksShape(body);
        assertGreeksMatchPositions(scope, body);
        return body;
    }

    static String part(JsonNode p) {
        return p.get("closedCount").asInt() + " " + p.get("realizedPnl").asText();
    }

    static String realizedLine(JsonNode r) {
        return part(r) + " (" + part(r.get("option")) + " · " + part(r.get("stock")) + ")";
    }

    /**
     * "accountId accountName SYMBOL | count pnl (optCount optPnl · stockCount stockPnl) | mv X | upnl X | total X |
     * prem X | opt N stock B | included/noQuote/expiredPending | excludedLabel".
     */
    static String ugLine(JsonNode g) {
        return g.get("accountId").asLong() + " " + g.get("accountName").asText() + " " + g.get("symbol").asText()
                + " | " + realizedLine(g.get("realized")) + " | mv " + text(g.get("marketValue")) + " | upnl "
                + text(g.get("unrealizedPnl")) + " | total " + text(g.get("totalPnl")) + " | prem "
                + text(g.get("netPremium")) + " | opt " + g.get("optionPositionCount").asInt() + " stock "
                + g.get("hasStockPosition").asBoolean() + " | " + g.get("includedCount").asInt() + "/"
                + g.get("noQuoteCount").asInt() + "/" + g.get("expiredPendingCount").asInt() + " | "
                + text(g.get("excludedLabel"));
    }

    static List<String> ugLines(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("groups").forEach(g -> out.add(ugLine(g)));
        return out;
    }

    /**
     * Greeks group line: "SYMBOL | opt stock net | price manual priceLabel | exposure status exposureLabel note |
     * gamma theta vega | rows included/noQuote/expired missing d/g/t/v | excludedLabel | missingLabel".
     */
    static String grLine(JsonNode g) {
        return g.get("symbol").asText() + " | " + text(g.get("optionDelta")) + " " + text(g.get("stockDelta")) + " "
                + text(g.get("netDelta")) + " | " + text(g.get("underlyingPrice")) + " "
                + g.get("underlyingPriceManual").asBoolean() + " " + text(g.get("underlyingPriceLabel")) + " | "
                + text(g.get("dollarExposure")) + " " + g.get("exposureStatus").asText() + " "
                + text(g.get("exposureLabel")) + " " + text(g.get("manualPriceNote")) + " | " + text(g.get("gamma"))
                + " " + text(g.get("theta")) + " " + text(g.get("vega")) + " | " + g.get("optionRowCount").asInt()
                + " " + g.get("includedCount").asInt() + "/" + g.get("noQuoteCount").asInt() + "/"
                + g.get("expiredPendingCount").asInt() + " missing " + g.get("deltaMissingCount").asInt() + "/"
                + g.get("gammaMissingCount").asInt() + "/" + g.get("thetaMissingCount").asInt() + "/"
                + g.get("vegaMissingCount").asInt() + " | " + text(g.get("excludedLabel")) + " | "
                + text(g.get("missingLabel"));
    }

    static JsonNode grGroup(JsonNode body, String symbol) {
        for (JsonNode g : body.get("groups")) {
            if (g.get("symbol").asText().equals(symbol)) {
                return g;
            }
        }
        throw new AssertionError("no Greeks group " + symbol + " in " + body);
    }

    static List<String> grSymbols(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("groups").forEach(g -> out.add(g.get("symbol").asText()));
        return out;
    }

    /** "exposure theta vega | noQuote/expired/noPrice missing d/t/v | excluded | noPriceLabel | missing". */
    static String pfLine(JsonNode body) {
        JsonNode p = body.get("portfolio");
        return p.get("dollarExposure").asText() + " " + p.get("theta").asText() + " " + p.get("vega").asText()
                + " | " + p.get("noQuoteCount").asInt() + "/" + p.get("expiredPendingCount").asInt() + "/"
                + p.get("noUnderlyingPriceCount").asInt() + " missing " + p.get("deltaMissingCount").asInt() + "/"
                + p.get("thetaMissingCount").asInt() + "/" + p.get("vegaMissingCount").asInt() + " | "
                + text(p.get("excludedLabel")) + " | " + text(p.get("noUnderlyingPriceLabel")) + " | "
                + text(p.get("missingLabel"));
    }

    // =========================================================================================== shape

    static void assertMoneyText(JsonNode n, String what, boolean nullable) {
        assertThat(n).as("%s present", what).isNotNull();
        if (n.isNull()) {
            assertThat(nullable).as("%s must not be null", what).isTrue();
            return;
        }
        assertThat(n.isTextual()).as("%s is a JSON string: %s", what, n).isTrue();
        String s = n.asText();
        assertThat(s).as("%s has no '+' / ','", what).doesNotContain("+").doesNotContain(",");
        assertThat(s).as("%s is not negative zero", what).isNotEqualTo("-0.00");
        assertThat(new BigDecimal(s).scale()).as("%s scale 2: %s", what, s).isEqualTo(2);
    }

    static String expectedExcludedLabel(int noQuote, int expired) {
        if (noQuote > 0 && expired > 0) {
            return noQuote + " 个持仓无报价、" + expired + " 个持仓已到期待处理，未计入";
        }
        if (noQuote > 0) {
            return noQuote + " 个持仓无报价，未计入";
        }
        if (expired > 0) {
            return expired + " 个持仓已到期待处理，未计入";
        }
        return null;
    }

    /**
     * Contract shape of the underlying summary for a scope: field sets (incl. accountId number / accountName string
     * on every group), FEAT-023 per-group invariants, order symbol → accountId (rule 3), (accountId, symbol) unique;
     * for a single-account scope every group carries that account.
     */
    void assertUnderlyingShape(String scope, JsonNode body) {
        assertThat(fieldNames(body)).as("top-level fields").containsExactlyInAnyOrderElementsOf(U_TOP_FIELDS);
        assertThat(body.get("asOfDate").isTextual()).isTrue();
        assertThat(body.get("groups").isArray()).as("groups never null").isTrue();
        List<String> keys = new ArrayList<>();
        String prevSymbol = null;
        long prevAccount = -1;
        for (JsonNode g : body.get("groups")) {
            String sym = g.get("symbol").asText();
            assertThat(fieldNames(g)).as("group %s fields", sym).containsExactlyInAnyOrderElementsOf(U_GROUP_FIELDS);
            assertThat(g.get("accountId").isIntegralNumber()).as("%s accountId is a number", sym).isTrue();
            assertThat(g.get("accountName").isTextual()).as("%s accountName is a string", sym).isTrue();
            long acc = g.get("accountId").asLong();
            if (!scope.trim().equalsIgnoreCase(ALL)) {
                assertThat(acc).as("single-account group carries that account").isEqualTo(Long.parseLong(scope));
            }
            keys.add(acc + "|" + sym);
            if (prevSymbol != null) {
                int c = prevSymbol.compareTo(sym);
                assertThat(c < 0 || (c == 0 && prevAccount < acc))
                        .as("groups ordered by symbol, then accountId (rule 3): %s/%d before %s/%d", prevSymbol,
                                prevAccount, sym, acc).isTrue();
            }
            prevSymbol = sym;
            prevAccount = acc;

            JsonNode r = g.get("realized");
            assertThat(r.isObject()).as("%s realized never null", sym).isTrue();
            assertMoneyText(r.get("realizedPnl"), sym + ".realized.realizedPnl", false);
            assertMoneyText(r.get("option").get("realizedPnl"), sym + ".realized.option", false);
            assertMoneyText(r.get("stock").get("realizedPnl"), sym + ".realized.stock", false);
            int inc = g.get("includedCount").asInt();
            int nq = g.get("noQuoteCount").asInt();
            int ep = g.get("expiredPendingCount").asInt();
            int opt = g.get("optionPositionCount").asInt();
            boolean stock = g.get("hasStockPosition").asBoolean();
            assertThat(inc + nq + ep).as("%s included + noQuote + expired = rows", sym).isEqualTo(opt + (stock ? 1 : 0));
            assertThat(opt + (stock ? 1 : 0)).as("%s/%d has a position (no card for history only)", sym, acc)
                    .isPositive();
            assertMoneyText(g.get("marketValue"), sym + ".marketValue", inc == 0);
            assertMoneyText(g.get("unrealizedPnl"), sym + ".unrealizedPnl", inc == 0);
            if (inc == 0) {
                assertThat(g.get("marketValue").isNull()).isTrue();
                assertThat(g.get("unrealizedPnl").isNull()).isTrue();
            }
            assertMoneyText(g.get("netPremium"), sym + ".netPremium", opt == 0);
            if (opt == 0) {
                assertThat(g.get("netPremium").isNull()).as("%s no option rows → netPremium null", sym).isTrue();
            }
            assertMoneyText(g.get("totalPnl"), sym + ".totalPnl", false);
            BigDecimal upnl = g.get("unrealizedPnl").isNull() ? BigDecimal.ZERO : money(g.get("unrealizedPnl"));
            assertThat(money(g.get("totalPnl"))).as("%s totalPnl = realized + (unrealized ?? 0)", sym)
                    .isEqualByComparingTo(money(r.get("realizedPnl")).add(upnl));
            assertThat(text(g.get("excludedLabel"))).as("%s excludedLabel", sym).isEqualTo(expectedExcludedLabel(nq, ep));
        }
        assertThat(keys).as("(accountId, symbol) unique").doesNotHaveDuplicates();
    }

    /** FEAT-019 shape of the Greeks summary (unchanged under ALL: no account fields anywhere). */
    void assertGreeksShape(JsonNode body) {
        assertThat(fieldNames(body)).as("top-level fields").containsExactlyInAnyOrderElementsOf(G_TOP_FIELDS);
        assertThat(body.get("groups").isArray()).isTrue();
        assertThat(grSymbols(body)).as("groups by symbol ascending, one per symbol (merged)").isSorted()
                .doesNotHaveDuplicates();
        for (JsonNode g : body.get("groups")) {
            String sym = g.get("symbol").asText();
            assertThat(fieldNames(g)).as("Greeks group %s fields (no account fields, Q234 A)", sym)
                    .containsExactlyInAnyOrderElementsOf(G_GROUP_FIELDS);
            for (String f : List.of("optionDelta", "gamma", "theta", "vega", "netDelta", "dollarExposure")) {
                assertMoneyText(g.get(f), sym + "." + f, true);
            }
            assertMoneyText(g.get("stockDelta"), sym + ".stockDelta", false);
            assertThat(g.get("optionRowCount").asInt()).as("%s optionRowCount = included + noQuote + expired", sym)
                    .isEqualTo(g.get("includedCount").asInt() + g.get("noQuoteCount").asInt()
                            + g.get("expiredPendingCount").asInt());
            String st = g.get("exposureStatus").asText();
            assertThat(st).isIn("OK", "NO_UNDERLYING_PRICE", "NO_DELTA");
            if (st.equals("OK")) {
                BigDecimal exp = money(g.get("netDelta")).multiply(new BigDecimal(g.get("underlyingPrice").asText()))
                        .setScale(2, RoundingMode.HALF_UP);
                assertThat(text(g.get("dollarExposure")))
                        .as("%s exposure = merged netDelta × price, HALF_UP 2 (Q233 A)", sym)
                        .isEqualTo(exp.toPlainString());
                assertThat(text(g.get("manualPriceNote"))).isEqualTo(
                        g.get("underlyingPriceManual").asBoolean() ? MANUAL_PRICE_NOTE : null);
            } else {
                assertThat(g.get("dollarExposure").isNull()).isTrue();
            }
            if (st.equals("NO_DELTA")) {
                assertThat(g.get("netDelta").isNull()).isTrue();
            }
            if (st.equals("NO_UNDERLYING_PRICE")) {
                assertThat(g.get("underlyingPrice").isNull()).isTrue();
                assertThat(text(g.get("exposureLabel"))).isEqualTo(NO_PRICE);
                assertThat(text(g.get("underlyingPriceLabel"))).isEqualTo(NO_PRICE);
            }
            assertThat(text(g.get("excludedLabel"))).as("%s excludedLabel", sym).isEqualTo(
                    expectedExcludedLabel(g.get("noQuoteCount").asInt(), g.get("expiredPendingCount").asInt()));
        }
        JsonNode p = body.get("portfolio");
        assertThat(fieldNames(p)).containsExactlyInAnyOrderElementsOf(G_PORTFOLIO_FIELDS);
        BigDecimal exp = new BigDecimal("0.00");
        BigDecimal theta = new BigDecimal("0.00");
        BigDecimal vega = new BigDecimal("0.00");
        int nq = 0;
        int ep = 0;
        int np = 0;
        for (JsonNode g : body.get("groups")) {
            if (g.get("exposureStatus").asText().equals("OK")) {
                exp = exp.add(money(g.get("dollarExposure")));
            }
            if (g.get("exposureStatus").asText().equals("NO_UNDERLYING_PRICE")) {
                np++;
            }
            if (!g.get("theta").isNull()) {
                theta = theta.add(money(g.get("theta")));
            }
            if (!g.get("vega").isNull()) {
                vega = vega.add(money(g.get("vega")));
            }
            nq += g.get("noQuoteCount").asInt();
            ep += g.get("expiredPendingCount").asInt();
        }
        assertThat(p.get("dollarExposure").asText()).as("portfolio exposure = Σ OK merged groups")
                .isEqualTo(exp.toPlainString());
        assertThat(p.get("theta").asText()).isEqualTo(theta.toPlainString());
        assertThat(p.get("vega").asText()).isEqualTo(vega.toPlainString());
        assertThat(p.get("noQuoteCount").asInt()).isEqualTo(nq);
        assertThat(p.get("expiredPendingCount").asInt()).isEqualTo(ep);
        assertThat(p.get("noUnderlyingPriceCount").asInt())
                .as("noUnderlyingPriceCount counts merged symbol groups (once per symbol)").isEqualTo(np);
        assertThat(text(p.get("excludedLabel"))).isEqualTo(expectedExcludedLabel(nq, ep));
        assertThat(text(p.get("noUnderlyingPriceLabel")))
                .isEqualTo(np == 0 ? null : np + " 个标的无标的价，美元敞口未计入");
    }

    // =========================================================================================== recomputation

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

    private static final String[] POS = {"deltaPosition", "gammaPosition", "thetaPosition", "vegaPosition"};
    private static final String[] GKEYS = {"optionDelta", "gamma", "theta", "vega"};

    private static void notePrice(Acc a, JsonNode quote, String sym) {
        String p = text(quote.get("price"));
        boolean m = quote.get("manual").asBoolean();
        if (a.priceSeen) {
            assertThat(p).as("%s same underlying price on every row of every account (one QuoteBook)", sym)
                    .isEqualTo(a.price);
            assertThat(m).isEqualTo(a.manual);
        }
        a.priceSeen = true;
        a.price = p;
        a.manual = m;
    }

    /**
     * Rule 5 / contract "组内计算": groups = every symbol with an option or stock row in the scope (merged across
     * accounts); Greeks = exact sums of the OK rows' rounded position values of all accounts; counts = row counts of
     * all accounts; stockDelta = Σ shares of all accounts; price / manual = the rows' underlying quote.
     */
    void assertGreeksMatchPositions(String scope, JsonNode s) {
        JsonNode opt = okAs(scope, OPTION_POSITIONS);
        JsonNode stk = okAs(scope, STOCK_POSITIONS);
        assertThat(s.get("asOfDate").asText()).isEqualTo(opt.get("asOfDate").asText());
        Map<String, Acc> by = new TreeMap<>();
        for (JsonNode r : opt.get("positions")) {
            String sym = r.get("symbol").asText();
            Acc a = by.computeIfAbsent(sym, k -> new Acc());
            a.rows++;
            JsonNode g = r.get("greeks");
            switch (g.get("status").asText()) {
                case "OK" -> {
                    a.ok++;
                    for (int i = 0; i < 4; i++) {
                        JsonNode pos = g.get(POS[i]);
                        if (pos == null || pos.isNull()) {
                            a.missing[i]++;
                        } else {
                            a.sum[i] = (a.sum[i] == null ? new BigDecimal("0.00") : a.sum[i]).add(money(pos));
                        }
                    }
                }
                case "NO_QUOTE" -> a.noQuote++;
                default -> a.expired++;
            }
            notePrice(a, r.get("underlyingQuote"), sym);
        }
        for (JsonNode st : stk.get("positions")) {
            String sym = st.get("symbol").asText();
            Acc a = by.computeIfAbsent(sym, k -> new Acc());
            a.shares += st.get("shares").asLong();
            notePrice(a, st.get("quote"), sym);
        }
        assertThat(grSymbols(s)).as("scope %s: one merged group per symbol of the position rows", scope)
                .containsExactlyElementsOf(by.keySet());
        for (Map.Entry<String, Acc> e : by.entrySet()) {
            String sym = e.getKey();
            Acc a = e.getValue();
            JsonNode g = grGroup(s, sym);
            for (int i = 0; i < 4; i++) {
                assertThat(text(g.get(GKEYS[i]))).as("scope %s %s.%s = Σ OK rows of all accounts", scope, sym,
                        GKEYS[i]).isEqualTo(a.sum[i] == null ? null : a.sum[i].toPlainString());
            }
            assertThat(text(g.get("stockDelta"))).as("scope %s %s.stockDelta = Σ shares of all accounts", scope, sym)
                    .isEqualTo(BigDecimal.valueOf(a.shares).setScale(2).toPlainString());
            assertThat(text(g.get("underlyingPrice"))).isEqualTo(a.price);
            assertThat(g.get("underlyingPriceManual").asBoolean()).isEqualTo(a.manual);
            assertThat(g.get("optionRowCount").asInt() + " " + g.get("includedCount").asInt() + "/"
                    + g.get("noQuoteCount").asInt() + "/" + g.get("expiredPendingCount").asInt() + " missing "
                    + g.get("deltaMissingCount").asInt() + "/" + g.get("gammaMissingCount").asInt() + "/"
                    + g.get("thetaMissingCount").asInt() + "/" + g.get("vegaMissingCount").asInt())
                    .as("scope %s %s counts = all accounts' rows", scope, sym)
                    .isEqualTo(a.rows + " " + a.ok + "/" + a.noQuote + "/" + a.expired + " missing " + a.missing[0]
                            + "/" + a.missing[1] + "/" + a.missing[2] + "/" + a.missing[3]);
        }
    }

    /**
     * Q53 / contract "每组数字与切到该账户时同一标的那组逐字相同": the ALL summary groups of each account = that
     * account's own response groups (all fields, same order), and nothing else; same asOfDate.
     */
    void assertUnderlyingAllIsUnionOfSingles(List<String> accounts) {
        JsonNode all = underlyingOf(ALL);
        int total = 0;
        for (String a : accounts) {
            JsonNode single = underlyingOf(a);
            assertThat(all.get("asOfDate")).isEqualTo(single.get("asOfDate"));
            List<JsonNode> fromAll = new ArrayList<>();
            for (JsonNode g : all.get("groups")) {
                if (g.get("accountId").asLong() == Long.parseLong(a)) {
                    fromAll.add(g);
                }
            }
            List<JsonNode> singles = new ArrayList<>();
            single.get("groups").forEach(singles::add);
            assertThat(fromAll).as("ALL groups of account %s = that account's own groups (Q53)", a)
                    .containsExactlyElementsOf(singles);
            total += singles.size();
        }
        assertThat(all.get("groups").size()).as("ALL groups = union of the single accounts' groups").isEqualTo(total);
    }

    /**
     * Rule 4 / contract "一致性" for a scope: group set = (accountId, symbol) of the position rows; per group the
     * counts / sums / openingNetTotal of that account's rows; realized = that account's pnl-summary bySymbol (no
     * dates) or zero; Σ groups = unrealized-summary total; for each symbol where every account with history in the
     * symbol has a group, Σ realized of its groups = the scope's pnl-summary bySymbol row.
     */
    void assertUnderlyingConsistent(String scope) {
        JsonNode us = underlyingOf(scope);
        JsonNode opts = okAs(scope, OPTION_POSITIONS).get("positions");
        JsonNode stocks = okAs(scope, STOCK_POSITIONS).get("positions");

        Map<String, int[]> counts = new LinkedHashMap<>(); // optCount, hasStock, ok, nq, ep
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>(); // mv, pnl, prem
        Set<String> keys = new TreeSet<>();
        List<JsonNode> rows = new ArrayList<>();
        opts.forEach(rows::add);
        stocks.forEach(rows::add);
        for (JsonNode row : rows) {
            String key = row.get("accountId").asLong() + "|" + row.get("symbol").asText();
            keys.add(key);
            int[] c = counts.computeIfAbsent(key, k -> new int[5]);
            BigDecimal[] s = sums.computeIfAbsent(key,
                    k -> new BigDecimal[] {new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00")});
            if (row.has("openingNetTotal")) {
                c[0]++;
                s[2] = s[2].add(money(row.get("openingNetTotal")));
            } else {
                c[1] = 1;
            }
            switch (row.get("unrealizedStatus").asText()) {
                case "OK" -> {
                    c[2]++;
                    s[0] = s[0].add(money(row.get("marketValue")));
                    s[1] = s[1].add(money(row.get("unrealizedPnl")));
                }
                case "NO_QUOTE" -> c[3]++;
                case "EXPIRED_PENDING" -> c[4]++;
                default -> throw new AssertionError("unknown unrealizedStatus " + row);
            }
        }
        Set<String> groupKeys = new TreeSet<>();
        us.get("groups").forEach(g -> groupKeys.add(g.get("accountId").asLong() + "|" + g.get("symbol").asText()));
        assertThat(groupKeys).as("scope %s: group set = (accountId, symbol) of the position rows", scope)
                .isEqualTo(keys);

        Map<Long, Map<String, JsonNode>> pnlByAccount = new LinkedHashMap<>();
        BigDecimal mvSum = new BigDecimal("0.00");
        BigDecimal pnlSum = new BigDecimal("0.00");
        int inc = 0;
        int nq = 0;
        int ep = 0;
        Map<String, BigDecimal[]> realizedBySymbol = new TreeMap<>(); // count, pnl, optCount, optPnl, stCount, stPnl
        Map<String, Set<Long>> accountsBySymbol = new TreeMap<>();
        for (JsonNode g : us.get("groups")) {
            long acc = g.get("accountId").asLong();
            String sym = g.get("symbol").asText();
            String key = acc + "|" + sym;
            int[] c = counts.get(key);
            BigDecimal[] s = sums.get(key);
            assertThat(g.get("optionPositionCount").asInt()).as("%s optionPositionCount", key).isEqualTo(c[0]);
            assertThat(g.get("hasStockPosition").asBoolean()).as("%s hasStockPosition", key).isEqualTo(c[1] == 1);
            assertThat(g.get("includedCount").asInt() + "/" + g.get("noQuoteCount").asInt() + "/"
                    + g.get("expiredPendingCount").asInt()).as("%s counts", key)
                    .isEqualTo(c[2] + "/" + c[3] + "/" + c[4]);
            if (c[2] > 0) {
                assertThat(g.get("marketValue").asText()).as("%s marketValue", key).isEqualTo(s[0].toPlainString());
                assertThat(g.get("unrealizedPnl").asText()).as("%s unrealizedPnl", key)
                        .isEqualTo(s[1].toPlainString());
                mvSum = mvSum.add(s[0]);
                pnlSum = pnlSum.add(s[1]);
            }
            if (c[0] > 0) {
                assertThat(g.get("netPremium").asText()).as("%s netPremium = Σ openingNetTotal", key)
                        .isEqualTo(s[2].toPlainString());
            }
            inc += c[2];
            nq += c[3];
            ep += c[4];
            assertThat(g.get("accountName").asText()).as("%s accountName = current name", key)
                    .isEqualTo(accountNameOf(acc));

            Map<String, JsonNode> own = pnlByAccount.computeIfAbsent(acc, k -> {
                Map<String, JsonNode> m = new LinkedHashMap<>();
                okAs(String.valueOf(k), PNL).get("bySymbol").forEach(r -> m.put(r.get("symbol").asText(), r));
                return m;
            });
            JsonNode p = own.get(sym);
            String expected = p == null ? "0 0.00 (0 0.00 · 0 0.00)" : realizedLine(p);
            assertThat(realizedLine(g.get("realized")))
                    .as("%s realized = that account's own pnl-summary bySymbol (not the merged row)", key)
                    .isEqualTo(expected);
            BigDecimal[] rs = realizedBySymbol.computeIfAbsent(sym, k -> new BigDecimal[] {BigDecimal.ZERO,
                    new BigDecimal("0.00"), BigDecimal.ZERO, new BigDecimal("0.00"), BigDecimal.ZERO,
                    new BigDecimal("0.00")});
            JsonNode r = g.get("realized");
            rs[0] = rs[0].add(BigDecimal.valueOf(r.get("closedCount").asInt()));
            rs[1] = rs[1].add(money(r.get("realizedPnl")));
            rs[2] = rs[2].add(BigDecimal.valueOf(r.get("option").get("closedCount").asInt()));
            rs[3] = rs[3].add(money(r.get("option").get("realizedPnl")));
            rs[4] = rs[4].add(BigDecimal.valueOf(r.get("stock").get("closedCount").asInt()));
            rs[5] = rs[5].add(money(r.get("stock").get("realizedPnl")));
            accountsBySymbol.computeIfAbsent(sym, k -> new HashSet<>()).add(acc);
        }
        JsonNode total = okAs(scope, UNREALIZED).get("total");
        assertThat(mvSum.toPlainString() + " " + pnlSum.toPlainString() + " " + inc + "/" + nq + "/" + ep)
                .as("scope %s: Σ groups = unrealized-summary total (rule 4)", scope)
                .isEqualTo(total.get("marketValue").asText() + " " + total.get("unrealizedPnl").asText() + " "
                        + total.get("includedCount").asInt() + "/" + total.get("noQuoteCount").asInt() + "/"
                        + total.get("expiredPendingCount").asInt());

        Map<String, JsonNode> scopePnl = new LinkedHashMap<>();
        okAs(scope, PNL).get("bySymbol").forEach(r -> scopePnl.put(r.get("symbol").asText(), r));
        for (Map.Entry<String, BigDecimal[]> e : realizedBySymbol.entrySet()) {
            String sym = e.getKey();
            boolean everyHistoryAccountHasCard = true;
            for (long acc : accountIds(scope)) {
                Map<String, JsonNode> own = new LinkedHashMap<>();
                okAs(String.valueOf(acc), PNL).get("bySymbol").forEach(r -> own.put(r.get("symbol").asText(), r));
                if (own.containsKey(sym) && !accountsBySymbol.get(sym).contains(acc)) {
                    everyHistoryAccountHasCard = false;
                }
            }
            if (!everyHistoryAccountHasCard) {
                continue;
            }
            BigDecimal[] rs = e.getValue();
            String sumLine = rs[0].intValue() + " " + rs[1].toPlainString() + " (" + rs[2].intValue() + " "
                    + rs[3].toPlainString() + " · " + rs[4].intValue() + " " + rs[5].toPlainString() + ")";
            JsonNode p = scopePnl.get(sym);
            assertThat(sumLine).as("scope %s: Σ realized of %s cards = pnl-summary bySymbol (Q222)", scope, sym)
                    .isEqualTo(p == null ? "0 0.00 (0 0.00 · 0 0.00)" : realizedLine(p));
        }
    }

    /** Account ids of a scope (ALL → every account of GET /api/accounts). */
    List<Long> accountIds(String scope) {
        List<Long> out = new ArrayList<>();
        if (!scope.trim().equalsIgnoreCase(ALL)) {
            out.add(Long.parseLong(scope.trim()));
            return out;
        }
        Resp r = noAccount(() -> get("/api/accounts"));
        assertThat(r.status()).isEqualTo(200);
        JsonNode list = r.body().isArray() ? r.body() : r.body().get("accounts");
        list.forEach(a -> out.add(a.get("id").asLong()));
        return out;
    }

    String accountNameOf(long id) {
        Resp r = noAccount(() -> get("/api/accounts"));
        JsonNode list = r.body().isArray() ? r.body() : r.body().get("accounts");
        for (JsonNode a : list) {
            if (a.get("id").asLong() == id) {
                return a.get("name").asText();
            }
        }
        throw new AssertionError("no account " + id);
    }

    /** Everything observable by the two endpoints for 甲 / 乙 / ALL (for "unchanged" checks). */
    Map<String, JsonNode> summariesWorld() {
        Map<String, JsonNode> w = new LinkedHashMap<>();
        for (String s : List.of(A, B, ALL)) {
            w.put(s + " " + UNDERLYING, okAs(s, UNDERLYING));
            w.put(s + " " + GREEKS, okAs(s, GREEKS));
        }
        return w;
    }
}
