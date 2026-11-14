package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers of the QA system tests for FEAT-017 未实现盈亏与市值 (contract docs/api/FEAT-017.md).
 *
 * <p>Reuses the FEAT-016 QA base (profiles {@code test} + {@code e2e}, random port, in-memory fake CBOE / Finnhub,
 * settable clock, reset before / after every test). Quotes are injected with the fake sources and a scheduled
 * {@code tick} at the test clock; never the real CBOE / Finnhub.
 *
 * <p>Requirement scenario (today 2026-11-23 Mon, pull at 10:00 succeeds; fictional tickers):
 * <ul>
 *   <li>Account A (default account 1): P1 STO 1 QRTV 2026-12-18 50P net 118.70 (bid 1.20 / ask 1.30, mark 1.25);
 *       P2 BTO 2 QRTV 2026-12-18 55C net 301.30 (1.80 / 1.90, mark 1.85); P3 STO 1 IXQZ 2026-12-18 65C net 84.35
 *       (0.80 / 0.90, mark 0.85; covered by the shares); P4 STO 1 QRTV 2026-12-18 47.5P net 45.00 (never in the
 *       chain); IXQZ 100 shares (转入) total cost 5,880.00, price 61.10.</li>
 *   <li>Account B: P5 STO 1 QRTV 2026-12-18 50P net 120.00.</li>
 * </ul>
 */
abstract class Feat017UnrealizedSystemTestBase extends Feat016QuoteSystemTestBase {

    static final String SUMMARY = "/api/positions/unrealized-summary";

    static final String C_P1 = "QRTV 2026-12-18 50 P";
    static final String C_P2 = "QRTV 2026-12-18 55 C";
    static final String C_P3 = "IXQZ 2026-12-18 65 C";
    static final String C_P4 = "QRTV 2026-12-18 47.5 P";
    static final String C_P5 = C_P1;
    static final String C_P6 = "QRTV 2026-12-18 60 C";
    static final String C_EXPIRED = "QRTV 2026-11-20 45 P";

    static final String OCC_C_P1 = "QRTV261218P00050000";
    static final String OCC_C_P2 = "QRTV261218C00055000";
    static final String OCC_C_P3 = "IXQZ261218C00065000";
    static final String OCC_C_P6 = "QRTV261218C00060000";
    static final String OCC_C_EXPIRED = "QRTV261120P00045000";

    static final String NO_QUOTE_LABEL = "无报价";
    static final String EXPIRED_LABEL = "已到期，待处理";

    static final List<String> ROW_FIELDS = List.of("marketValue", "unrealizedPnl", "unrealizedPnlPercent",
            "unrealizedStatus", "unrealizedLabel");
    static final List<String> SUMMARY_FIELDS = List.of("asOfDate", "option", "stock", "total", "excludedLabel");
    static final List<String> GROUP_FIELDS = List.of("marketValue", "unrealizedPnl", "includedCount", "noQuoteCount",
            "expiredPendingCount");

    // =========================================================================================== data

    long openN(String action, int contracts, String symbol, String expiration, String strike, String type,
               String net, String at) {
        ObjectNode body = trade(action, contracts, symbol, expiration, strike, type, net, at);
        Resp r = post("/api/option-trades", body.toString());
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void closeN(String action, int contracts, String symbol, String expiration, String strike, String type,
                String net, String at) {
        ObjectNode body = trade(action, contracts, symbol, expiration, strike, type, net, at);
        body.remove("multiplier");
        Resp r = post("/api/option-trades/closes", body.toString());
        assertThat(r.status()).as("POST closes %s -> %s", body, r.body()).isEqualTo(201);
    }

    void expire(String symbol, String expiration, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        Resp r = post("/api/option-trades/expirations", n.toString());
        assertThat(r.status()).as("POST expirations %s -> %s", n, r.body()).isEqualTo(201);
    }

    void assignPut(String symbol, String expiration, String strike, int contracts, String assignmentDate) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", "PUT");
        n.put("contracts", contracts);
        n.put("assignmentDate", assignmentDate);
        n.put("assignmentFee", "0.00");
        n.putNull("note");
        Resp r = post("/api/option-trades/assignments", n.toString());
        assertThat(r.status()).as("POST assignments %s -> %s", n, r.body()).isEqualTo(201);
    }

    void sellShares(String symbol, int shares, String amount, String tradedAtLocal) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "SELL");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", tradedAtLocal);
        n.putNull("note");
        Resp r = post("/api/stock-trades", n.toString());
        assertThat(r.status()).as("SELL %s -> %s", n, r.body()).isEqualTo(201);
    }

    /**
     * The requirement scenario (data only, no sources, no pull). Clock left at 2026-11-23 09:50 New York, API account
     * A ("1"); {@link #accountB} set.
     */
    void scenario017() {
        setNow("2026-11-23T09:50");
        accountId = "1";
        deposit("30000.00", "2026-11-16T10:00");
        transferInShares("IXQZ", 100, "5880.00", "2026-11-19");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-20T10:00");
        openN("BTO", 2, "QRTV", "2026-12-18", "55", "CALL", "301.30", "2026-11-20T10:05");
        openN("STO", 1, "IXQZ", "2026-12-18", "65", "CALL", "84.35", "2026-11-20T10:10");
        openN("STO", 1, "QRTV", "2026-12-18", "47.5", "PUT", "45.00", "2026-11-20T10:15");
        accountB = createAccount("账户B");
        accountId = accountB;
        deposit("10000.00", "2026-11-16T10:00");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-20T10:20");
        accountId = "1";
    }

    ObjectNode qrtv017(String timestamp, ObjectNode... extra) {
        List<ObjectNode> opts = new ArrayList<>();
        opts.add(option(OCC_C_P1, "bid", "1.20", "ask", "1.30", "theo", "1.24"));
        opts.add(option(OCC_C_P2, "bid", "1.80", "ask", "1.90", "theo", "1.86"));
        opts.addAll(List.of(extra));
        return ok(cboeBody("QRTV", "52.40", timestamp, opts));
    }

    ObjectNode ixqz017(String timestamp) {
        return ok(cboeBody("IXQZ", "61.10", timestamp,
                List.of(option(OCC_C_P3, "bid", "0.80", "ask", "0.90", "theo", "0.84"))));
    }

    /** The scenario's fake CBOE: QRTV (P1, P2, no P4) and IXQZ (P3), price 52.40 / 61.10. */
    void sources017(ObjectNode... extraQrtvOptions) {
        sources().cboe("QRTV", qrtv017("2026-11-23 09:45:00", extraQrtvOptions))
                .cboe("IXQZ", ixqz017("2026-11-23 09:45:00")).apply();
    }

    /** Scenario + sources + the 10:00 pull; clock left at 10:00. */
    void scenarioPulled() {
        scenario017();
        sources017();
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    // =========================================================================================== reading

    JsonNode optionPositionsOf(String account) {
        return optionPositions(account);
    }

    /** Option row by contractLabel (asserts the FEAT-017 field set is present). */
    JsonNode optRow(String account, String contractLabel) {
        JsonNode p = position(account, contractLabel);
        assertThat(fieldNames(p)).as("FEAT-017 fields of %s", contractLabel).containsAll(ROW_FIELDS);
        return p;
    }

    boolean hasOptRow(String account, String contractLabel) {
        for (JsonNode p : optionPositions(account).get("positions")) {
            if (p.get("contractLabel").asText().equals(contractLabel)) {
                return true;
            }
        }
        return false;
    }

    JsonNode stockPositions(String account) {
        String saved = accountId;
        accountId = account;
        try {
            Resp r = get(STOCK_POSITIONS);
            assertThat(r.status()).as("GET %s -> %s", STOCK_POSITIONS, r.body()).isEqualTo(200);
            return r.body();
        } finally {
            accountId = saved;
        }
    }

    JsonNode stkRow(String account, String symbol) {
        for (JsonNode p : stockPositions(account).get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                assertThat(fieldNames(p)).as("FEAT-017 fields of stock %s", symbol).containsAll(ROW_FIELDS);
                return p;
            }
        }
        throw new AssertionError("no stock position " + symbol + " in account " + account);
    }

    /** "marketValue unrealizedPnl unrealizedPnlPercent unrealizedStatus unrealizedLabel" of a row. */
    static String val(JsonNode row) {
        return text(row.get("marketValue")) + " " + text(row.get("unrealizedPnl")) + " "
                + text(row.get("unrealizedPnlPercent")) + " " + row.get("unrealizedStatus").asText() + " "
                + text(row.get("unrealizedLabel"));
    }

    static String okVal(String mv, String pnl, String pct) {
        return mv + " " + pnl + " " + pct + " OK null";
    }

    static final String NO_QUOTE_VAL = "null null null NO_QUOTE " + NO_QUOTE_LABEL;
    static final String EXPIRED_VAL = "null null null EXPIRED_PENDING " + EXPIRED_LABEL;

    Resp summaryResp(String account) {
        String saved = accountId;
        accountId = account;
        try {
            return get(SUMMARY);
        } finally {
            accountId = saved;
        }
    }

    /** GET the summary of {@code account}; asserts status 200 and the contract shape. */
    JsonNode summary(String account) {
        Resp r = summaryResp(account);
        assertThat(r.status()).as("GET %s (account %s) -> %s", SUMMARY, account, r.body()).isEqualTo(200);
        assertSummaryShape(r.body());
        return r.body();
    }

    void assertSummaryShape(JsonNode s) {
        assertThat(fieldNames(s)).as("summary fields").containsExactlyInAnyOrderElementsOf(SUMMARY_FIELDS);
        for (String g : List.of("option", "stock", "total")) {
            JsonNode n = s.get(g);
            assertThat(n.isObject()).as("%s never null", g).isTrue();
            assertThat(fieldNames(n)).as("%s fields", g).containsExactlyInAnyOrderElementsOf(GROUP_FIELDS);
            assertThat(n.get("marketValue").isTextual()).as("%s.marketValue is a string", g).isTrue();
            assertThat(n.get("unrealizedPnl").isTextual()).as("%s.unrealizedPnl is a string", g).isTrue();
            assertThat(new BigDecimal(n.get("marketValue").asText()).scale()).isEqualTo(2);
            assertThat(new BigDecimal(n.get("unrealizedPnl").asText()).scale()).isEqualTo(2);
            assertThat(n.get("includedCount").isInt()).isTrue();
            assertThat(n.get("noQuoteCount").isInt()).isTrue();
            assertThat(n.get("expiredPendingCount").isInt()).isTrue();
        }
        assertThat(s.get("stock").get("expiredPendingCount").asInt()).as("stock never EXPIRED_PENDING").isZero();
        // total = option + stock
        for (String f : List.of("marketValue", "unrealizedPnl")) {
            assertThat(d(s.get("total").get(f).asText()))
                    .as("total.%s = option + stock", f)
                    .isEqualByComparingTo(d(s.get("option").get(f).asText()).add(d(s.get("stock").get(f).asText())));
        }
        for (String f : List.of("includedCount", "noQuoteCount", "expiredPendingCount")) {
            assertThat(s.get("total").get(f).asInt()).as("total.%s", f)
                    .isEqualTo(s.get("option").get(f).asInt() + s.get("stock").get(f).asInt());
        }
    }

    /** "marketValue unrealizedPnl included/noQuote/expiredPending" of a summary group. */
    static String grp(JsonNode group) {
        return group.get("marketValue").asText() + " " + group.get("unrealizedPnl").asText() + " "
                + group.get("includedCount").asInt() + "/" + group.get("noQuoteCount").asInt() + "/"
                + group.get("expiredPendingCount").asInt();
    }

    /**
     * Contract: the summary is computed with the same code as the rows — option / stock group = sum of the OK rows'
     * marketValue / unrealizedPnl, counts = number of rows per status.
     */
    void assertSummaryMatchesRows(String account) {
        JsonNode s = summary(account);
        assertGroupMatches(s.get("option"), optionPositions(account).get("positions"), "option");
        assertGroupMatches(s.get("stock"), stockPositions(account).get("positions"), "stock");
    }

    private void assertGroupMatches(JsonNode group, JsonNode rows, String name) {
        BigDecimal mv = BigDecimal.ZERO.setScale(2);
        BigDecimal pnl = BigDecimal.ZERO.setScale(2);
        int ok = 0;
        int nq = 0;
        int ep = 0;
        for (JsonNode r : rows) {
            switch (r.get("unrealizedStatus").asText()) {
                case "OK" -> {
                    ok++;
                    mv = mv.add(d(r.get("marketValue").asText()));
                    pnl = pnl.add(d(r.get("unrealizedPnl").asText()));
                }
                case "NO_QUOTE" -> nq++;
                case "EXPIRED_PENDING" -> ep++;
                default -> throw new AssertionError("unknown unrealizedStatus " + r);
            }
        }
        assertThat(grp(group)).as("%s group = sum of rows", name)
                .isEqualTo(mv.toPlainString() + " " + pnl.toPlainString() + " " + ok + "/" + nq + "/" + ep);
    }

    /** Row-level invariants of the contract for every row of the account (types, scale, null-ness, labels). */
    void assertRowInvariants(String account) {
        List<JsonNode> all = new ArrayList<>();
        optionPositions(account).get("positions").forEach(all::add);
        stockPositions(account).get("positions").forEach(all::add);
        for (JsonNode r : all) {
            assertThat(fieldNames(r)).containsAll(ROW_FIELDS);
            String st = r.get("unrealizedStatus").asText();
            assertThat(st).isIn("OK", "NO_QUOTE", "EXPIRED_PENDING");
            if (st.equals("OK")) {
                assertThat(r.get("unrealizedLabel").isNull()).as("OK → label null: %s", r).isTrue();
                assertThat(r.get("marketValue").isTextual()).isTrue();
                assertThat(r.get("unrealizedPnl").isTextual()).isTrue();
                assertThat(d(r.get("marketValue").asText()).scale()).isEqualTo(2);
                assertThat(d(r.get("unrealizedPnl").asText()).scale()).isEqualTo(2);
                if (!r.get("unrealizedPnlPercent").isNull()) {
                    String pct = r.get("unrealizedPnlPercent").asText();
                    assertThat(pct).as("percent without + / %%").doesNotContain("+").doesNotContain("%");
                    assertThat(d(pct).scale()).isEqualTo(2);
                }
                // pnl = openingNetTotal + marketValue (option) / marketValue - totalCost (stock)
                if (r.has("openingNetTotal")) {
                    assertThat(d(r.get("unrealizedPnl").asText())).isEqualByComparingTo(
                            d(r.get("openingNetTotal").asText()).add(d(r.get("marketValue").asText())));
                } else {
                    assertThat(d(r.get("unrealizedPnl").asText())).isEqualByComparingTo(
                            d(r.get("marketValue").asText()).subtract(d(r.get("totalCost").asText())));
                }
            } else {
                assertThat(r.get("marketValue").isNull()).isTrue();
                assertThat(r.get("unrealizedPnl").isNull()).isTrue();
                assertThat(r.get("unrealizedPnlPercent").isNull()).isTrue();
                assertThat(text(r.get("unrealizedLabel")))
                        .isEqualTo(st.equals("NO_QUOTE") ? NO_QUOTE_LABEL : EXPIRED_LABEL);
            }
        }
    }
}
