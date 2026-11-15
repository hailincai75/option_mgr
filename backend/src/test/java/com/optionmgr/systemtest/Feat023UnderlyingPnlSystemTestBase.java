package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpMethod;

/**
 * Shared helpers of the QA system tests for FEAT-023 按标的盈亏 (contract docs/api/FEAT-023.md, new
 * {@code GET /api/positions/underlying-summary}).
 *
 * <p>Reuses the FEAT-016 / 017 QA bases (profiles {@code test} + {@code e2e}, random port, in-memory fake CBOE /
 * Finnhub, settable clock, reset before / after every test). Quotes come from the fake sources and a scheduled
 * {@code tick}; never the real CBOE / Finnhub.
 *
 * <p>Requirement scenario (today 2026-11-23 Mon, the 10:00 pull succeeds, multiplier 100; fictional tickers QRTV,
 * IXQZ, ZWLK, QFZ):
 * <ul>
 *   <li>Account A (default account 1): deposit 50,000.00; S1 转入 QRTV 100 股 4,800.00, S2 转入 ZWLK 50 股 1,500.00
 *       (2026-10-01). History H1 STO QRTV 2026-11-20 50C 60.35 / H2 BTC 20.65 (+39.70); H3 STO IXQZ 2026-11-13 55P
 *       80.00 / H4 作废 (+80.00); H5 STO QFZ 2026-11-20 20P 50.00 / H6 BTC 10.65 (+39.35); H7 卖出 ZWLK 20 股 700.00
 *       (+100.00). Positions T1 STO QRTV 12-18 55C 85.35, T2 STO 45P 120.35, T3 BTO 40P 40.65, T6 STO IXQZ 11-20 58P
 *       90.00 (expired, pending), T7 STO IXQZ 12-18 60P 200.00.</li>
 *   <li>Quotes: QRTV 50.00; marks 55C 0.60, 45P 1.10, 40P 0.30, IXQZ 60P 2.50 (all MID); ZWLK never quoted (its fake
 *       source is not configured = unreachable).</li>
 *   <li>Account B: deposit 10,000.00; T8 STO QRTV 12-18 45P 115.00.</li>
 * </ul>
 */
abstract class Feat023UnderlyingPnlSystemTestBase extends Feat017UnrealizedSystemTestBase {

    static final String UNDERLYING = "/api/positions/underlying-summary";
    static final String PNL_SUMMARY = "/api/pnl-summary";
    static final String CLOSED_TRADES = "/api/closed-trades";
    static final String OPTION_TRADES = "/api/option-trades";
    static final String STOCK_TRADES = "/api/stock-trades";

    static final List<String> TOP_FIELDS = List.of("asOfDate", "groups");
    static final List<String> UGROUP_FIELDS = List.of("symbol", "realized", "marketValue", "unrealizedPnl",
            "totalPnl", "netPremium", "optionPositionCount", "hasStockPosition", "includedCount", "noQuoteCount",
            "expiredPendingCount", "excludedLabel");
    static final List<String> REALIZED_FIELDS = List.of("closedCount", "realizedPnl", "option", "stock");
    static final List<String> PART_FIELDS = List.of("closedCount", "realizedPnl");

    // contracts of the scenario
    static final String QRTV_55C = "QRTV 2026-12-18 55 C";
    static final String QRTV_45P = "QRTV 2026-12-18 45 P";
    static final String QRTV_40P = "QRTV 2026-12-18 40 P";
    static final String IXQZ_58P = "IXQZ 2026-11-20 58 P";
    static final String IXQZ_60P = "IXQZ 2026-12-18 60 P";

    static final String OCC_QRTV_55C = "QRTV261218C00055000";
    static final String OCC_QRTV_45P = "QRTV261218P00045000";
    static final String OCC_QRTV_40P = "QRTV261218P00040000";
    static final String OCC_IXQZ_60P = "IXQZ261218P00060000";

    static final String LABEL_1_EXPIRED = "1 个持仓已到期待处理，未计入";
    static final String LABEL_1_NO_QUOTE = "1 个持仓无报价，未计入";

    // expected group lines of AC 1 (account A) and AC 8 (account B); see grpLine for the format
    static final String G_IXQZ = "IXQZ | 1 80.00 (1 80.00 · 0 0.00) | mv -250.00 | upnl -50.00 | total 30.00"
            + " | prem 290.00 | opt 2 stock false | 1/0/1 | " + LABEL_1_EXPIRED;
    static final String G_QRTV = "QRTV | 1 39.70 (1 39.70 · 0 0.00) | mv 4860.00 | upnl 225.05 | total 264.75"
            + " | prem 165.05 | opt 3 stock true | 4/0/0 | null";
    static final String G_ZWLK = "ZWLK | 1 100.00 (0 0.00 · 1 100.00) | mv null | upnl null | total 100.00"
            + " | prem null | opt 0 stock true | 0/1/0 | " + LABEL_1_NO_QUOTE;
    static final String G_QRTV_B = "QRTV | 0 0.00 (0 0.00 · 0 0.00) | mv -110.00 | upnl 5.00 | total 5.00"
            + " | prem 115.00 | opt 1 stock false | 1/0/0 | null";

    /** Ids of the scenario records the tests change later. */
    long h4ExpireId;
    long h7SellId;

    // =========================================================================================== data

    long expireId(String symbol, String expiration, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        Resp r = post(OPTION_TRADES + "/expirations", n.toString());
        assertThat(r.status()).as("POST expirations %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long assignId(String symbol, String expiration, String strike, String type, int contracts, String date) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        n.put("assignmentDate", date);
        n.put("assignmentFee", "0.00");
        n.putNull("note");
        Resp r = post(OPTION_TRADES + "/assignments", n.toString());
        assertThat(r.status()).as("POST assignments %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void deleteOptionTrade(long id) {
        Resp r = exchange(HttpMethod.DELETE, OPTION_TRADES + "/" + id, null);
        assertThat(r.status()).as("DELETE option trade %d -> %s", id, r.body()).isEqualTo(204);
    }

    long stockTradeId(String type, String symbol, int shares, String amount, String tradedAtLocal) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", tradedAtLocal);
        n.putNull("note");
        Resp r = post(STOCK_TRADES, n.toString());
        assertThat(r.status()).as("%s %s -> %s", type, n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void editStockAmount(long id, int shares, String amount, String tradedAtLocal) {
        ObjectNode n = json.createObjectNode();
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", tradedAtLocal);
        n.putNull("note");
        Resp r = exchange(HttpMethod.PUT, STOCK_TRADES + "/" + id, n.toString());
        assertThat(r.status()).as("PUT stock %d %s -> %s", id, n, r.body()).isEqualTo(200);
    }

    /**
     * Requirement scenario, data only (no sources, no pull). Clock left at 2026-11-23 09:50 New York, API account A
     * ("1"); {@link #accountB}, {@link #h4ExpireId}, {@link #h7SellId} set.
     */
    void scenario023() {
        setNow("2026-11-23T09:50");
        accountId = "1";
        deposit("50000.00", "2026-10-01T09:00");
        transferInShares("QRTV", 100, "4800.00", "2026-10-01");
        transferInShares("ZWLK", 50, "1500.00", "2026-10-01");
        // history
        openN("STO", 1, "QRTV", "2026-11-20", "50", "CALL", "60.35", "2026-10-20T10:00");          // H1
        closeN("BTC", 1, "QRTV", "2026-11-20", "50", "CALL", "20.65", "2026-10-30T10:00");         // H2
        openN("STO", 1, "IXQZ", "2026-11-13", "55", "PUT", "80.00", "2026-10-27T10:00");           // H3
        h4ExpireId = expireId("IXQZ", "2026-11-13", "55", "PUT", 1);                               // H4
        openN("STO", 1, "QFZ", "2026-11-20", "20", "PUT", "50.00", "2026-10-28T10:00");            // H5
        closeN("BTC", 1, "QFZ", "2026-11-20", "20", "PUT", "10.65", "2026-11-06T10:00");           // H6
        h7SellId = stockTradeId("SELL", "ZWLK", 20, "700.00", "2026-11-12T10:00");                 // H7
        // positions
        openN("STO", 1, "QRTV", "2026-12-18", "55", "CALL", "85.35", "2026-11-02T10:00");          // T1
        openN("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "120.35", "2026-11-03T10:00");          // T2
        openN("BTO", 1, "QRTV", "2026-12-18", "40", "PUT", "40.65", "2026-11-03T10:05");           // T3
        openN("STO", 1, "IXQZ", "2026-11-20", "58", "PUT", "90.00", "2026-11-02T10:30");           // T6
        openN("STO", 1, "IXQZ", "2026-12-18", "60", "PUT", "200.00", "2026-11-05T10:30");          // T7
        accountB = createAccount("账户B");
        accountId = accountB;
        deposit("10000.00", "2026-10-01T09:00");
        openN("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "115.00", "2026-11-05T11:00");          // T8
        accountId = "1";
    }

    /** Fake CBOE of the scenario: QRTV 50.00 (55C 0.60, 45P 1.10, 40P 0.30), IXQZ 61.00 (60P 2.50); ZWLK absent. */
    void sources023() {
        sources()
                .cboe("QRTV", ok(cboeBody("QRTV", "50.00", "2026-11-23 09:45:00", List.of(
                        option(OCC_QRTV_55C, "bid", "0.55", "ask", "0.65"),
                        option(OCC_QRTV_45P, "bid", "1.05", "ask", "1.15"),
                        option(OCC_QRTV_40P, "bid", "0.25", "ask", "0.35")))))
                .cboe("IXQZ", ok(cboeBody("IXQZ", "61.00", "2026-11-23 09:45:00", List.of(
                        option(OCC_IXQZ_60P, "bid", "2.45", "ask", "2.55")))))
                .apply();
    }

    /** Scenario + sources + the 10:00 pull; clock left at 10:00. */
    void scenario023Pulled() {
        scenario023();
        sources023();
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    // =========================================================================================== reading

    Resp underlyingResp(String account) {
        String saved = accountId;
        accountId = account;
        try {
            return get(UNDERLYING);
        } finally {
            accountId = saved;
        }
    }

    /** GET the underlying summary of {@code account}; asserts 200 and every contract invariant of the shape. */
    JsonNode underlying(String account) {
        Resp r = underlyingResp(account);
        assertThat(r.status()).as("GET %s (account %s) -> %s", UNDERLYING, account, r.body()).isEqualTo(200);
        assertUnderlyingShape(r.body());
        return r.body();
    }

    /** "SYMBOL | …" line of every group, in API order. */
    List<String> groupLines(String account) {
        List<String> out = new ArrayList<>();
        underlying(account).get("groups").forEach(g -> out.add(grpLine(g)));
        return out;
    }

    /** The group line of {@code symbol} (must be present). */
    String groupLine(String account, String symbol) {
        for (JsonNode g : underlying(account).get("groups")) {
            if (g.get("symbol").asText().equals(symbol)) {
                return grpLine(g);
            }
        }
        throw new AssertionError("no group " + symbol + " in account " + account);
    }

    static String part(JsonNode p) {
        return p.get("closedCount").asInt() + " " + p.get("realizedPnl").asText();
    }

    /**
     * "SYMBOL | count pnl (optCount optPnl · stockCount stockPnl) | mv X | upnl X | total X | prem X | opt N stock B |
     * included/noQuote/expiredPending | excludedLabel".
     */
    static String grpLine(JsonNode g) {
        JsonNode r = g.get("realized");
        return g.get("symbol").asText() + " | " + part(r) + " (" + part(r.get("option")) + " · "
                + part(r.get("stock")) + ") | mv " + text(g.get("marketValue")) + " | upnl "
                + text(g.get("unrealizedPnl")) + " | total " + text(g.get("totalPnl")) + " | prem "
                + text(g.get("netPremium")) + " | opt " + g.get("optionPositionCount").asInt() + " stock "
                + g.get("hasStockPosition").asBoolean() + " | " + g.get("includedCount").asInt() + "/"
                + g.get("noQuoteCount").asInt() + "/" + g.get("expiredPendingCount").asInt() + " | "
                + text(g.get("excludedLabel"));
    }

    static void assertMoney(JsonNode n, String what) {
        assertThat(n.isTextual()).as("%s is a JSON string: %s", what, n).isTrue();
        String s = n.asText();
        assertThat(s).as("%s has no '+'", what).doesNotContain("+");
        assertThat(s).as("%s is not negative zero", what).isNotEqualTo("-0.00");
        assertThat(d(s).scale()).as("%s scale 2", what).isEqualTo(2);
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

    /** Every shape rule and per-group invariant of the contract (field sets, types, null rules, sums, order). */
    void assertUnderlyingShape(JsonNode body) {
        assertThat(fieldNames(body)).as("top-level fields").containsExactlyInAnyOrderElementsOf(TOP_FIELDS);
        assertThat(body.get("asOfDate").isTextual()).isTrue();
        assertThat(body.get("groups").isArray()).as("groups is an array (never null)").isTrue();
        List<String> symbols = new ArrayList<>();
        for (JsonNode g : body.get("groups")) {
            String sym = g.get("symbol").asText();
            symbols.add(sym);
            assertThat(fieldNames(g)).as("group %s fields", sym).containsExactlyInAnyOrderElementsOf(UGROUP_FIELDS);
            assertThat(sym).as("symbol upper case").isEqualTo(sym.toUpperCase());

            // realized: never null; option + stock = total
            JsonNode r = g.get("realized");
            assertThat(r.isObject()).as("%s realized never null", sym).isTrue();
            assertThat(fieldNames(r)).containsExactlyInAnyOrderElementsOf(REALIZED_FIELDS);
            assertThat(r.get("closedCount").isInt()).isTrue();
            assertMoney(r.get("realizedPnl"), sym + ".realized.realizedPnl");
            for (String p : List.of("option", "stock")) {
                JsonNode pn = r.get(p);
                assertThat(pn.isObject()).as("%s realized.%s never null", sym, p).isTrue();
                assertThat(fieldNames(pn)).containsExactlyInAnyOrderElementsOf(PART_FIELDS);
                assertThat(pn.get("closedCount").isInt()).isTrue();
                assertMoney(pn.get("realizedPnl"), sym + ".realized." + p + ".realizedPnl");
                if (pn.get("closedCount").asInt() == 0) {
                    assertThat(pn.get("realizedPnl").asText()).as("%s %s 0 笔 → 0.00", sym, p).isEqualTo("0.00");
                }
            }
            assertThat(r.get("option").get("closedCount").asInt() + r.get("stock").get("closedCount").asInt())
                    .as("%s option + stock count = total", sym).isEqualTo(r.get("closedCount").asInt());
            assertThat(d(r.get("option").get("realizedPnl").asText()).add(d(r.get("stock").get("realizedPnl").asText())))
                    .as("%s option + stock pnl = total", sym).isEqualByComparingTo(d(r.get("realizedPnl").asText()));

            // counts
            int inc = g.get("includedCount").asInt();
            int nq = g.get("noQuoteCount").asInt();
            int ep = g.get("expiredPendingCount").asInt();
            int optCount = g.get("optionPositionCount").asInt();
            assertThat(g.get("includedCount").isInt()).isTrue();
            assertThat(g.get("noQuoteCount").isInt()).isTrue();
            assertThat(g.get("expiredPendingCount").isInt()).isTrue();
            assertThat(g.get("optionPositionCount").isInt()).isTrue();
            assertThat(g.get("hasStockPosition").isBoolean()).isTrue();
            boolean stock = g.get("hasStockPosition").asBoolean();
            assertThat(inc + nq + ep).as("%s included + noQuote + expired = option rows + stock row", sym)
                    .isEqualTo(optCount + (stock ? 1 : 0));
            assertThat(optCount + (stock ? 1 : 0)).as("%s has at least one position (Q196)", sym).isPositive();

            // null rules
            if (inc == 0) {
                assertThat(g.get("marketValue").isNull()).as("%s includedCount 0 → marketValue null", sym).isTrue();
                assertThat(g.get("unrealizedPnl").isNull()).as("%s includedCount 0 → unrealizedPnl null", sym)
                        .isTrue();
            } else {
                assertMoney(g.get("marketValue"), sym + ".marketValue");
                assertMoney(g.get("unrealizedPnl"), sym + ".unrealizedPnl");
            }
            if (optCount == 0) {
                assertThat(g.get("netPremium").isNull()).as("%s no option rows → netPremium null", sym).isTrue();
            } else {
                assertMoney(g.get("netPremium"), sym + ".netPremium");
            }
            assertMoney(g.get("totalPnl"), sym + ".totalPnl");
            BigDecimal upnl = g.get("unrealizedPnl").isNull() ? BigDecimal.ZERO : d(g.get("unrealizedPnl").asText());
            assertThat(d(g.get("totalPnl").asText())).as("%s totalPnl = realized + (unrealized ?? 0)", sym)
                    .isEqualByComparingTo(d(r.get("realizedPnl").asText()).add(upnl));
            assertThat(text(g.get("excludedLabel"))).as("%s excludedLabel", sym)
                    .isEqualTo(expectedExcludedLabel(nq, ep));
        }
        assertThat(symbols).as("groups ordered by symbol ascending (Q203), unique")
                .isSortedAccordingTo(String::compareTo).doesNotHaveDuplicates();
    }

    JsonNode pnlSummaryAll(String account) {
        String saved = accountId;
        accountId = account;
        try {
            Resp r = get(PNL_SUMMARY);
            assertThat(r.status()).as("GET %s -> %s", PNL_SUMMARY, r.body()).isEqualTo(200);
            return r.body();
        } finally {
            accountId = saved;
        }
    }

    /** pnl-summary (no dates) bySymbol rows as "SYMBOL | count pnl (opt · stock)". */
    List<String> pnlBySymbol(String account) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : pnlSummaryAll(account).get("bySymbol")) {
            out.add(row.get("symbol").asText() + " | " + part(row) + " (" + part(row.get("option")) + " · "
                    + part(row.get("stock")) + ")");
        }
        return out;
    }

    /**
     * Contract 一致性 (规则 2 / 8): the summary agrees with the other endpoints read right now — group set = symbols of
     * the two position endpoints; per symbol the row counts / statuses / sums and openingNetTotal of the rows;
     * realized = pnl-summary bySymbol (no dates) or zero; Σ groups = unrealized-summary total.
     */
    void assertConsistent(String account) {
        JsonNode us = underlying(account);
        JsonNode opts = optionPositions(account).get("positions");
        JsonNode stocks = stockPositions(account).get("positions");
        JsonNode pnl = pnlSummaryAll(account);
        JsonNode unreal = summary(account);

        assertThat(us.get("asOfDate").asText()).as("asOfDate = option-positions.asOfDate")
                .isEqualTo(optionPositions(account).get("asOfDate").asText());

        // expected per symbol from the rows
        Map<String, int[]> counts = new LinkedHashMap<>(); // optCount, hasStock, ok, nq, ep
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>(); // mv, pnl, netPremium
        TreeSet<String> symbols = new TreeSet<>();
        List<JsonNode> rows = new ArrayList<>();
        opts.forEach(rows::add);
        stocks.forEach(rows::add);
        for (JsonNode row : rows) {
            String sym = row.get("symbol").asText();
            symbols.add(sym);
            int[] c = counts.computeIfAbsent(sym, k -> new int[5]);
            BigDecimal[] s = sums.computeIfAbsent(sym,
                    k -> new BigDecimal[] {d("0.00"), d("0.00"), d("0.00")});
            boolean isOption = row.has("openingNetTotal");
            if (isOption) {
                c[0]++;
                s[2] = s[2].add(d(row.get("openingNetTotal").asText()));
            } else {
                c[1] = 1;
            }
            switch (row.get("unrealizedStatus").asText()) {
                case "OK" -> {
                    c[2]++;
                    s[0] = s[0].add(d(row.get("marketValue").asText()));
                    s[1] = s[1].add(d(row.get("unrealizedPnl").asText()));
                }
                case "NO_QUOTE" -> c[3]++;
                case "EXPIRED_PENDING" -> c[4]++;
                default -> throw new AssertionError("unknown unrealizedStatus " + row);
            }
        }

        List<String> groupSymbols = new ArrayList<>();
        us.get("groups").forEach(g -> groupSymbols.add(g.get("symbol").asText()));
        assertThat(groupSymbols).as("group set = symbols of option-positions ∪ stock-positions (Q196)")
                .containsExactlyElementsOf(symbols);

        Map<String, JsonNode> bySymbol = new LinkedHashMap<>();
        pnl.get("bySymbol").forEach(r -> bySymbol.put(r.get("symbol").asText(), r));

        BigDecimal mvSum = d("0.00");
        BigDecimal pnlSum = d("0.00");
        int incSum = 0;
        int nqSum = 0;
        int epSum = 0;
        for (JsonNode g : us.get("groups")) {
            String sym = g.get("symbol").asText();
            int[] c = counts.get(sym);
            BigDecimal[] s = sums.get(sym);
            assertThat(g.get("optionPositionCount").asInt()).as("%s optionPositionCount", sym).isEqualTo(c[0]);
            assertThat(g.get("hasStockPosition").asBoolean()).as("%s hasStockPosition", sym).isEqualTo(c[1] == 1);
            assertThat(g.get("includedCount").asInt() + "/" + g.get("noQuoteCount").asInt() + "/"
                    + g.get("expiredPendingCount").asInt()).as("%s counts = row statuses", sym)
                    .isEqualTo(c[2] + "/" + c[3] + "/" + c[4]);
            if (c[2] > 0) {
                assertThat(g.get("marketValue").asText()).as("%s marketValue = Σ OK rows", sym)
                        .isEqualTo(s[0].toPlainString());
                assertThat(g.get("unrealizedPnl").asText()).as("%s unrealizedPnl = Σ OK rows", sym)
                        .isEqualTo(s[1].toPlainString());
                mvSum = mvSum.add(s[0]);
                pnlSum = pnlSum.add(s[1]);
            }
            if (c[0] > 0) {
                assertThat(g.get("netPremium").asText()).as("%s netPremium = Σ openingNetTotal", sym)
                        .isEqualTo(s[2].toPlainString());
            }
            incSum += c[2];
            nqSum += c[3];
            epSum += c[4];

            JsonNode r = g.get("realized");
            JsonNode p = bySymbol.get(sym);
            if (p == null) {
                assertThat(grpRealized(r)).as("%s has no history → zero realized", sym)
                        .isEqualTo("0 0.00 (0 0.00 · 0 0.00)");
            } else {
                assertThat(grpRealized(r)).as("%s realized = pnl-summary bySymbol (no dates)", sym)
                        .isEqualTo(grpRealized(p));
            }
        }

        JsonNode total = unreal.get("total");
        assertThat(mvSum.toPlainString() + " " + pnlSum.toPlainString() + " " + incSum + "/" + nqSum + "/" + epSum)
                .as("Σ groups = unrealized-summary total (规则 8)")
                .isEqualTo(grp(total));
    }

    static String grpRealized(JsonNode r) {
        return part(r) + " (" + part(r.get("option")) + " · " + part(r.get("stock")) + ")";
    }

    JsonNode closedTrades(String account, String query) {
        String saved = accountId;
        accountId = account;
        try {
            Resp r = get(CLOSED_TRADES + (query.isEmpty() ? "" : "?" + query));
            assertThat(r.status()).as("GET %s?%s -> %s", CLOSED_TRADES, query, r.body()).isEqualTo(200);
            return r.body();
        } finally {
            accountId = saved;
        }
    }
}
