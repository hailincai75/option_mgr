package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-040 stock realized P&L in 已平仓 / 盈亏汇总 / 首页, end-to-end over HTTP (real port, in-memory H2).
 * "Now" = 2026-11-13 10:00 New York (EST) = 2026-11-13T15:00:00Z. Option-trade and stock-trade id sequences are
 * restarted at 1 before every test so scenario M gives O1–O4 = option #1–#4 and S1–S7 = stock #1–#7 as in the
 * requirement (the two sequences collide on purpose: rows are identified by type + id). Tickers are fictional.
 */
class Feat040StockPnlInSummariesSystemTest extends OptionTradeSystemTestBase {

    static final String CLOSED = "/api/closed-trades";
    static final String SUM = "/api/pnl-summary";
    static final String DASH = "/api/dashboard";
    static final String STOCKS = "/api/stock-trades";
    static final String CLOSES = "/api/option-trades/closes";
    static final String EXPIRATIONS = "/api/option-trades/expirations";

    /** label → id, per type. */
    final Map<String, Long> optIds = new LinkedHashMap<>();
    final Map<String, Long> stockIds = new LinkedHashMap<>();
    /** "OPTION#4" / "STOCK#4" → label. */
    final Map<String, String> labels = new LinkedHashMap<>();

    static final List<String> ALL_ROW_FIELDS = List.of("type", "id", "tradedAt", "tradedAtLocal", "symbol",
            "expirationDate", "strike", "optionType", "contractLabel", "action", "closedDirection", "contracts",
            "multiplier", "shares", "openingNetTotal", "openingNetPerContract", "costPerShare", "closeNetAmount",
            "closeNetPerContract", "pricePerShare", "realizedPnl", "note", "coveredLots", "soldLots", "hasAuditLog");

    static final List<String> OPTION_ONLY = List.of("expirationDate", "strike", "optionType", "contractLabel",
            "closedDirection", "contracts", "multiplier", "openingNetPerContract", "closeNetPerContract", "coveredLots");
    static final List<String> STOCK_ONLY = List.of("shares", "costPerShare", "pricePerShare", "soldLots");

    @Override
    Instant now() {
        return Instant.parse("2026-11-13T15:00:00Z");
    }

    @BeforeEach
    void restartIds() {
        // Test DB only (in-memory H2, profile "test"); the requirement numbers the records from an empty database.
        jdbc.execute("ALTER TABLE option_trade ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE stock_trade ALTER COLUMN id RESTART WITH 1");
    }

    // ------------------------------------------------------------------ write helpers

    void opt(String label, String action, String symbol, String exp, String strike, String type, String net,
             String at) {
        JsonNode t = okTrade(trade(action, 1, symbol, exp, strike, type, net, at, 100));
        remember(label, "OPTION", t.get("id").asLong());
    }

    void close(String label, String action, String symbol, String exp, String strike, String type, String net,
               String at) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", 1);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        Resp r = postRaw(CLOSES, n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("close %s -> %s", n, r.body()).isEqualTo(201);
        remember(label, "OPTION", r.body().get("id").asLong());
    }

    void remember(String label, String type, long id) {
        (type.equals("OPTION") ? optIds : stockIds).put(label, id);
        labels.put(type + "#" + id, label);
    }

    JsonNode stock(String label, String type, String symbol, int shares, String amount, String time, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put(type.equals("TRANSFER_IN") ? "tradeDate" : "tradedAtLocal", time);
        if (note == null) {
            n.putNull("note");
        } else {
            n.put("note", note);
        }
        Resp r = postRaw(STOCKS, n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("stock %s -> %s", n, r.body()).isEqualTo(201);
        if (label != null) {
            remember(label, "STOCK", r.body().get("id").asLong());
        }
        return r.body();
    }

    JsonNode putStock(String label, int shares, String amount, String timeKey, String time, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("shares", shares);
        n.put("amount", amount);
        n.put(timeKey, time);
        if (note == null) {
            n.putNull("note");
        } else {
            n.put("note", note);
        }
        Resp r = exchange(HttpMethod.PUT, STOCKS + "/" + stockIds.get(label), n.toString(),
                MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("PUT stock %s %s -> %s", label, n, r.body()).isEqualTo(200);
        return r.body();
    }

    void deleteStock(String label) {
        Resp r = exchange(HttpMethod.DELETE, STOCKS + "/" + stockIds.get(label), null, MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("DELETE stock %s -> %s", label, r.body()).isEqualTo(204);
    }

    /** Scenario M of the requirement, in the current account. Cash 19,499.35. */
    void scenarioM() {
        deposit("20000.00", "2026-10-26T10:00");
        stock("S1", "TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01", null);
        stock("S2", "TRANSFER_IN", "QRTV", 50, "400.00", "2026-10-01", null);
        opt("O1", "STO", "IXQZ", "2026-11-20", "7", "PUT", "45.00", "2026-10-27T10:00");
        close("O2", "BTC", "IXQZ", "2026-11-20", "7", "PUT", "10.00", "2026-10-30T10:00");
        stock("S3", "SELL", "QRTV", 50, "380.00", "2026-10-31T23:30", "盘后");
        stock("S4", "BUY", "IXQZ", 200, "1500.65", "2026-11-03T10:00", null);
        stock("S5", "BUY", "XYZ", 300, "1000.00", "2026-11-04T10:00", null);
        opt("O3", "STO", "XYZ", "2026-12-18", "3", "CALL", "25.00", "2026-11-04T11:00");
        stock("S6", "SELL", "IXQZ", 150, "1200.00", "2026-11-05T10:00", null);
        stock("S7", "SELL", "XYZ", 100, "400.00", "2026-11-06T10:00", null);
        close("O4", "BTC", "XYZ", "2026-12-18", "3", "CALL", "40.00", "2026-11-09T10:00");
        assertSummary("19499.35", "0.00", "19499.35");
        assertThat(optIds.values()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(stockIds.values()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }

    // ------------------------------------------------------------------ read helpers

    /** GET /api/closed-trades (200) with the contract shape of every row checked. */
    JsonNode closed(String query) {
        Resp r = get(CLOSED + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("GET closed %s -> %s", query, r.body()).isEqualTo(200);
        JsonNode b = r.body();
        assertThat(b.get("filteredCount").asInt()).isEqualTo(b.get("totalItems").asInt());
        assertThat(b.get("filteredRealizedPnlTotal").isTextual()).isTrue();
        Set<String> keys = new HashSet<>();
        for (JsonNode row : b.get("items")) {
            assertRowShape(row);
            assertThat(keys.add(key(row))).as("type + id unique within a page").isTrue();
        }
        return b;
    }

    /** GET with an already-encoded path+query sent verbatim (no URI-template encoding); must be 200. */
    JsonNode rawGet(String encodedPathAndQuery) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.set("X-Account-Id", accountId);
        org.springframework.http.ResponseEntity<String> r = http.exchange(
                java.net.URI.create(http.getRootUri() + encodedPathAndQuery), HttpMethod.GET,
                new org.springframework.http.HttpEntity<Void>(h), String.class);
        assertThat(r.getStatusCode().value()).as("GET %s -> %s", encodedPathAndQuery, r.getBody()).isEqualTo(200);
        try {
            return json.readTree(r.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static String key(JsonNode row) {
        return row.get("type").asText() + "#" + row.get("id").asLong();
    }

    String label(JsonNode row) {
        return labels.getOrDefault(key(row), key(row));
    }

    void assertRowShape(JsonNode row) {
        for (String f : ALL_ROW_FIELDS) {
            assertThat(row.has(f)).as("field %s present in %s", f, row).isTrue();
        }
        assertThat(row.get("id").isIntegralNumber()).isTrue();
        assertThat(row.get("tradedAt").asText()).endsWith("Z");
        assertThat(row.get("hasAuditLog").isBoolean()).isTrue();
        for (String f : List.of("openingNetTotal", "closeNetAmount", "realizedPnl")) {
            assertThat(row.get(f).isTextual()).as("%s is a string", f).isTrue();
            assertThat(row.get(f).asText()).matches("-?\\d+\\.\\d{2}");
        }
        BigDecimal open = new BigDecimal(row.get("openingNetTotal").asText());
        BigDecimal close = new BigDecimal(row.get("closeNetAmount").asText());
        assertThat(open.add(close)).isEqualByComparingTo(new BigDecimal(row.get("realizedPnl").asText()));
        String type = row.get("type").asText();
        assertThat(type).isIn("OPTION", "STOCK");
        if (type.equals("STOCK")) {
            for (String f : OPTION_ONLY) {
                assertThat(row.get(f).isNull()).as("STOCK row: %s is null in %s", f, row).isTrue();
            }
            assertThat(row.get("action").asText()).isEqualTo("SELL");
            assertThat(row.get("shares").isIntegralNumber()).isTrue();
            assertThat(row.get("costPerShare").asText()).matches("\\d+\\.\\d{4}");
            assertThat(row.get("pricePerShare").asText()).matches("\\d+\\.\\d{4}");
            assertThat(open.signum()).as("openingNetTotal = −cost < 0").isNegative();
            assertThat(close.signum()).isPositive();
            BigDecimal lots = BigDecimal.ZERO;
            for (JsonNode l : row.get("soldLots")) {
                assertThat(l.get("type").asText()).isIn("BUY", "TRANSFER_IN");
                assertThat(l.get("netAmount").asText()).matches("-\\d+\\.\\d{2}");
                assertThat(l.get("shares").isIntegralNumber()).isTrue();
                lots = lots.add(new BigDecimal(l.get("netAmount").asText()));
                if (l.get("type").asText().equals("TRANSFER_IN")) {
                    assertThat(l.get("tradeDate").isTextual()).isTrue();
                    assertThat(l.get("tradedAtLocal").asText()).isEqualTo(l.get("tradeDate").asText() + "T00:00:00");
                } else {
                    assertThat(l.get("tradeDate").isNull()).isTrue();
                }
            }
            assertThat(lots).as("soldLots sum = openingNetTotal").isEqualByComparingTo(open);
            // same numbers as the stock record (FEAT-011)
            JsonNode s = get(STOCKS + "/" + row.get("id").asLong()).body();
            assertThat(s.get("type").asText()).isEqualTo("SELL");
            assertThat(s.get("symbol").asText()).isEqualTo(row.get("symbol").asText());
            assertThat(s.get("shares").asInt()).isEqualTo(row.get("shares").asInt());
            assertThat(s.get("cashChange").asText()).isEqualTo(row.get("closeNetAmount").asText());
            assertThat(new BigDecimal(s.get("soldCost").asText()).negate()).isEqualByComparingTo(open);
            assertThat(s.get("realizedPnl").asText()).isEqualTo(row.get("realizedPnl").asText());
            assertThat(s.get("pricePerShare").asText()).isEqualTo(row.get("pricePerShare").asText());
            assertThat(s.get("tradedAt").asText()).isEqualTo(row.get("tradedAt").asText());
            assertThat(s.get("note")).isEqualTo(row.get("note"));
            assertThat(s.get("hasAuditLog")).isEqualTo(row.get("hasAuditLog"));
            assertThat(s.get("soldLots").size()).isEqualTo(row.get("soldLots").size());
        } else {
            for (String f : STOCK_ONLY) {
                assertThat(row.get(f).isNull()).as("OPTION row: %s is null in %s", f, row).isTrue();
            }
            assertThat(row.get("action").asText()).isIn("BTC", "STC", "EXP");
            assertThat(row.get("contracts").isIntegralNumber()).isTrue();
            assertThat(row.get("multiplier").isIntegralNumber()).isTrue();
            assertThat(row.get("coveredLots").isArray()).isTrue();
            assertThat(row.get("contractLabel").isTextual()).isTrue();
        }
    }

    /** "label type tradedAtLocal symbol contractLabel action closedDirection qty open openPer close closePer pnl". */
    String line(JsonNode r) {
        boolean stock = r.get("type").asText().equals("STOCK");
        return String.join(" ", label(r), r.get("type").asText(), r.get("tradedAtLocal").asText(),
                r.get("symbol").asText(), r.get("contractLabel").asText(), r.get("action").asText(),
                r.get("closedDirection").asText(), r.get(stock ? "shares" : "contracts").asText(),
                r.get("openingNetTotal").asText(),
                r.get(stock ? "costPerShare" : "openingNetPerContract").asText(), r.get("closeNetAmount").asText(),
                r.get(stock ? "pricePerShare" : "closeNetPerContract").asText(), r.get("realizedPnl").asText());
    }

    List<String> lines(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(line(i)));
        return out;
    }

    /** Labels of the rows; asserts the count and total of the whole filter. */
    List<String> labelsOf(String query, int count, String total) {
        JsonNode b = closed(query + (query.isEmpty() ? "" : "&") + "size=100");
        assertThat(b.get("filteredCount").asInt()).as("count for %s", query).isEqualTo(count);
        assertThat(b.get("filteredRealizedPnlTotal").asText()).as("total for %s", query).isEqualTo(total);
        List<String> out = new ArrayList<>();
        b.get("items").forEach(i -> out.add(label(i)));
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode i : b.get("items")) {
            sum = sum.add(new BigDecimal(i.get("realizedPnl").asText()));
        }
        assertThat(sum).isEqualByComparingTo(new BigDecimal(total));
        assertThat(out).hasSize(count);
        return out;
    }

    JsonNode row(JsonNode page, String label) {
        for (JsonNode i : page.get("items")) {
            if (label(i).equals(label)) {
                return i;
            }
        }
        throw new AssertionError("no row " + label + " in " + page);
    }

    static String part(JsonNode p) {
        assertThat(p).as("breakdown object present").isNotNull();
        assertThat(p.isObject()).isTrue();
        assertThat(p.get("closedCount").isIntegralNumber()).isTrue();
        assertThat(p.get("realizedPnl").isTextual()).isTrue();
        return p.get("closedCount").asInt() + " " + p.get("realizedPnl").asText();
    }

    /**
     * GET /api/pnl-summary with all invariants: option + stock = all for the total and every row; groups add up to
     * the totals; totals equal /api/closed-trades for the same range, also per type.
     */
    JsonNode sum(String query) {
        Resp r = get(SUM + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("GET summary %s -> %s", query, r.body()).isEqualTo(200);
        JsonNode b = r.body();
        checkParts(b, b.get("closedCount").asInt(), b.get("realizedPnlTotal").asText());
        for (String group : List.of("bySymbol", "byMonth")) {
            int c = 0;
            int oc = 0;
            int sc = 0;
            BigDecimal t = BigDecimal.ZERO;
            BigDecimal ot = BigDecimal.ZERO;
            BigDecimal st = BigDecimal.ZERO;
            for (JsonNode g : b.get(group)) {
                checkParts(g, g.get("closedCount").asInt(), g.get("realizedPnl").asText());
                assertThat(g.get("closedCount").asInt()).isPositive();
                c += g.get("closedCount").asInt();
                oc += g.get("option").get("closedCount").asInt();
                sc += g.get("stock").get("closedCount").asInt();
                t = t.add(new BigDecimal(g.get("realizedPnl").asText()));
                ot = ot.add(new BigDecimal(g.get("option").get("realizedPnl").asText()));
                st = st.add(new BigDecimal(g.get("stock").get("realizedPnl").asText()));
            }
            assertThat(c).as("%s counts", group).isEqualTo(b.get("closedCount").asInt());
            assertThat(oc).isEqualTo(b.get("option").get("closedCount").asInt());
            assertThat(sc).isEqualTo(b.get("stock").get("closedCount").asInt());
            assertThat(t).isEqualByComparingTo(new BigDecimal(b.get("realizedPnlTotal").asText()));
            assertThat(ot).isEqualByComparingTo(new BigDecimal(b.get("option").get("realizedPnl").asText()));
            assertThat(st).isEqualByComparingTo(new BigDecimal(b.get("stock").get("realizedPnl").asText()));
        }
        String q = query.isEmpty() ? "" : query + "&";
        JsonNode all = closed(q + "size=1");
        assertThat(all.get("filteredCount").asInt()).isEqualTo(b.get("closedCount").asInt());
        assertThat(all.get("filteredRealizedPnlTotal").asText()).isEqualTo(b.get("realizedPnlTotal").asText());
        JsonNode o = closed(q + "type=OPTION&size=1");
        assertThat(part(b.get("option"))).isEqualTo(
                o.get("filteredCount").asInt() + " " + o.get("filteredRealizedPnlTotal").asText());
        JsonNode s = closed(q + "type=STOCK&size=1");
        assertThat(part(b.get("stock"))).isEqualTo(
                s.get("filteredCount").asInt() + " " + s.get("filteredRealizedPnlTotal").asText());
        return b;
    }

    static void checkParts(JsonNode n, int count, String total) {
        part(n.get("option"));
        part(n.get("stock"));
        assertThat(n.get("option").get("closedCount").asInt() + n.get("stock").get("closedCount").asInt())
                .isEqualTo(count);
        assertThat(new BigDecimal(n.get("option").get("realizedPnl").asText())
                .add(new BigDecimal(n.get("stock").get("realizedPnl").asText())))
                .isEqualByComparingTo(new BigDecimal(total));
    }

    /** "KEY count all | optCount opt | stockCount stock" per row. */
    static List<String> sumRows(JsonNode arr, String key) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.get(key).asText() + " " + x.get("closedCount").asInt() + " "
                + x.get("realizedPnl").asText() + " | " + part(x.get("option")) + " | " + part(x.get("stock"))));
        return out;
    }

    static String totals(JsonNode b) {
        return b.get("closedCount").asInt() + " " + b.get("realizedPnlTotal").asText() + " | " + part(b.get("option"))
                + " | " + part(b.get("stock"));
    }

    JsonNode dash() {
        Resp r = get(DASH);
        assertThat(r.status()).isEqualTo(200);
        JsonNode d = r.body();
        assertThat(d.get("stockPositions").get("positionCount").isIntegralNumber()).isTrue();
        assertThat(d.get("stockPositions").get("positionCount").asInt())
                .as("= GET /api/stock-positions positionCount")
                .isEqualTo(get("/api/stock-positions").body().get("positionCount").asInt());
        // FEAT-040: home has only the totals, the option positions object is unchanged
        assertThat(d.get("positions").has("stockPositionCount")).isFalse();
        return d;
    }

    static String pnl(JsonNode o) {
        return o.get("total").asText() + " (" + o.get("closedCount").asInt() + ")";
    }

    long createAccount(String name) {
        String saved = accountId;
        accountId = null;
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        Resp r = postRaw("/api/accounts", n.toString(), MediaType.APPLICATION_JSON);
        accountId = saved;
        assertThat(r.status()).as("create account %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    static final List<String> M_ROWS = List.of(
            "O4 OPTION 2026-11-09T10:00:00 XYZ XYZ 2026-12-18 3 C BTC SHORT 1 25.00 25.00 -40.00 -40.00 -15.00",
            "S7 STOCK 2026-11-06T10:00:00 XYZ null SELL null 100 -333.33 3.3333 400.00 4.0000 66.67",
            "S6 STOCK 2026-11-05T10:00:00 IXQZ null SELL null 150 -1125.16 7.5011 1200.00 8.0000 74.84",
            "S3 STOCK 2026-10-31T23:30:00 QRTV null SELL null 50 -400.00 8.0000 380.00 7.6000 -20.00",
            "O2 OPTION 2026-10-30T10:00:00 IXQZ IXQZ 2026-11-20 7 P BTC SHORT 1 45.00 45.00 -10.00 -10.00 35.00");

    // ------------------------------------------------------------------ AC 1, 2

    @Test
    @DisplayName("AC1 已平仓：期权和股票合并、顺序与各列；股票行期权字段为 null；买入/转入不出现；无历史")
    void ac1Merged() {
        scenarioM();
        JsonNode b = closed("");
        assertThat(b.get("totalItems").asInt()).isEqualTo(5);
        assertThat(b.get("totalPages").asInt()).isEqualTo(1);
        assertThat(b.get("page").asInt()).isEqualTo(1);
        assertThat(b.get("size").asInt()).isEqualTo(20);
        assertThat(b.get("filteredCount").asInt()).isEqualTo(5);
        assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("141.51");
        assertThat(lines(b)).containsExactlyElementsOf(M_ROWS);
        assertThat(row(b, "S3").get("note").asText()).isEqualTo("盘后");
        assertThat(row(b, "S6").get("note").isNull()).isTrue();
        assertThat(row(b, "O4").get("multiplier").asInt()).isEqualTo(100);
        assertThat(row(b, "S7").get("tradedAt").asText()).isEqualTo("2026-11-06T15:00:00Z");
        // EDT on 10-31: 23:30 local = 03:30Z the next day
        assertThat(row(b, "S3").get("tradedAt").asText()).isEqualTo("2026-11-01T03:30:00Z");
        for (JsonNode i : b.get("items")) {
            assertThat(i.get("hasAuditLog").asBoolean()).as("no 历史 on %s", label(i)).isFalse();
        }
        // buys and transfers are not records
        for (String s : List.of("S1", "S2", "S4", "S5")) {
            assertThat(lines(b)).noneMatch(l -> l.startsWith(s + " "));
        }
    }

    @Test
    @DisplayName("AC2 被卖出批次：S6 两批（转入 100 −750.00、买入 50 −375.16），S7 一批；O2 被平批次不变")
    void ac2SoldLots() {
        scenarioM();
        JsonNode b = closed("");
        List<String> s6 = new ArrayList<>();
        row(b, "S6").get("soldLots").forEach(l -> s6.add(labels.get("STOCK#" + l.get("tradeId").asLong()) + " "
                + l.get("type").asText() + " " + l.get("tradedAt").asText() + " " + l.get("tradedAtLocal").asText()
                + " " + l.get("tradeDate").asText() + " " + l.get("shares").asInt() + " "
                + l.get("netAmount").asText()));
        assertThat(s6).containsExactly(
                "S1 TRANSFER_IN 2026-10-01T04:00:00Z 2026-10-01T00:00:00 2026-10-01 100 -750.00",
                "S4 BUY 2026-11-03T15:00:00Z 2026-11-03T10:00:00 null 50 -375.16");
        List<String> s7 = new ArrayList<>();
        row(b, "S7").get("soldLots").forEach(l -> s7.add(labels.get("STOCK#" + l.get("tradeId").asLong()) + " "
                + l.get("type").asText() + " " + l.get("tradedAtLocal").asText() + " " + l.get("shares").asInt()
                + " " + l.get("netAmount").asText()));
        assertThat(s7).containsExactly("S5 BUY 2026-11-04T10:00:00 100 -333.33");
        List<String> s3 = new ArrayList<>();
        row(b, "S3").get("soldLots").forEach(l -> s3.add(labels.get("STOCK#" + l.get("tradeId").asLong()) + " "
                + l.get("type").asText() + " " + l.get("shares").asInt() + " " + l.get("netAmount").asText()));
        assertThat(s3).containsExactly("S2 TRANSFER_IN 50 -400.00");

        JsonNode o2 = row(b, "O2").get("coveredLots");
        assertThat(o2).hasSize(1);
        assertThat(o2.get(0).get("tradeId").asLong()).isEqualTo(optIds.get("O1"));
        assertThat(o2.get(0).get("action").asText()).isEqualTo("STO");
        assertThat(o2.get(0).get("tradedAtLocal").asText()).isEqualTo("2026-10-27T10:00:00");
        assertThat(o2.get(0).get("contracts").asInt()).isEqualTo(1);
        assertThat(o2.get(0).get("netAmount").asText()).isEqualTo("45.00");
    }

    // ------------------------------------------------------------------ AC 3, 4, 5

    @Test
    @DisplayName("AC3 类型筛选与合计；标的筛选含两类；非法类型 400")
    void ac3TypeFilter() {
        scenarioM();
        assertThat(labelsOf("type=STOCK", 3, "121.51")).containsExactly("S7", "S6", "S3");
        assertThat(labelsOf("type=OPTION", 2, "20.00")).containsExactly("O4", "O2");
        assertThat(labelsOf("symbol=ixqz", 2, "109.84")).containsExactly("S6", "O2");
        assertThat(labelsOf("symbol=IXQZ&type=STOCK", 1, "74.84")).containsExactly("S6");
        assertThat(labelsOf("symbol=IXQZ&type=OPTION", 1, "35.00")).containsExactly("O2");
        assertThat(labelsOf("symbol=QFZ", 0, "0.00")).isEmpty();
        assertThat(labelsOf("type=", 5, "141.51")).hasSize(5);
        // real whitespace on the wire (the String-path helpers would double-encode %20)
        JsonNode blank = rawGet(CLOSED + "?type=%20%20&size=100");
        assertThat(blank.get("filteredCount").asInt()).as("blank type = both").isEqualTo(5);
        JsonNode spaced = rawGet(CLOSED + "?symbol=%20ixqz%20&type=STOCK");
        assertThat(spaced.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(spaced.get("filteredRealizedPnlTotal").asText()).isEqualTo("74.84");

        for (String bad : List.of("FUTURE", "stock", "Option", "OPTION,STOCK", "SELL")) {
            Resp r = get(CLOSED + "?type=" + bad);
            assertErrorShape(r, 400, "BAD_REQUEST", CLOSED);
            assertThat(r.body().get("details")).as("details for type=%s", bad).hasSize(1);
            assertThat(r.body().get("details").get(0).get("field").asText()).isEqualTo("type");
            assertThat(r.body().get("details").get(0).get("message").asText()).isEqualTo("类型只能是 OPTION 或 STOCK");
        }
        // reported together with other unparsable params, and before VALIDATION_ERROR problems
        Resp both = get(CLOSED + "?type=FUTURE&action=XXX");
        assertErrorShape(both, 400, "BAD_REQUEST", CLOSED);
        assertThat(detailFields(both)).containsExactlyInAnyOrder("type", "action");
        Resp order = get(CLOSED + "?type=FUTURE&startDate=2026-11-07&endDate=2026-11-06");
        assertErrorShape(order, 400, "BAD_REQUEST", CLOSED);
        assertThat(detailFields(order)).containsExactly("type");
    }

    @Test
    @DisplayName("AC4 Q90 期权专用筛选（方向/到期日/Call·Put/行权价）只匹配期权行；类型=股票 + 期权条件 = 空结果不报错")
    void ac4OptionOnlyFilters() {
        scenarioM();
        assertThat(labelsOf("action=BTC", 2, "20.00")).containsExactly("O4", "O2");
        assertThat(labelsOf("action=BTC,STC,EXP", 2, "20.00")).containsExactly("O4", "O2");
        assertThat(labelsOf("action=STC", 0, "0.00")).isEmpty();
        assertThat(labelsOf("expirationDate=2026-11-20", 1, "35.00")).containsExactly("O2");
        assertThat(labelsOf("optionType=CALL", 1, "-15.00")).containsExactly("O4");
        assertThat(labelsOf("optionType=PUT", 1, "35.00")).containsExactly("O2");
        assertThat(labelsOf("strike=3", 1, "-15.00")).containsExactly("O4");
        assertThat(labelsOf("strike=3.00", 1, "-15.00")).containsExactly("O4");
        assertThat(labelsOf("symbol=XYZ&strike=3", 1, "-15.00")).containsExactly("O4");
        assertThat(labelsOf("type=OPTION&action=BTC", 2, "20.00")).containsExactly("O4", "O2");
        // type=STOCK + any option-only condition → empty, not an error
        assertThat(labelsOf("type=STOCK&strike=3", 0, "0.00")).isEmpty();
        assertThat(labelsOf("type=STOCK&action=BTC", 0, "0.00")).isEmpty();
        assertThat(labelsOf("type=STOCK&expirationDate=2026-11-20", 0, "0.00")).isEmpty();
        assertThat(labelsOf("type=STOCK&optionType=CALL", 0, "0.00")).isEmpty();
        JsonNode empty = closed("type=STOCK&strike=3");
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("totalPages").asInt()).isZero();
        // empty option-only params are "not set": stocks stay
        assertThat(labelsOf("expirationDate=&strike=&optionType=&action=", 5, "141.51")).hasSize(5);
        // SELL is not an action filter value
        Resp sell = get(CLOSED + "?action=SELL");
        assertErrorShape(sell, 400, "BAD_REQUEST", CLOSED);
        assertThat(detailFields(sell)).containsExactly("action");
        assertThat(sell.body().get("details").get(0).get("message").asText()).isEqualTo("方向只能是 BTC、STC、EXP");
    }

    @Test
    @DisplayName("AC5 美东日期筛选：10-31 只有 S3（UTC 已是 11-01）；11-01 无；11-06..11-09 O4 S7；颠倒报错")
    void ac5Dates() {
        scenarioM();
        assertThat(labelsOf("startDate=2026-10-31&endDate=2026-10-31", 1, "-20.00")).containsExactly("S3");
        assertThat(labelsOf("startDate=2026-11-01&endDate=2026-11-01", 0, "0.00")).isEmpty();
        assertThat(labelsOf("startDate=2026-11-06&endDate=2026-11-09", 2, "51.67")).containsExactly("O4", "S7");
        assertThat(labelsOf("startDate=2026-11-05&endDate=2026-11-05", 1, "74.84")).containsExactly("S6");
        assertThat(labelsOf("endDate=2026-10-31", 2, "15.00")).containsExactly("S3", "O2");
        assertThat(labelsOf("startDate=2026-11-01", 3, "126.51")).containsExactly("O4", "S7", "S6");
        assertThat(labelsOf("startDate=2026-11-01&type=STOCK", 2, "141.51")).containsExactly("S7", "S6");
        Resp rev = get(CLOSED + "?startDate=2026-11-07&endDate=2026-11-06");
        assertErrorShape(rev, 400, "VALIDATION_ERROR", CLOSED);
        assertThat(rev.body().get("details").get(0).get("field").asText()).isEqualTo("endDate");
        assertThat(rev.body().get("details").get(0).get("message").asText()).isEqualTo("开始日期不能晚于结束日期");
    }

    // ------------------------------------------------------------------ AC 6, 7

    @Test
    @DisplayName("AC6 跨两类分页：每页 2 条 → O4 S7 / S6 S3 / O2 / 空；每页合计都是 5 笔 +141.51")
    void ac6Paging() {
        scenarioM();
        List<List<String>> pages = new ArrayList<>();
        for (int p = 1; p <= 4; p++) {
            JsonNode b = closed("page=" + p + "&size=2");
            assertThat(b.get("page").asInt()).isEqualTo(p);
            assertThat(b.get("size").asInt()).isEqualTo(2);
            assertThat(b.get("totalItems").asInt()).isEqualTo(5);
            assertThat(b.get("totalPages").asInt()).isEqualTo(3);
            assertThat(b.get("filteredCount").asInt()).isEqualTo(5);
            assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("141.51");
            List<String> l = new ArrayList<>();
            b.get("items").forEach(i -> l.add(label(i)));
            pages.add(l);
        }
        assertThat(pages).containsExactly(List.of("O4", "S7"), List.of("S6", "S3"), List.of("O2"), List.of());
        // filtered paging still across both types
        JsonNode p2 = closed("symbol=XYZ&page=2&size=1");
        assertThat(label(p2.get("items").get(0))).isEqualTo("S7");
        assertThat(p2.get("filteredRealizedPnlTotal").asText()).isEqualTo("51.67");
        // the same rows as one big page
        List<String> joined = new ArrayList<>();
        pages.forEach(joined::addAll);
        assertThat(labelsOf("", 5, "141.51")).containsExactlyElementsOf(joined);
    }

    @Test
    @DisplayName("AC7 同一时间股票在上（Q92）；两类 id 相同（期权 #6 与股票 #6）都出现、按 type+id 区分")
    void ac7SameTimeOrderAndIdCollision() {
        scenarioM();
        deposit("1000.00", "2026-11-10T09:00");
        opt("O5", "STO", "QRTV", "2026-12-18", "5", "PUT", "60.00", "2026-11-10T09:30");
        stock("S8", "BUY", "QRTV", 10, "70.00", "2026-11-10T09:30", null);
        stock("S9", "SELL", "QRTV", 10, "75.00", "2026-11-11T10:00", null);
        close("O6", "BTC", "QRTV", "2026-12-18", "5", "PUT", "20.00", "2026-11-11T10:00");
        assertThat(labelsOf("", 7, "186.51")).containsExactly("S9", "O6", "O4", "S7", "S6", "S3", "O2");
        JsonNode b = closed("size=2");
        assertThat(lines(b)).containsExactly(
                "S9 STOCK 2026-11-11T10:00:00 QRTV null SELL null 10 -70.00 7.0000 75.00 7.5000 5.00",
                "O6 OPTION 2026-11-11T10:00:00 QRTV QRTV 2026-12-18 5 P BTC SHORT 1 60.00 60.00 -20.00 -20.00 40.00");

        // ids collide: option #6 = O6 (QRTV BTC), stock #6 = S6 (IXQZ sale)
        assertThat(optIds.get("O6")).isEqualTo(6L);
        assertThat(stockIds.get("S6")).isEqualTo(6L);
        JsonNode all = closed("size=100");
        List<String> sixes = new ArrayList<>();
        for (JsonNode i : all.get("items")) {
            if (i.get("id").asLong() == 6) {
                sixes.add(i.get("type").asText() + " " + i.get("symbol").asText() + " " + i.get("action").asText());
            }
        }
        assertThat(sixes).containsExactly("OPTION QRTV BTC", "STOCK IXQZ SELL");

        // two stock sales at the same time: later entered (higher id) on top; options likewise below them
        stock("S10", "BUY", "QRTV", 2, "14.00", "2026-11-12T09:00", null);
        stock("S11", "SELL", "QRTV", 1, "8.00", "2026-11-12T10:00", null);
        stock("S12", "SELL", "QRTV", 1, "9.00", "2026-11-12T10:00", null);
        assertThat(labelsOf("startDate=2026-11-11", 4, "48.00")).containsExactly("S12", "S11", "S9", "O6");
    }

    @Test
    @DisplayName("边界：EXP 属于期权（type=OPTION 含 EXP）；与到期日 16:00 的股票卖出同一时间时股票在上；盈亏 0.00 的卖出计 1 笔")
    void expiryAndZeroPnl() {
        deposit("10000.00", "2026-11-02T09:00");
        opt("E1", "STO", "XYZ", "2026-11-06", "5", "PUT", "10.00", "2026-11-02T10:00");
        stock("Z1", "TRANSFER_IN", "QFZ", 100, "300.00", "2026-10-01", null);
        stock("Z2", "SELL", "QFZ", 100, "300.00", "2026-11-06T16:00", null);
        Resp e = postRaw(EXPIRATIONS, "{\"symbol\":\"XYZ\",\"expirationDate\":\"2026-11-06\",\"strike\":\"5\","
                + "\"optionType\":\"PUT\",\"contracts\":1}", MediaType.APPLICATION_JSON);
        assertThat(e.status()).as("expire -> %s", e.body()).isEqualTo(201);
        remember("EXP", "OPTION", e.body().get("id").asLong());

        assertThat(labelsOf("", 2, "10.00")).containsExactly("Z2", "EXP");
        JsonNode b = closed("");
        assertThat(row(b, "EXP").get("tradedAtLocal").asText()).isEqualTo("2026-11-06T16:00:00");
        assertThat(row(b, "Z2").get("tradedAtLocal").asText()).isEqualTo("2026-11-06T16:00:00");
        assertThat(row(b, "Z2").get("realizedPnl").asText()).isEqualTo("0.00");
        assertThat(row(b, "Z2").get("costPerShare").asText()).isEqualTo("3.0000");
        assertThat(labelsOf("type=OPTION", 1, "10.00")).containsExactly("EXP");
        assertThat(labelsOf("action=EXP", 1, "10.00")).containsExactly("EXP");
        assertThat(labelsOf("type=STOCK", 1, "0.00")).containsExactly("Z2");
        JsonNode s = sum("");
        assertThat(totals(s)).isEqualTo("2 10.00 | 1 10.00 | 1 0.00");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).containsExactly(
                "QFZ 1 0.00 | 0 0.00 | 1 0.00", "XYZ 1 10.00 | 1 10.00 | 0 0.00");
        assertThat(pnl(dash().get("realizedPnl"))).isEqualTo("10.00 (2)");
    }

    // ------------------------------------------------------------------ AC 8

    @Test
    @DisplayName("AC8 历史按类型+id：只改 S1 → 无历史；改 S6 → 只有股票 #6；改 O2 → 期权 #2（不是股票 #2）")
    void ac8HistoryByTypeAndId() {
        scenarioM();
        putStock("S1", 100, "750.00", "tradeDate", "2026-10-01", "期初");
        JsonNode b = closed("");
        for (JsonNode i : b.get("items")) {
            assertThat(i.get("hasAuditLog").asBoolean()).as("S1 edit does not mark %s", label(i)).isFalse();
        }
        assertThat(lines(b)).containsExactlyElementsOf(M_ROWS);

        putStock("S6", 150, "1200.00", "tradedAtLocal", "2026-11-05T10:00", "减仓");
        b = closed("");
        List<String> marked = new ArrayList<>();
        b.get("items").forEach(i -> {
            if (i.get("hasAuditLog").asBoolean()) {
                marked.add(key(i));
            }
        });
        assertThat(marked).containsExactly("STOCK#6");
        assertThat(row(b, "S6").get("note").asText()).isEqualTo("减仓");
        JsonNode audit = get("/api/audit-log?objectType=STOCK_TRADE&objectId=6").body();
        assertThat(audit.get("totalItems").asInt()).isEqualTo(1);
        assertThat(get("/api/audit-log?objectType=OPTION_TRADE&objectId=6").body().get("totalItems").asInt())
                .isZero();

        ObjectNode n = json.createObjectNode();
        n.put("contracts", 1);
        n.put("netAmount", "10.00");
        n.put("tradedAtLocal", "2026-10-30T10:00");
        n.put("note", "提前平仓");
        Resp r = exchange(HttpMethod.PUT, TRADES + "/" + optIds.get("O2"), n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("PUT O2 -> %s", r.body()).isEqualTo(200);
        b = closed("");
        marked.clear();
        b.get("items").forEach(i -> {
            if (i.get("hasAuditLog").asBoolean()) {
                marked.add(key(i));
            }
        });
        assertThat(marked).containsExactlyInAnyOrder("STOCK#6", "OPTION#2");
        assertThat(row(b, "S3").get("hasAuditLog").asBoolean()).isFalse();
        assertThat(row(b, "S7").get("hasAuditLog").asBoolean()).isFalse();
        assertThat(get("/api/audit-log?objectType=OPTION_TRADE&objectId=2").body().get("totalItems").asInt())
                .isEqualTo(1);
        // the S1 edit is audited under 股票交易 #1, not on any closed row; 股票交易 #2 (= S2) has none
        assertThat(get("/api/audit-log?objectType=STOCK_TRADE&objectId=2").body().get("totalItems").asInt())
                .isZero();

        // the stock row's symbol is directly usable on the stock list (link target)
        JsonNode stocks = get(STOCKS + "?symbol=" + row(b, "S6").get("symbol").asText()).body();
        List<Long> ids = new ArrayList<>();
        stocks.get("items").forEach(i -> ids.add(i.get("id").asLong()));
        assertThat(ids).containsExactly(stockIds.get("S6"), stockIds.get("S4"), stockIds.get("S1"));
    }

    // ------------------------------------------------------------------ AC 9, 10

    @Test
    @DisplayName("AC9 盈亏汇总全部时间：总计 5 笔 +141.51（期权 2 +20.00 · 股票 3 +121.51）；按标的合成一行；按月")
    void ac9SummaryAllTime() {
        scenarioM();
        JsonNode s = sum("");
        assertThat(s.get("startDate").isNull()).isTrue();
        assertThat(s.get("endDate").isNull()).isTrue();
        assertThat(totals(s)).isEqualTo("5 141.51 | 2 20.00 | 3 121.51");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).containsExactly(
                "IXQZ 2 109.84 | 1 35.00 | 1 74.84",
                "QRTV 1 -20.00 | 0 0.00 | 1 -20.00",
                "XYZ 2 51.67 | 1 -15.00 | 1 66.67");
        assertThat(sumRows(s.get("byMonth"), "month")).containsExactly(
                "2026-11 3 126.51 | 1 -15.00 | 2 141.51",
                "2026-10 2 15.00 | 1 35.00 | 1 -20.00");
    }

    @Test
    @DisplayName("AC10 盈亏汇总日期范围：10-31 → QRTV 1 笔 −20.00；从 11-01 → 3 笔 +126.51；2027 → 空；= 已平仓页")
    void ac10SummaryRanges() {
        scenarioM();
        JsonNode s = sum("startDate=2026-10-31&endDate=2026-10-31");
        assertThat(totals(s)).isEqualTo("1 -20.00 | 0 0.00 | 1 -20.00");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).containsExactly("QRTV 1 -20.00 | 0 0.00 | 1 -20.00");
        assertThat(sumRows(s.get("byMonth"), "month")).containsExactly("2026-10 1 -20.00 | 0 0.00 | 1 -20.00");

        s = sum("startDate=2026-11-01");
        assertThat(totals(s)).isEqualTo("3 126.51 | 1 -15.00 | 2 141.51");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).containsExactly(
                "IXQZ 1 74.84 | 0 0.00 | 1 74.84",
                "XYZ 2 51.67 | 1 -15.00 | 1 66.67");
        assertThat(sumRows(s.get("byMonth"), "month")).containsExactly("2026-11 3 126.51 | 1 -15.00 | 2 141.51");

        s = sum("startDate=2026-11-01&endDate=2026-11-01");
        assertThat(totals(s)).isEqualTo("0 0.00 | 0 0.00 | 0 0.00");

        s = sum("startDate=2027-01-01");
        assertThat(totals(s)).isEqualTo("0 0.00 | 0 0.00 | 0 0.00");
        assertThat(s.get("bySymbol")).isEmpty();
        assertThat(s.get("byMonth")).isEmpty();

        // the 已平仓 targets of the summary links (symbol / month, no type)
        assertThat(labelsOf("symbol=QRTV", 1, "-20.00")).containsExactly("S3");
        assertThat(labelsOf("startDate=2026-10-01&endDate=2026-10-31", 2, "15.00")).containsExactly("S3", "O2");
    }

    // ------------------------------------------------------------------ AC 11, 12

    @Test
    @DisplayName("AC11 首页：总计 +141.51（5）、本月 +126.51（3）、期权持仓 1、股票持仓 2；卖出 XYZ 200 → 1 个标的、+174.84（6）")
    void ac11Dashboard() {
        scenarioM();
        opt("O5", "STO", "IXQZ", "2026-12-18", "9", "CALL", "30.00", "2026-11-12T10:00");
        JsonNode d = dash();
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-13");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(d.get("cash").get("totalCash").asText()).isEqualTo("19529.35");
        assertThat(d.get("cash").get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(d.get("cash").get("availableCash").asText()).isEqualTo("19529.35");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("141.51 (5)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("126.51 (3)");
        JsonNode p = d.get("positions");
        assertThat(List.of(p.get("positionCount").asInt(), p.get("expiringWithin7DaysCount").asInt(),
                p.get("expiredUnprocessedCount").asInt())).containsExactly(1, 0, 0);
        assertThat(p.get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
        JsonNode positionsBefore = p;

        stock("S8", "SELL", "XYZ", 200, "700.00", "2026-11-12T11:00", null);
        d = dash();
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(1);
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("174.84 (6)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("159.84 (4)");
        assertThat(d.get("positions")).isEqualTo(positionsBefore);
        assertThat(row(closed(""), "S8").get("realizedPnl").asText()).isEqualTo("33.33");
    }

    @Test
    @DisplayName("AC12 本月边界（调时钟）：10-31 23:45 ET → 2026-10 +15.00（2）；11-01 00:30 ET → 2026-11 0.00（0），S3 仍属 10 月")
    void ac12MonthEdges() {
        deposit("20000.00", "2026-10-26T10:00");
        stock("S1", "TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01", null);
        stock("S2", "TRANSFER_IN", "QRTV", 50, "400.00", "2026-10-01", null);
        opt("O1", "STO", "IXQZ", "2026-11-20", "7", "PUT", "45.00", "2026-10-27T10:00");
        close("O2", "BTC", "IXQZ", "2026-11-20", "7", "PUT", "10.00", "2026-10-30T10:00");
        stock("S3", "SELL", "QRTV", 50, "380.00", "2026-10-31T23:30", "盘后");

        clock.set(Instant.parse("2026-11-01T03:45:00Z")); // 2026-10-31 23:45 EDT
        JsonNode d = dash();
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-10-31");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-10");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("15.00 (2)");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("15.00 (2)");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(1);

        clock.set(Instant.parse("2026-11-01T04:30:00Z")); // 2026-11-01 00:30 EDT
        d = dash();
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-01");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("15.00 (2)");
        assertThat(sumRows(sum("").get("byMonth"), "month")).containsExactly("2026-10 2 15.00 | 1 35.00 | 1 -20.00");

        clock.set(Instant.parse("2026-11-01T06:30:00Z")); // 01:30 EST, after the DST fall-back
        assertThat(pnl(dash().get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
    }

    // ------------------------------------------------------------------ AC 13

    @Test
    @DisplayName("AC13 改转入成本 S1 750 → 800：S6 −1,175.16（7.8344）+24.84；合计 +91.51；汇总与首页同步")
    void ac13EditTransferCost() {
        scenarioM();
        putStock("S1", 100, "800.00", "tradeDate", "2026-10-01", null);
        JsonNode b = closed("");
        assertThat(line(row(b, "S6"))).isEqualTo(
                "S6 STOCK 2026-11-05T10:00:00 IXQZ null SELL null 150 -1175.16 7.8344 1200.00 8.0000 24.84");
        assertThat(row(b, "S6").get("soldLots").get(0).get("netAmount").asText()).isEqualTo("-800.00");
        assertThat(row(b, "S6").get("hasAuditLog").asBoolean()).as("only the transfer changed").isFalse();
        assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("91.51");
        JsonNode s = sum("");
        assertThat(totals(s)).isEqualTo("5 91.51 | 2 20.00 | 3 71.51");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).first().isEqualTo("IXQZ 2 59.84 | 1 35.00 | 1 24.84");
        assertThat(sumRows(s.get("byMonth"), "month")).first().isEqualTo("2026-11 3 76.51 | 1 -15.00 | 2 91.51");
        JsonNode d = dash();
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("91.51 (5)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("76.51 (3)");
    }

    @Test
    @DisplayName("AC13 改卖出实收 S7 400 → 300：S7 −33.33，合计 +41.51，XYZ −48.33；S7 有历史")
    void ac13EditSaleProceeds() {
        scenarioM();
        putStock("S7", 100, "300.00", "tradedAtLocal", "2026-11-06T10:00", null);
        JsonNode b = closed("");
        assertThat(row(b, "S7").get("realizedPnl").asText()).isEqualTo("-33.33");
        assertThat(row(b, "S7").get("pricePerShare").asText()).isEqualTo("3.0000");
        assertThat(row(b, "S7").get("hasAuditLog").asBoolean()).isTrue();
        assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("41.51");
        JsonNode s = sum("");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).last().isEqualTo("XYZ 2 -48.33 | 1 -15.00 | 1 -33.33");
        assertThat(pnl(dash().get("realizedPnl"))).isEqualTo("41.51 (5)");
    }

    @Test
    @DisplayName("AC13 删除卖出 S7：4 笔 +74.84；XYZ 只剩期权；2026-11 2 笔 +59.84；首页 4 笔、股票持仓仍 2")
    void ac13DeleteSale() {
        scenarioM();
        deleteStock("S7");
        assertThat(labelsOf("", 4, "74.84")).containsExactly("O4", "S6", "S3", "O2");
        JsonNode s = sum("");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).last().isEqualTo("XYZ 1 -15.00 | 1 -15.00 | 0 0.00");
        assertThat(sumRows(s.get("byMonth"), "month")).first().isEqualTo("2026-11 2 59.84 | 1 -15.00 | 1 74.84");
        JsonNode d = dash();
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("74.84 (4)");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC13 补录卖出 XYZ 100 @420：成本 333.34（333.335 → 333.34）+86.66；6 笔 +228.17；2026-11 4 笔 +213.17")
    void ac13BackfillSale() {
        scenarioM();
        stock("S8", "SELL", "XYZ", 100, "420.00", "2026-11-12T10:00", null);
        JsonNode b = closed("");
        assertThat(line(row(b, "S8"))).isEqualTo(
                "S8 STOCK 2026-11-12T10:00:00 XYZ null SELL null 100 -333.34 3.3334 420.00 4.2000 86.66");
        assertThat(labelsOf("", 6, "228.17")).containsExactly("S8", "O4", "S7", "S6", "S3", "O2");
        assertThat(sumRows(sum("").get("byMonth"), "month")).first()
                .isEqualTo("2026-11 4 213.17 | 1 -15.00 | 3 228.17");
        assertThat(pnl(dash().get("currentMonthRealizedPnl"))).isEqualTo("213.17 (4)");
    }

    @Test
    @DisplayName("AC13 删除被用到的转入 S1：S6 改为只用 S4，−1,125.49、+74.51；合计 +141.18")
    void ac13DeleteUsedLot() {
        scenarioM();
        deleteStock("S1");
        JsonNode b = closed("");
        assertThat(line(row(b, "S6"))).isEqualTo(
                "S6 STOCK 2026-11-05T10:00:00 IXQZ null SELL null 150 -1125.49 7.5033 1200.00 8.0000 74.51");
        assertThat(row(b, "S6").get("soldLots")).hasSize(1);
        assertThat(row(b, "S6").get("soldLots").get(0).get("tradeId").asLong()).isEqualTo(stockIds.get("S4"));
        assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("141.18");
        assertThat(totals(sum(""))).isEqualTo("5 141.18 | 2 20.00 | 3 121.18");
        assertThat(pnl(dash().get("realizedPnl"))).isEqualTo("141.18 (5)");
    }

    @Test
    @DisplayName("AC13 转入/买入没有盈亏：转入 QFZ、买入 QRTV 后盈亏和笔数都不变；股票持仓个数变化")
    void ac13TransferAndBuyNoPnl() {
        scenarioM();
        String closedBefore = closed("size=100").toString();
        String sumBefore = sum("").toString();
        JsonNode dBefore = dash();
        stock("S8", "TRANSFER_IN", "QFZ", 100, "300.00", "2026-11-10", null);
        stock("S9", "BUY", "QRTV", 10, "70.00", "2026-11-10T10:00", null);
        assertThat(closed("size=100").toString()).isEqualTo(closedBefore);
        assertThat(sum("").toString()).isEqualTo(sumBefore);
        JsonNode d = dash();
        assertThat(d.get("realizedPnl")).isEqualTo(dBefore.get("realizedPnl"));
        assertThat(d.get("currentMonthRealizedPnl")).isEqualTo(dBefore.get("currentMonthRealizedPnl"));
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(4);
        // a deposit / withdrawal does not change P&L either
        withdraw("100.00", "2026-11-12T10:00");
        assertThat(sum("").toString()).isEqualTo(sumBefore);
    }

    @Test
    @DisplayName("AC13 改期权交易后同样重新计算：O4 净额 40 → 30 → −5.00，合计 +151.51")
    void ac13EditOptionReflows() {
        scenarioM();
        ObjectNode n = json.createObjectNode();
        n.put("contracts", 1);
        n.put("netAmount", "30.00");
        n.put("tradedAtLocal", "2026-11-09T10:00");
        Resp r = exchange(HttpMethod.PUT, TRADES + "/" + optIds.get("O4"), n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("PUT O4 -> %s", r.body()).isEqualTo(200);
        assertThat(labelsOf("", 5, "151.51")).containsExactly("O4", "S7", "S6", "S3", "O2");
        assertThat(totals(sum(""))).isEqualTo("5 151.51 | 2 30.00 | 3 121.51");
        assertThat(pnl(dash().get("realizedPnl"))).isEqualTo("151.51 (5)");
    }

    // ------------------------------------------------------------------ AC 14

    @Test
    @DisplayName("AC14 按账户隔离：小账户只看到自己的股票卖出 +60.00；默认账户仍 5 笔 +141.51")
    void ac14AccountIsolation() {
        scenarioM();
        accountId = String.valueOf(createAccount("小账户"));
        deposit("1000.00", "2026-11-02T10:00");
        stock("X1", "TRANSFER_IN", "IXQZ", 100, "700.00", "2026-11-02", null);
        stock("X2", "SELL", "IXQZ", 100, "760.00", "2026-11-10T10:00", null);
        assertThat(labelsOf("", 1, "60.00")).containsExactly("X2");
        JsonNode b = closed("");
        assertThat(line(row(b, "X2"))).isEqualTo(
                "X2 STOCK 2026-11-10T10:00:00 IXQZ null SELL null 100 -700.00 7.0000 760.00 7.6000 60.00");
        JsonNode s = sum("");
        assertThat(totals(s)).isEqualTo("1 60.00 | 0 0.00 | 1 60.00");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).containsExactly("IXQZ 1 60.00 | 0 0.00 | 1 60.00");
        JsonNode d = dash();
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("60.00 (1)");
        assertThat(d.get("positions").get("positionCount").asInt()).isZero();
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isZero();
        // other account's records by id/type are not visible as rows
        assertThat(labelsOf("type=OPTION", 0, "0.00")).isEmpty();

        accountId = "1";
        assertThat(labelsOf("", 5, "141.51")).containsExactly("O4", "S7", "S6", "S3", "O2");
        assertThat(sumRows(sum("").get("bySymbol"), "symbol")).first().isEqualTo("IXQZ 2 109.84 | 1 35.00 | 1 74.84");
        JsonNode d1 = dash();
        assertThat(pnl(d1.get("realizedPnl"))).isEqualTo("141.51 (5)");
        assertThat(d1.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ AC 15, 16

    @Test
    @DisplayName("AC15 只有期权（FEAT-008 场景 Q）：数字与原验收相同，全部为期权行，股票拆分全 0，股票持仓 0")
    void ac15OptionsOnlyRegression() {
        deposit("100000.00", "2026-10-26T10:00");
        opt("O1", "BTO", "TSLA", "2026-12-18", "300", "CALL", "500.00", "2026-10-27T10:00");
        opt("O2", "STO", "AAPL", "2026-11-20", "180", "PUT", "150.00", "2026-10-28T10:00");
        close("O3", "STC", "TSLA", "2026-12-18", "300", "CALL", "620.00", "2026-10-30T10:00");
        close("O4", "BTC", "AAPL", "2026-11-20", "180", "PUT", "200.00", "2026-10-31T23:30");
        JsonNode t1 = okTrade(trade("STO", 2, "AAPL", "2026-12-18", "200", "PUT", "698.70", "2026-11-02T10:30", 100));
        opt("T2", "BTO", "MSFT", "2027-01-15", "450", "CALL", "900.00", "2026-11-02T14:00");
        okTrade(trade("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-02T15:00", 10));
        okTrade(trade("BTO", 2, "AAPL", "2026-12-18", "210", "CALL", "1000.00", "2026-11-03T09:45", 100));
        opt("T5", "STO", "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-03T11:00");
        assertThat(t1.get("id").asLong()).isPositive();
        ObjectNode c1 = json.createObjectNode();
        c1.put("symbol", "AAPL");
        c1.put("expirationDate", "2026-12-18");
        c1.put("strike", "200");
        c1.put("optionType", "PUT");
        c1.put("action", "BTC");
        c1.put("contracts", 3);
        c1.put("netAmount", "400.00");
        c1.put("tradedAtLocal", "2026-11-05T10:00");
        assertThat(postRaw(CLOSES, c1.toString(), MediaType.APPLICATION_JSON).status()).isEqualTo(201);
        close("C2", "BTC", "XYZ", "2026-12-18", "200", "PUT", "50.00", "2026-11-06T10:00");
        close("C3", "STC", "MSFT", "2027-01-15", "450", "CALL", "1250.00", "2026-11-06T23:30");
        ObjectNode c4 = c1.deepCopy();
        c4.put("strike", "210");
        c4.put("optionType", "CALL");
        c4.put("action", "STC");
        c4.put("contracts", 2);
        c4.put("netAmount", "640.00");
        c4.put("tradedAtLocal", "2026-11-07T10:00");
        assertThat(postRaw(CLOSES, c4.toString(), MediaType.APPLICATION_JSON).status()).isEqualTo(201);

        JsonNode b = closed("size=100");
        assertThat(b.get("filteredCount").asInt()).isEqualTo(6);
        assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("703.05");
        for (JsonNode i : b.get("items")) {
            assertThat(i.get("type").asText()).isEqualTo("OPTION");
        }
        assertThat(closed("type=STOCK").get("filteredCount").asInt()).isZero();
        JsonNode s = sum("");
        assertThat(totals(s)).isEqualTo("6 703.05 | 6 703.05 | 0 0.00");
        assertThat(sumRows(s.get("bySymbol"), "symbol")).containsExactly(
                "AAPL 3 248.70 | 3 248.70 | 0 0.00",
                "MSFT 1 350.00 | 1 350.00 | 0 0.00",
                "TSLA 1 120.00 | 1 120.00 | 0 0.00",
                "XYZ 1 -15.65 | 1 -15.65 | 0 0.00");
        assertThat(sumRows(s.get("byMonth"), "month")).containsExactly(
                "2026-11 4 633.05 | 4 633.05 | 0 0.00",
                "2026-10 2 70.00 | 2 70.00 | 0 0.00");
        JsonNode d = dash();
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("703.05 (6)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("633.05 (4)");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isZero();
    }

    @Test
    @DisplayName("AC16 空状态：只有入金、买入、转入 → 已平仓 0 笔 0.00；汇总 0（拆分都是 0 笔 0.00，不为 null）；首页 0.00（0），股票持仓按实际")
    void ac16Empty() {
        JsonNode s0 = sum("");
        assertThat(totals(s0)).isEqualTo("0 0.00 | 0 0.00 | 0 0.00");
        deposit("1000.00", "2026-11-02T10:00");
        stock("B1", "BUY", "IXQZ", 10, "80.00", "2026-11-03T10:00", null);
        stock("T1", "TRANSFER_IN", "QRTV", 10, "70.00", "2026-10-01", null);
        JsonNode b = closed("");
        assertThat(b.get("items")).isEmpty();
        assertThat(b.get("totalItems").asInt()).isZero();
        assertThat(b.get("totalPages").asInt()).isZero();
        assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        JsonNode s = sum("");
        assertThat(totals(s)).isEqualTo("0 0.00 | 0 0.00 | 0 0.00");
        assertThat(s.get("bySymbol")).isEmpty();
        assertThat(s.get("byMonth")).isEmpty();
        JsonNode d = dash();
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
    }
}
