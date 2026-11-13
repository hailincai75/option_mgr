package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-011 stock trades (BUY / SELL / TRANSFER_IN), FIFO lots, stock positions, cash replay together with option
 * trades and cash transfers, edit/delete with audit, account isolation. End-to-end over HTTP on a real port.
 * "Now" = 2026-11-13 10:00 New York (EST) = 2026-11-13T15:00:00Z as stated in the acceptance criteria.
 * All tickers are fictional.
 */
class Feat011StockTradesSystemTest extends OptionTradeSystemTestBase {

    static final String STOCKS = "/api/stock-trades";
    static final String STOCK_POS = "/api/stock-positions";
    static final String AUDIT = "/api/audit-log";

    final Map<String, Long> ids = new LinkedHashMap<>();
    final Map<Long, String> labels = new LinkedHashMap<>();

    @Override
    Instant now() {
        return Instant.parse("2026-11-13T15:00:00Z");
    }

    // ------------------------------------------------------------------ request helpers

    /** time = tradedAtLocal for BUY/SELL, tradeDate for TRANSFER_IN. shares as Integer, or any other JSON via Object. */
    ObjectNode stock(String type, String symbol, Object shares, String amount, String time, String note) {
        ObjectNode n = json.createObjectNode();
        if (type != null) {
            n.put("type", type);
        }
        n.put("symbol", symbol);
        if (shares instanceof Integer i) {
            n.put("shares", i);
        } else if (shares instanceof Double d) {
            n.put("shares", d);
        } else if (shares instanceof String s) {
            n.put("shares", s);
        } else {
            n.putNull("shares");
        }
        if (amount == null) {
            n.putNull("amount");
        } else {
            n.put("amount", amount);
        }
        if ("TRANSFER_IN".equals(type)) {
            n.put("tradeDate", time);
        } else if (time != null) {
            n.put("tradedAtLocal", time);
        }
        if (note == null) {
            n.putNull("note");
        } else {
            n.put("note", note);
        }
        return n;
    }

    Resp postStock(ObjectNode body) {
        return postRaw(STOCKS, body.toString(), MediaType.APPLICATION_JSON);
    }

    JsonNode okStock(String label, ObjectNode body) {
        Resp r = postStock(body);
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        assertStockShape(r.body());
        assertThat(r.body().get("hasAuditLog").asBoolean()).isFalse();
        if (label != null) {
            ids.put(label, r.body().get("id").asLong());
            labels.put(r.body().get("id").asLong(), label);
        }
        return r.body();
    }

    JsonNode buy(String label, String symbol, int shares, String amount, String at) {
        return okStock(label, stock("BUY", symbol, shares, amount, at, null));
    }

    JsonNode sell(String label, String symbol, int shares, String amount, String at) {
        return okStock(label, stock("SELL", symbol, shares, amount, at, null));
    }

    JsonNode transferIn(String label, String symbol, int shares, String cost, String date) {
        return okStock(label, stock("TRANSFER_IN", symbol, shares, cost, date, null));
    }

    /** Everything observable in the current account; must be identical before and after a rejected write. */
    String snap() {
        return summary().toString() + "|" + ledger(1, 100).toString() + "|" + get(STOCKS + "?size=100").body()
                + "|" + get(STOCK_POS).body() + "|" + get(AUDIT + "?size=100").body() + "|"
                + get("/api/option-positions").body();
    }

    /** POST must be rejected with this status/code; nothing changes; returns the message. */
    String rejectedStock(ObjectNode body, int status, String code) {
        String before = snap();
        Resp r = postStock(body);
        assertErrorShape(r, status, code, STOCKS);
        assertThat(snap()).as("nothing changed after rejected %s", body).isEqualTo(before);
        return r.body().get("message").asText();
    }

    /** POST must be rejected with VALIDATION_ERROR whose details are exactly these field=message pairs. */
    void stockValidation(ObjectNode body, String... pairs) {
        String before = snap();
        Resp r = postStock(body);
        assertErrorShape(r, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(r)).as("details for %s", body).containsExactlyInAnyOrder(pairs);
        assertThat(snap()).isEqualTo(before);
    }

    void stockBadRequest(String rawBody, String field) {
        String before = snap();
        Resp r = postRaw(STOCKS, rawBody, MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "BAD_REQUEST", STOCKS);
        assertThat(detailFields(r)).as("details for %s", rawBody).contains(field);
        assertThat(snap()).isEqualTo(before);
    }

    List<String> details(Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("details").forEach(d -> out.add(d.get("field").asText() + "=" + d.get("message").asText()));
        return out;
    }

    Resp putStock(long id, ObjectNode body) {
        return exchange(HttpMethod.PUT, STOCKS + "/" + id, body.toString(), MediaType.APPLICATION_JSON);
    }

    Resp deleteStock(long id) {
        return exchange(HttpMethod.DELETE, STOCKS + "/" + id, null, MediaType.APPLICATION_JSON);
    }

    ObjectNode edit(Integer shares, String amount, String time, String dateOrNull, String note) {
        ObjectNode n = json.createObjectNode();
        if (shares != null) {
            n.put("shares", shares);
        }
        n.put("amount", amount);
        if (time != null) {
            n.put("tradedAtLocal", time);
        }
        if (dateOrNull != null) {
            n.put("tradeDate", dateOrNull);
        }
        if (note != null) {
            n.put("note", note);
        }
        return n;
    }

    JsonNode editOk(String label, ObjectNode body) {
        Resp r = putStock(ids.get(label), body);
        assertThat(r.status()).as("PUT %s %s -> %s", label, body, r.body()).isEqualTo(200);
        assertStockShape(r.body());
        assertThat(r.body().get("hasAuditLog").asBoolean()).isTrue();
        return r.body();
    }

    /** PUT / DELETE that must be rejected; nothing changes (no audit). */
    String rejectedWrite(HttpMethod method, String label, ObjectNode body, int status, String code) {
        String before = snap();
        long id = ids.get(label);
        Resp r = method == HttpMethod.PUT ? putStock(id, body) : deleteStock(id);
        assertErrorShape(r, status, code, STOCKS + "/" + id);
        assertThat(snap()).as("nothing changed after rejected %s %s", method, label).isEqualTo(before);
        return r.body().get("message").asText();
    }

    JsonNode getStock(String label) {
        Resp r = get(STOCKS + "/" + ids.get(label));
        assertThat(r.status()).isEqualTo(200);
        assertStockShape(r.body());
        return r.body();
    }

    // ------------------------------------------------------------------ read helpers

    JsonNode list(String query) {
        Resp r = get(STOCKS + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("GET %s -> %s", query, r.body()).isEqualTo(200);
        for (JsonNode item : r.body().get("items")) {
            assertStockShape(item);
        }
        return r.body();
    }

    List<String> listLabels(String query) {
        List<String> out = new ArrayList<>();
        list(query).get("items").forEach(i -> out.add(labels.get(i.get("id").asLong())));
        return out;
    }

    /** "label type symbol shares amount cashChange pricePerShare tradedAtLocal tradeDate soldCost realizedPnl". */
    String line(JsonNode t) {
        return String.join(" ", labels.get(t.get("id").asLong()), t.get("type").asText(), t.get("symbol").asText(),
                t.get("shares").asText(), t.get("amount").asText(), t.get("cashChange").asText(),
                t.get("pricePerShare").asText(), t.get("tradedAtLocal").asText(), t.get("tradeDate").asText(),
                t.get("soldCost").asText(), t.get("realizedPnl").asText());
    }

    List<String> soldLots(JsonNode sale) {
        List<String> out = new ArrayList<>();
        sale.get("soldLots").forEach(l -> out.add(labels.get(l.get("tradeId").asLong()) + " " + l.get("type").asText()
                + " " + l.get("tradedAtLocal").asText() + " " + l.get("tradeDate").asText() + " "
                + l.get("shares").asText() + " " + l.get("cost").asText()));
        return out;
    }

    JsonNode positions() {
        Resp r = get(STOCK_POS);
        assertThat(r.status()).isEqualTo(200);
        JsonNode b = r.body();
        assertThat(b.get("positionCount").asInt()).isEqualTo(b.get("positions").size());
        for (JsonNode p : b.get("positions")) {
            assertThat(p.get("shares").isIntegralNumber()).isTrue();
            assertThat(p.get("totalCost").isTextual()).isTrue();
            assertThat(p.get("averageCost").isTextual()).isTrue();
            assertThat(p.get("lotCount").asInt()).isEqualTo(p.get("lots").size());
        }
        return b;
    }

    /** "SYMBOL shares totalCost averageCost lotCount" per position, in backend order. */
    List<String> positionRows() {
        List<String> out = new ArrayList<>();
        positions().get("positions").forEach(p -> out.add(p.get("symbol").asText() + " " + p.get("shares").asText()
                + " " + p.get("totalCost").asText() + " " + p.get("averageCost").asText() + " "
                + p.get("lotCount").asText()));
        return out;
    }

    JsonNode position(String symbol) {
        for (JsonNode p : positions().get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    /** "label type tradedAtLocal tradeDate originalShares remainingShares originalCost remainingCost". */
    List<String> lots(String symbol) {
        List<String> out = new ArrayList<>();
        position(symbol).get("lots").forEach(l -> out.add(labels.get(l.get("tradeId").asLong()) + " "
                + l.get("type").asText() + " " + l.get("tradedAtLocal").asText() + " " + l.get("tradeDate").asText()
                + " " + l.get("originalShares").asText() + " " + l.get("remainingShares").asText() + " "
                + l.get("originalCost").asText() + " " + l.get("remainingCost").asText()));
        return out;
    }

    /** Contract shape of a stock record. */
    void assertStockShape(JsonNode t) {
        assertThat(t.get("id").isIntegralNumber()).isTrue();
        assertThat(t.get("shares").isIntegralNumber()).as("shares is a JSON integer").isTrue();
        for (String f : List.of("amount", "cashChange", "pricePerShare", "tradedAt", "tradedAtLocal", "createdAt",
                "type", "symbol")) {
            assertThat(t.get(f).isTextual()).as("%s is a string in %s", f, t).isTrue();
        }
        assertThat(t.get("pricePerShare").asText()).matches("\\d+\\.\\d{4}");
        assertThat(t.get("amount").asText()).matches("\\d+\\.\\d{2}");
        assertThat(t.get("tradedAt").asText()).endsWith("Z");
        assertThat(t.has("hasAuditLog")).isTrue();
        assertThat(t.has("note")).isTrue();
        String type = t.get("type").asText();
        if (type.equals("SELL")) {
            assertThat(t.get("soldCost").isTextual()).isTrue();
            assertThat(t.get("realizedPnl").isTextual()).isTrue();
            assertThat(t.get("soldLots").isArray()).isTrue();
        } else {
            assertThat(t.get("soldCost").isNull()).isTrue();
            assertThat(t.get("realizedPnl").isNull()).isTrue();
            assertThat(t.get("soldLots").isNull()).isTrue();
        }
        if (type.equals("TRANSFER_IN")) {
            assertThat(t.get("tradeDate").isTextual()).isTrue();
            assertThat(t.get("ledgerEntryId").isNull()).isTrue();
            assertThat(t.get("cashChange").asText()).isEqualTo("0.00");
            assertThat(t.get("tradedAtLocal").asText()).endsWith("T00:00:00");
        } else {
            assertThat(t.get("tradeDate").isNull()).isTrue();
            assertThat(t.get("ledgerEntryId").isIntegralNumber()).isTrue();
        }
    }

    long auditTotal() {
        return get(AUDIT + "?size=100").body().get("totalItems").asLong();
    }

    List<String> changeTexts(JsonNode audit) {
        List<String> out = new ArrayList<>();
        audit.get("changes").forEach(c -> out.add(c.get("text").asText()));
        return out;
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

    /** "sourceType amount balanceAfter description" per ledger row, newest first. */
    List<String> ledgerRows() {
        List<String> out = new ArrayList<>();
        ledger(1, 100).get("items").forEach(r -> out.add(r.get("sourceType").asText() + " " + r.get("amount").asText()
                + " " + r.get("balanceAfter").asText() + " " + r.get("description").asText()));
        return out;
    }

    // ------------------------------------------------------------------ scenarios

    /** Scenario S in the current account. */
    void scenarioS() {
        ids.put("D1", deposit("10000.00", "2026-11-02T10:00").get("id").asLong());
        assertSummary("10000.00", "0.00", "10000.00");
        transferIn("S1", "IXQZ", 100, "750.00", "2026-10-01");
        assertSummary("10000.00", "0.00", "10000.00");
        buy("S2", "IXQZ", 200, "1500.65", "2026-11-03T10:00");
        buy("S3", "XYZ", 300, "1000.00", "2026-11-04T10:00");
        sell("S4", "IXQZ", 150, "1200.00", "2026-11-05T10:00");
        sell("S5", "XYZ", 100, "400.00", "2026-11-06T10:00");
        assertSummary("9099.35", "0.00", "9099.35");
    }

    /** AC 5 "小账户": deposit 1,000.00, STO 1 QFZ 2026-12-18 5 P for 20.00 (frozen 500.00). Switches to it. */
    void smallAccount() {
        accountId = String.valueOf(createAccount("小账户"));
        ids.put("SD1", deposit("1000.00", "2026-11-02T10:00").get("id").asLong());
        okTrade(trade("STO", 1, "QFZ", "2026-12-18", "5", "PUT", "20.00", "2026-11-03T09:00", 100));
        assertSummary("1020.00", "500.00", "520.00");
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC1 录入与股票持仓：转入存为该日美东 00:00、不影响现金；持仓按 FIFO 剩余")
    void ac1EntryAndPositions() {
        scenarioS();
        JsonNode s1 = getStock("S1");
        assertThat(s1.get("tradedAt").asText()).isEqualTo("2026-10-01T04:00:00Z");
        assertThat(s1.get("tradedAtLocal").asText()).isEqualTo("2026-10-01T00:00:00");
        assertThat(s1.get("tradeDate").asText()).isEqualTo("2026-10-01");
        assertThat(s1.get("pricePerShare").asText()).isEqualTo("7.5000");
        assertThat(s1.get("cashChange").asText()).isEqualTo("0.00");

        JsonNode s2 = getStock("S2");
        assertThat(s2.get("tradedAt").asText()).isEqualTo("2026-11-03T15:00:00Z");
        assertThat(s2.get("cashChange").asText()).isEqualTo("-1500.65");
        assertThat(s2.get("pricePerShare").asText()).isEqualTo("7.5033");

        // option part empty
        JsonNode opt = get("/api/option-positions").body();
        assertThat(opt.get("positionCount").asInt()).isZero();

        assertThat(positions().get("positionCount").asInt()).isEqualTo(2);
        assertThat(positionRows()).containsExactly("IXQZ 150 1125.49 7.5033 1", "XYZ 200 666.67 3.3334 1");
        assertThat(lots("IXQZ")).containsExactly("S2 BUY 2026-11-03T10:00:00 null 200 150 1500.65 1125.49");
        assertThat(lots("XYZ")).containsExactly("S3 BUY 2026-11-04T10:00:00 null 300 200 1000.00 666.67");
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("AC2 列表顺序、卖出的被卖出批次与已实现盈亏、资金流水")
    void ac2ListPnlLedger() {
        scenarioS();
        JsonNode page = list("");
        assertThat(page.get("totalItems").asInt()).isEqualTo(5);
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        List<String> rows = new ArrayList<>();
        page.get("items").forEach(i -> rows.add(line(i)));
        assertThat(rows).containsExactly(
                "S5 SELL XYZ 100 400.00 400.00 4.0000 2026-11-06T10:00:00 null 333.33 66.67",
                "S4 SELL IXQZ 150 1200.00 1200.00 8.0000 2026-11-05T10:00:00 null 1125.16 74.84",
                "S3 BUY XYZ 300 1000.00 -1000.00 3.3333 2026-11-04T10:00:00 null null null",
                "S2 BUY IXQZ 200 1500.65 -1500.65 7.5033 2026-11-03T10:00:00 null null null",
                "S1 TRANSFER_IN IXQZ 100 750.00 0.00 7.5000 2026-10-01T00:00:00 2026-10-01 null null");
        for (JsonNode i : page.get("items")) {
            assertThat(i.get("hasAuditLog").asBoolean()).as("no 历史 for %s", line(i)).isFalse();
            // GET by id returns the same representation
            assertThat(get(STOCKS + "/" + i.get("id").asLong()).body()).isEqualTo(i);
        }
        assertThat(soldLots(getStock("S4"))).containsExactly(
                "S1 TRANSFER_IN 2026-10-01T00:00:00 2026-10-01 100 750.00",
                "S2 BUY 2026-11-03T10:00:00 null 50 375.16");
        assertThat(soldLots(getStock("S5"))).containsExactly("S3 BUY 2026-11-04T10:00:00 null 100 333.33");

        assertThat(ledgerRows()).containsExactly(
                "STOCK_SELL 400.00 9099.35 卖出 100 股 XYZ",
                "STOCK_SELL 1200.00 8699.35 卖出 150 股 IXQZ",
                "STOCK_BUY -1000.00 7499.35 买入 300 股 XYZ",
                "STOCK_BUY -1500.65 8499.35 买入 200 股 IXQZ",
                "DEPOSIT 10000.00 10000.00 null");
        // sourceId / ledgerEntryId link the two, frozen 0, no 历史 on ledger rows
        for (JsonNode row : ledger(1, 100).get("items")) {
            if (row.get("sourceType").asText().startsWith("STOCK_")) {
                JsonNode t = get(STOCKS + "/" + row.get("sourceId").asLong()).body();
                assertThat(t.get("ledgerEntryId").asLong()).isEqualTo(row.get("id").asLong());
                assertThat(row.get("frozenChange").asText()).isEqualTo("0.00");
                assertThat(row.get("hasAuditLog").asBoolean()).isFalse();
                assertThat(row.get("occurredAt").asText()).isEqualTo(t.get("tradedAt").asText());
            }
        }
    }

    @Test
    @DisplayName("备注：保存到股票记录、流水 remark、持仓批次 note；空白备注存为 null")
    void noteFlowsToLedgerAndLots() {
        deposit("1000.00", "2026-11-02T10:00");
        okStock("B", stock("BUY", "IXQZ", 10, "80.00", "2026-11-03T10:00", "上线后买入"));
        okStock("T", stock("TRANSFER_IN", "IXQZ", 5, "30.00", "2026-10-01", "   "));
        assertThat(getStock("B").get("note").asText()).isEqualTo("上线后买入");
        assertThat(getStock("T").get("note").isNull()).isTrue();
        JsonNode row = ledgerRowFor("STOCK_BUY", ids.get("B"));
        assertThat(row.get("remark").asText()).isEqualTo("上线后买入");
        List<String> notes = new ArrayList<>();
        position("IXQZ").get("lots").forEach(l -> notes.add(l.get("note").asText()));
        assertThat(notes).containsExactly("null", "上线后买入");
    }

    // ------------------------------------------------------------------ AC 3

    @Test
    @DisplayName("AC3 列表筛选与分页、筛选参数校验")
    void ac3Filters() {
        scenarioS();
        assertThat(listLabels("symbol=ixqz")).containsExactly("S4", "S2", "S1");
        assertThat(listLabels("symbol=IXQ")).isEmpty();
        assertThat(listLabels("type=SELL")).containsExactly("S5", "S4");
        assertThat(listLabels("type=TRANSFER_IN")).containsExactly("S1");
        assertThat(listLabels("type=BUY,TRANSFER_IN")).containsExactly("S3", "S2", "S1");
        assertThat(listLabels("type=BUY&type=TRANSFER_IN")).containsExactly("S3", "S2", "S1");
        assertThat(listLabels("type=BUY,,TRANSFER_IN")).containsExactly("S3", "S2", "S1");
        assertThat(listLabels("startDate=2026-11-04&endDate=2026-11-05")).containsExactly("S4", "S3");
        assertThat(listLabels("endDate=2026-10-31")).containsExactly("S1");
        assertThat(listLabels("startDate=2026-10-01&endDate=2026-10-01")).containsExactly("S1");
        assertThat(listLabels("startDate=2026-11-06")).containsExactly("S5");
        assertThat(listLabels("symbol=ixqz&type=SELL&startDate=2026-11-05")).containsExactly("S4");
        assertThat(listLabels("symbol=&type=&startDate=&endDate=")).hasSize(5);
        JsonNode none = list("symbol=QRTV");
        assertThat(none.get("totalItems").asInt()).isZero();
        assertThat(none.get("totalPages").asInt()).isZero();

        // pagination
        JsonNode p1 = list("size=2");
        assertThat(p1.get("totalItems").asInt()).isEqualTo(5);
        assertThat(p1.get("totalPages").asInt()).isEqualTo(3);
        assertThat(listLabels("size=2&page=1")).containsExactly("S5", "S4");
        assertThat(listLabels("size=2&page=3")).containsExactly("S1");
        assertThat(listLabels("size=2&page=4")).isEmpty();

        // errors
        Resp bad = get(STOCKS + "?symbol=$IXQZ");
        assertErrorShape(bad, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(bad)).containsExactly("symbol=标的只能包含字母、数字和点");
        Resp longSym = get(STOCKS + "?symbol=ABCDEFGHIJK");
        assertErrorShape(longSym, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(longSym)).containsExactly("symbol=标的最多 10 个字符");
        Resp badType = get(STOCKS + "?type=BUY,FOO");
        assertErrorShape(badType, 400, "BAD_REQUEST", STOCKS);
        assertThat(details(badType)).containsExactly("type=类型只能是 BUY、SELL、TRANSFER_IN");
        Resp badDate = get(STOCKS + "?startDate=2026-02-30");
        assertErrorShape(badDate, 400, "BAD_REQUEST", STOCKS);
        assertThat(details(badDate)).containsExactly("startDate=日期格式不正确（YYYY-MM-DD）");
        Resp order = get(STOCKS + "?startDate=2026-11-05&endDate=2026-11-04");
        assertErrorShape(order, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(order)).containsExactly("endDate=开始日期不能晚于结束日期");
        Resp size0 = get(STOCKS + "?size=0");
        assertErrorShape(size0, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(size0)).containsExactly("size=size 必须在 1 到 100 之间");
        Resp size101 = get(STOCKS + "?size=101");
        assertErrorShape(size101, 400, "VALIDATION_ERROR", STOCKS);
        Resp page0 = get(STOCKS + "?page=0");
        assertErrorShape(page0, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(page0)).containsExactly("page=page 必须 ≥ 1");
        assertErrorShape(get(STOCKS + "?page=abc"), 400, "BAD_REQUEST", STOCKS);
        // BAD_REQUEST wins over VALIDATION_ERROR
        Resp mixed = get(STOCKS + "?symbol=$X&type=NOPE");
        assertErrorShape(mixed, 400, "BAD_REQUEST", STOCKS);
    }

    @Test
    @DisplayName("边界：空列表形状；深夜成交按美东日期筛选；周末成交允许")
    void emptyListLateNightAndWeekend() {
        JsonNode empty = list("");
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("totalItems").asInt()).isZero();
        assertThat(empty.get("totalPages").asInt()).isZero();
        assertThat(empty.get("page").asInt()).isEqualTo(1);
        assertThat(empty.get("size").asInt()).isEqualTo(20);
        JsonNode emptyPos = positions();
        assertThat(emptyPos.get("positionCount").asInt()).isZero();
        assertThat(emptyPos.get("positions")).isEmpty();

        deposit("1000.00", "2026-11-02T10:00");
        // 2026-11-05 23:30 NY = 2026-11-06T04:30Z
        JsonNode late = buy("LATE", "IXQZ", 10, "75.00", "2026-11-05T23:30");
        assertThat(late.get("tradedAt").asText()).isEqualTo("2026-11-06T04:30:00Z");
        assertThat(listLabels("startDate=2026-11-05&endDate=2026-11-05")).containsExactly("LATE");
        assertThat(listLabels("startDate=2026-11-06")).isEmpty();
        // Sunday 2026-11-08 and an off-hours time are accepted (no trading-calendar check)
        sell("SUN", "IXQZ", 5, "40.00", "2026-11-08T03:00");
        assertThat(positionRows()).containsExactly("IXQZ 5 37.50 7.5000 1");
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("AC4 同一批次多次部分卖出（Q69、Q87）：最后一次取剩余成本，合计不差一分")
    void ac4RepeatedPartialSales() {
        scenarioS();
        JsonNode s6 = sell("S6", "XYZ", 100, "420.00", "2026-11-09T10:00");
        assertThat(s6.get("soldCost").asText()).isEqualTo("333.34");
        assertThat(s6.get("realizedPnl").asText()).isEqualTo("86.66");
        assertThat(soldLots(s6)).containsExactly("S3 BUY 2026-11-04T10:00:00 null 100 333.34");
        assertThat(position("XYZ").get("shares").asInt()).isEqualTo(100);
        assertThat(position("XYZ").get("totalCost").asText()).isEqualTo("333.33");
        assertThat(position("XYZ").get("averageCost").asText()).isEqualTo("3.3333");

        JsonNode s7 = sell("S7", "XYZ", 100, "300.00", "2026-11-10T10:00");
        assertThat(s7.get("soldCost").asText()).isEqualTo("333.33");
        assertThat(s7.get("realizedPnl").asText()).isEqualTo("-33.33");
        assertThat(position("XYZ")).isNull();
        assertThat(positionRows()).containsExactly("IXQZ 150 1125.49 7.5033 1");
        // sum over the three XYZ sales
        assertThat(listLabels("symbol=XYZ&type=SELL")).containsExactly("S7", "S6", "S5");
        java.math.BigDecimal cost = java.math.BigDecimal.ZERO;
        java.math.BigDecimal pnl = java.math.BigDecimal.ZERO;
        for (JsonNode i : list("symbol=XYZ&type=SELL").get("items")) {
            cost = cost.add(new java.math.BigDecimal(i.get("soldCost").asText()));
            pnl = pnl.add(new java.math.BigDecimal(i.get("realizedPnl").asText()));
        }
        assertThat(cost.toPlainString()).isEqualTo("1000.00");
        assertThat(pnl.toPlainString()).isEqualTo("120.00");
    }

    @Test
    @DisplayName("边界：卖光后再买入是新的持仓，FIFO 从新批次开始")
    void sellOutThenBuyAgain() {
        scenarioS();
        sell("ALL", "XYZ", 200, "900.00", "2026-11-09T10:00");
        assertThat(position("XYZ")).isNull();
        buy("NEW", "XYZ", 50, "250.00", "2026-11-10T10:00");
        assertThat(lots("XYZ")).containsExactly("NEW BUY 2026-11-10T10:00:00 null 50 50 250.00 250.00");
        JsonNode s = sell("S8", "XYZ", 25, "150.00", "2026-11-11T10:00");
        assertThat(soldLots(s)).containsExactly("NEW BUY 2026-11-10T10:00:00 null 25 125.00");
        assertThat(s.get("realizedPnl").asText()).isEqualTo("25.00");
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("AC5 冻结的现金不能买股票；入金/出金的检查算上股票买入")
    void ac5CashCheckWithFrozenPut() {
        smallAccount();
        String msg = rejectedStock(stock("BUY", "QRTV", 100, "600.00", "2026-11-03T10:00", null), 422,
                "CASH_INSUFFICIENT");
        assertThat(msg).isEqualTo("可用现金不足：需要 600.00；2026-11-03 10:00 时可用现金将变为 −80.00");

        buy("Q1", "QRTV", 100, "520.00", "2026-11-03T10:00");
        assertSummary("500.00", "500.00", "0.00");

        // withdrawal 1.00 later -> rejected (available 0.00)
        String before = snap();
        Resp w = transfer("WITHDRAWAL", "1.00", "2026-11-04T10:00", null);
        assertErrorShape(w, 422, "CASH_INSUFFICIENT", "/api/cash/transfers");
        assertThat(w.body().get("message").asText()).isEqualTo("可用现金不足：需要 1.00，可用 0.00");
        assertThat(snap()).isEqualTo(before);

        // editing the deposit to 999.00 -> rejected at the stock buy, no audit
        Resp e = exchange(HttpMethod.PUT, "/api/cash/transfers/" + ids.get("SD1"),
                transferBody("DEPOSIT", "999.00", "2026-11-02T10:00", null).toString(), MediaType.APPLICATION_JSON);
        assertErrorShape(e, 422, "CASH_INSUFFICIENT", "/api/cash/transfers/" + ids.get("SD1"));
        assertThat(e.body().get("message").asText()).isEqualTo("可用现金不足：修改后 2026-11-03 10:00 时可用现金将变为 −1.00");
        assertThat(snap()).isEqualTo(before);
        assertThat(auditTotal()).isEqualTo(1); // only the account CREATE

        // deleting the deposit -> rejected as well
        Resp d = exchange(HttpMethod.DELETE, "/api/cash/transfers/" + ids.get("SD1"), null,
                MediaType.APPLICATION_JSON);
        assertErrorShape(d, 422, "CASH_INSUFFICIENT", "/api/cash/transfers/" + ids.get("SD1"));
        assertThat(snap()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC5 补录买入：录入时点够、之后的出金时点不够 -> 拒绝并指出之后的时点")
    void ac5BackfilledBuyLaterPointNegative() {
        scenarioS();
        withdraw("8000.00", "2026-11-07T10:00");
        assertSummary("1099.35", "0.00", "1099.35");
        String msg = rejectedStock(stock("BUY", "XYZ", 100, "1200.00", "2026-11-04T11:00", null), 422,
                "CASH_INSUFFICIENT");
        assertThat(msg).isEqualTo("可用现金不足：需要 1,200.00；2026-11-07 10:00 时可用现金将变为 −100.65");
    }

    @Test
    @DisplayName("跨功能：股票买入用掉可用现金后，STO put 担保检查与出金按扣除后的可用现金判断")
    void stockBuyReducesAvailableForPutAndWithdrawal() {
        deposit("1000.00", "2026-11-02T10:00");
        buy("B", "QRTV", 100, "600.00", "2026-11-03T10:00");
        // STO 1 QFZ 5 P needs 500.00 collateral; available 400.00 (+20 premium) -> rejected
        String put = rejectedTrade(trade("STO", 1, "QFZ", "2026-12-18", "5", "PUT", "20.00", "2026-11-04T10:00", 100),
                "CASH_INSUFFICIENT");
        assertThat(put).startsWith("可用现金不足以担保卖出 put");
        // withdrawal of 401 rejected, 400 accepted
        Resp w = transfer("WITHDRAWAL", "401.00", "2026-11-04T10:00", null);
        assertErrorShape(w, 422, "CASH_INSUFFICIENT", "/api/cash/transfers");
        // a withdrawal BEFORE the buy that makes the later buy go negative -> rejected naming the buy's time
        Resp early = transfer("WITHDRAWAL", "500.00", "2026-11-02T11:00", null);
        assertErrorShape(early, 422, "CASH_INSUFFICIENT", "/api/cash/transfers");
        assertThat(early.body().get("message").asText())
                .isEqualTo("可用现金不足：需要 500.00；2026-11-03 10:00 时可用现金将变为 −100.00");
        withdraw("400.00", "2026-11-04T10:00");
        assertSummary("0.00", "0.00", "0.00");
        // a stock sale frees cash for a later STO put
        sell("S", "QRTV", 100, "600.00", "2026-11-05T10:00");
        okTrade(trade("STO", 1, "QFZ", "2026-12-18", "5", "PUT", "20.00", "2026-11-06T10:00", 100));
        assertSummary("620.00", "500.00", "120.00");
        // now deleting the sale would make the put collateral go negative
        String del = rejectedWrite(HttpMethod.DELETE, "S", null, 422, "CASH_INSUFFICIENT");
        // without the sale: 0.00 after the withdrawal, then STO put +20.00 / frozen 500.00 -> available −480.00
        assertThat(del).isEqualTo("可用现金不足：删除后 2026-11-06 10:00 时可用现金将变为 −480.00");
    }

    // ------------------------------------------------------------------ AC 6

    @Test
    @DisplayName("AC6 不能卖空；补录卖出不能让之后的卖出不够")
    void ac6NoShortSelling() {
        scenarioS();
        assertThat(rejectedStock(stock("SELL", "IXQZ", 151, "1300.00", "2026-11-13T09:00", null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("持股不足：2026-11-13 09:00 卖出 IXQZ 151 股，当时持有 150 股");
        assertThat(rejectedStock(stock("SELL", "XYZ", 100, "400.00", "2026-11-03T10:00", null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("持股不足：2026-11-03 10:00 卖出 XYZ 100 股，当时持有 0 股");
        assertThat(rejectedStock(stock("SELL", "QRTV", 1, "10.00", "2026-11-12T10:00", null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("持股不足：2026-11-12 10:00 卖出 QRTV 1 股，当时持有 0 股");
        assertThat(rejectedStock(stock("SELL", "IXQZ", 200, "1600.00", "2026-11-04T12:00", null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("补录后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股");
        sell("S6", "IXQZ", 150, "1300.00", "2026-11-13T09:00");
        assertThat(positionRows()).containsExactly("XYZ 200 666.67 3.3334 1");
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("AC7 转入按日期 00:00 参与 FIFO；当天的卖出可以用；日期/时间不能晚于当前")
    void ac7TransferInByDate() {
        accountId = String.valueOf(createAccount("转入账户"));
        deposit("1000.00", "2026-11-02T10:00");
        JsonNode t = transferIn("T", "QRTV", 100, "500.00", "2026-11-10");
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-10T05:00:00Z"); // EST
        JsonNode s = sell("S", "QRTV", 100, "550.00", "2026-11-10T09:30");
        assertThat(s.get("realizedPnl").asText()).isEqualTo("50.00");
        assertSummary("1550.00", "0.00", "1550.00");

        stockValidation(stock("TRANSFER_IN", "QRTV", 1, "5.00", "2026-11-14", null), "tradeDate=日期不能晚于今天");
        JsonNode today = transferIn("TODAY", "QRTV", 10, "50.00", "2026-11-13");
        assertThat(today.get("tradedAt").asText()).isEqualTo("2026-11-13T05:00:00Z");
        stockValidation(stock("BUY", "QRTV", 1, "5.00", "2026-11-13T10:01", null),
                "tradedAtLocal=成交时间不能晚于当前时间");
        stockValidation(stock("SELL", "QRTV", 1, "5.00", "2026-11-13T10:01", null),
                "tradedAtLocal=成交时间不能晚于当前时间");
        // exactly "now" is allowed
        buy("NOW", "QRTV", 1, "5.00", "2026-11-13T10:00");
    }

    @Test
    @DisplayName("Q86 同一天：转入排在当天任何买入前面；前一天的买入排在转入前面；夏令时换算")
    void q86SameDayOrderingAndDst() {
        deposit("5000.00", "2026-11-02T10:00");
        // a buy at 00:00 of the same day as the transfer, entered BEFORE it: same instant, lower id -> earlier in FIFO
        buy("B0", "QRTV", 10, "100.00", "2026-11-10T00:00");
        transferIn("T", "QRTV", 10, "50.00", "2026-11-10");
        buy("B1", "QRTV", 10, "70.00", "2026-11-10T00:01");
        buy("B9", "QRTV", 10, "90.00", "2026-11-09T23:59");
        assertThat(lots("QRTV")).containsExactly(
                "B9 BUY 2026-11-09T23:59:00 null 10 10 90.00 90.00",
                "B0 BUY 2026-11-10T00:00:00 null 10 10 100.00 100.00",
                "T TRANSFER_IN 2026-11-10T00:00:00 2026-11-10 10 10 50.00 50.00",
                "B1 BUY 2026-11-10T00:01:00 null 10 10 70.00 70.00");
        JsonNode s = sell("S", "QRTV", 25, "300.00", "2026-11-10T09:00");
        assertThat(soldLots(s)).containsExactly(
                "B9 BUY 2026-11-09T23:59:00 null 10 90.00",
                "B0 BUY 2026-11-10T00:00:00 null 10 100.00",
                "T TRANSFER_IN 2026-11-10T00:00:00 2026-11-10 5 25.00");
        assertThat(s.get("realizedPnl").asText()).isEqualTo("85.00");
        // EDT date (before 2026-11-01) -> 04:00Z; EST date -> 05:00Z
        assertThat(transferIn("E1", "DSTA", 1, "1.00", "2026-10-31").get("tradedAt").asText())
                .isEqualTo("2026-10-31T04:00:00Z");
        assertThat(transferIn("E2", "DSTA", 1, "1.00", "2026-11-01").get("tradedAt").asText())
                .isEqualTo("2026-11-01T04:00:00Z");
        assertThat(transferIn("E3", "DSTA", 1, "1.00", "2026-11-02").get("tradedAt").asText())
                .isEqualTo("2026-11-02T05:00:00Z");
    }

    // ------------------------------------------------------------------ AC 8

    @Test
    @DisplayName("AC8 改金额：现金、FIFO 重新计算；流水原地更新；审计与 hasAuditLog")
    void ac8EditAmount() {
        scenarioS();
        long ledgerId = getStock("S2").get("ledgerEntryId").asLong();
        JsonNode r = editOk("S2", edit(200, "1500.00", "2026-11-03T10:00", null, null));
        assertThat(r.get("amount").asText()).isEqualTo("1500.00");
        assertThat(r.get("cashChange").asText()).isEqualTo("-1500.00");
        assertThat(r.get("pricePerShare").asText()).isEqualTo("7.5000");
        assertThat(r.get("ledgerEntryId").asLong()).isEqualTo(ledgerId);
        assertSummary("9100.00", "0.00", "9100.00");
        JsonNode s4 = getStock("S4");
        assertThat(s4.get("soldCost").asText()).isEqualTo("1125.00");
        assertThat(s4.get("realizedPnl").asText()).isEqualTo("75.00");
        assertThat(soldLots(s4)).containsExactly("S1 TRANSFER_IN 2026-10-01T00:00:00 2026-10-01 100 750.00",
                "S2 BUY 2026-11-03T10:00:00 null 50 375.00");
        assertThat(positionRows()).containsExactly("IXQZ 150 1125.00 7.5000 1", "XYZ 200 666.67 3.3334 1");
        assertThat(ledgerCount()).isEqualTo(5);
        assertThat(ledgerRowFor("STOCK_BUY", ids.get("S2")).get("amount").asText()).isEqualTo("-1500.00");
        assertThat(ledgerRowFor("STOCK_BUY", ids.get("S2")).get("id").asLong()).isEqualTo(ledgerId);

        JsonNode audit = get(AUDIT).body().get("items").get(0);
        assertThat(audit.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(audit.get("objectType").asText()).isEqualTo("STOCK_TRADE");
        assertThat(audit.get("objectId").asLong()).isEqualTo(ids.get("S2"));
        assertThat(audit.get("summary").asText()).isEqualTo("买入 IXQZ 200 股");
        assertThat(changeTexts(audit)).containsExactly("金额：1,500.65 → 1,500.00");
        assertThat(audit.get("sideEffects")).isEmpty();
        JsonNode before = audit.get("before");
        JsonNode after = audit.get("after");
        assertThat(before.get("type").asText()).isEqualTo("BUY");
        assertThat(before.get("symbol").asText()).isEqualTo("IXQZ");
        assertThat(before.get("shares").asInt()).isEqualTo(200);
        assertThat(before.get("amount").asText()).isEqualTo("1500.65");
        assertThat(before.get("cashChange").asText()).isEqualTo("-1500.65");
        assertThat(before.get("tradedAtLocal").asText()).isEqualTo("2026-11-03T10:00:00");
        assertThat(before.has("note")).isTrue();
        assertThat(after.get("amount").asText()).isEqualTo("1500.00");
        assertThat(after.get("cashChange").asText()).isEqualTo("-1500.00");
        // filter by object
        JsonNode byObj = get(AUDIT + "?objectType=STOCK_TRADE&objectId=" + ids.get("S2")).body();
        assertThat(byObj.get("totalItems").asInt()).isEqualTo(1);

        // hasAuditLog only on S2
        for (JsonNode i : list("").get("items")) {
            assertThat(i.get("hasAuditLog").asBoolean()).as(line(i))
                    .isEqualTo(i.get("id").asLong() == ids.get("S2"));
        }
        // ledger rows still never carry 历史
        ledger(1, 100).get("items").forEach(row -> assertThat(row.get("hasAuditLog").asBoolean()).isFalse());
    }

    @Test
    @DisplayName("AC8 改转入日期：FIFO 重新排序；改转入总成本：盈亏变化、现金不变")
    void ac8EditTransferIn() {
        scenarioS();
        JsonNode r = editOk("S1", edit(100, "750.00", null, "2026-11-04", null));
        assertThat(r.get("tradeDate").asText()).isEqualTo("2026-11-04");
        assertThat(r.get("tradedAt").asText()).isEqualTo("2026-11-04T05:00:00Z");
        JsonNode s4 = getStock("S4");
        assertThat(soldLots(s4)).containsExactly("S2 BUY 2026-11-03T10:00:00 null 150 1125.49");
        assertThat(s4.get("soldCost").asText()).isEqualTo("1125.49");
        assertThat(s4.get("realizedPnl").asText()).isEqualTo("74.51");
        assertThat(position("IXQZ").get("shares").asInt()).isEqualTo(150);
        assertThat(lots("IXQZ")).containsExactly(
                "S2 BUY 2026-11-03T10:00:00 null 200 50 1500.65 375.16",
                "S1 TRANSFER_IN 2026-11-04T00:00:00 2026-11-04 100 100 750.00 750.00");
        assertThat(positionRows()).first().isEqualTo("IXQZ 150 1125.16 7.5011 2");
        assertSummary("9099.35", "0.00", "9099.35");
        assertThat(ledgerCount()).isEqualTo(5);
        JsonNode audit = get(AUDIT).body().get("items").get(0);
        assertThat(audit.get("summary").asText()).isEqualTo("转入 IXQZ 100 股");
        assertThat(changeTexts(audit)).containsExactly("日期：2026-10-01 → 2026-11-04");
        assertThat(audit.get("before").get("tradeDate").asText()).isEqualTo("2026-10-01");
        assertThat(audit.get("before").get("cashChange").asText()).isEqualTo("0.00");
        assertThat(audit.get("after").get("tradeDate").asText()).isEqualTo("2026-11-04");
    }

    @Test
    @DisplayName("AC8 改转入总成本 750.00 -> 800.00：S4 盈亏 +24.84，现金不变")
    void ac8EditTransferCost() {
        scenarioS();
        editOk("S1", edit(100, "800.00", null, "2026-10-01", null));
        assertThat(getStock("S4").get("realizedPnl").asText()).isEqualTo("24.84");
        assertThat(getStock("S4").get("soldCost").asText()).isEqualTo("1175.16");
        assertSummary("9099.35", "0.00", "9099.35");
        assertThat(changeTexts(get(AUDIT).body().get("items").get(0))).containsExactly("总成本：750.00 → 800.00");
    }

    @Test
    @DisplayName("AC8 改小股数、改卖出时间、没有改动、改标的/类型都被拒绝且不写审计")
    void ac8RejectedEdits() {
        scenarioS();
        assertThat(rejectedWrite(HttpMethod.PUT, "S2", edit(40, "1500.65", "2026-11-03T10:00", null, null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("修改后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 140 股");
        assertThat(rejectedWrite(HttpMethod.PUT, "S4", edit(150, "1200.00", "2026-11-02T10:00", null, null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("持股不足：2026-11-02 10:00 卖出 IXQZ 150 股，当时持有 100 股");
        // no change (numerically equal amount, seconds, blank note)
        assertThat(rejectedWrite(HttpMethod.PUT, "S4", edit(150, "1200.00", "2026-11-05T10:00", null, null), 422,
                "NO_CHANGES")).isEqualTo("没有修改");
        assertThat(rejectedWrite(HttpMethod.PUT, "S4", edit(150, "1200", "2026-11-05T10:00:00", null, "  "), 422,
                "NO_CHANGES")).isEqualTo("没有修改");
        assertThat(rejectedWrite(HttpMethod.PUT, "S1", edit(100, "750.00", null, "2026-10-01", null), 422,
                "NO_CHANGES")).isEqualTo("没有修改");
        // identity
        ObjectNode sym = edit(200, "1500.00", "2026-11-03T10:00", null, null);
        sym.put("symbol", "XYZ");
        assertThat(rejectedWrite(HttpMethod.PUT, "S2", sym, 422, "STOCK_TRADE_IDENTITY_NOT_EDITABLE"))
                .isEqualTo("标的或类型录错请删除后重新录入");
        ObjectNode typ = edit(200, "1500.00", "2026-11-03T10:00", null, null);
        typ.put("type", "SELL");
        assertThat(rejectedWrite(HttpMethod.PUT, "S2", typ, 422, "STOCK_TRADE_IDENTITY_NOT_EDITABLE"))
                .isEqualTo("标的或类型录错请删除后重新录入");
        // field errors and required time by original type
        String before = snap();
        Resp noDate = putStock(ids.get("S1"), edit(100, "750.00", "2026-10-01T00:00", null, null));
        assertErrorShape(noDate, 400, "VALIDATION_ERROR", STOCKS + "/" + ids.get("S1"));
        assertThat(details(noDate)).containsExactly("tradeDate=日期不能为空");
        Resp noTime = putStock(ids.get("S2"), edit(200, "1500.65", null, "2026-11-03", null));
        assertErrorShape(noTime, 400, "VALIDATION_ERROR", STOCKS + "/" + ids.get("S2"));
        assertThat(details(noTime)).containsExactly("tradedAtLocal=成交时间不能为空");
        Resp future = putStock(ids.get("S2"), edit(200, "1500.65", "2026-11-13T10:01", null, null));
        assertErrorShape(future, 400, "VALIDATION_ERROR", STOCKS + "/" + ids.get("S2"));
        assertThat(details(future)).containsExactly("tradedAtLocal=成交时间不能晚于当前时间");
        Resp zero = putStock(ids.get("S2"), edit(0, "0", "2026-11-03T10:00", null, null));
        assertErrorShape(zero, 400, "VALIDATION_ERROR", STOCKS + "/" + ids.get("S2"));
        assertThat(details(zero)).containsExactlyInAnyOrder("shares=股数必须是正整数", "amount=金额必须大于 0");
        Resp missing = putStock(999999, edit(1, "1.00", "2026-11-03T10:00", null, null));
        assertErrorShape(missing, 404, "NOT_FOUND", STOCKS + "/999999");
        assertErrorShape(get(STOCKS + "/abc"), 400, "BAD_REQUEST", STOCKS + "/abc");
        assertThat(snap()).isEqualTo(before);
        assertThat(auditTotal()).isZero();
        list("").get("items").forEach(i -> assertThat(i.get("hasAuditLog").asBoolean()).isFalse());
    }

    @Test
    @DisplayName("修改：同样的标的/类型被忽略；只改备注；修改让之后的出金变负被拒绝")
    void editIdentitySameIgnoredNoteAndCash() {
        scenarioS();
        ObjectNode same = edit(200, "1500.65", "2026-11-03T10:00", null, "改备注");
        same.put("symbol", " ixqz ");
        same.put("type", "BUY");
        JsonNode r = editOk("S2", same);
        assertThat(r.get("note").asText()).isEqualTo("改备注");
        assertThat(changeTexts(get(AUDIT).body().get("items").get(0))).containsExactly("备注：— → 改备注");
        assertThat(ledgerRowFor("STOCK_BUY", ids.get("S2")).get("remark").asText()).isEqualTo("改备注");

        withdraw("8000.00", "2026-11-07T10:00");
        String msg = rejectedWrite(HttpMethod.PUT, "S3", edit(300, "2100.00", "2026-11-04T10:00", null, null), 422,
                "CASH_INSUFFICIENT");
        assertThat(msg).isEqualTo("可用现金不足：修改后 2026-11-07 10:00 时可用现金将变为 −0.65");
        // editing a sale to receive less also checks cash: 9,099.35 − 1,200.00 + 100.64 − 8,000.00 = −0.01
        String sale = rejectedWrite(HttpMethod.PUT, "S4", edit(150, "100.64", "2026-11-05T10:00", null, null), 422,
                "CASH_INSUFFICIENT");
        assertThat(sale).isEqualTo("可用现金不足：修改后 2026-11-07 10:00 时可用现金将变为 −0.01");
        // moving a sale later than the withdrawal also fails
        assertThat(rejectedWrite(HttpMethod.PUT, "S4", edit(150, "1200.00", "2026-11-08T10:00", null, null), 422,
                "CASH_INSUFFICIENT")).contains("2026-11-07 10:00");
    }

    // ------------------------------------------------------------------ AC 9

    @Test
    @DisplayName("AC9 删除转入：FIFO 重新匹配、现金不变、审计；再查询不存在")
    void ac9DeleteTransferIn() {
        scenarioS();
        Resp d = deleteStock(ids.get("S1"));
        assertThat(d.status()).isEqualTo(204);
        assertThat(d.body()).isNull();
        JsonNode s4 = getStock("S4");
        assertThat(s4.get("realizedPnl").asText()).isEqualTo("74.51");
        assertThat(soldLots(s4)).containsExactly("S2 BUY 2026-11-03T10:00:00 null 150 1125.49");
        assertThat(positionRows()).first().isEqualTo("IXQZ 50 375.16 7.5032 1");
        assertSummary("9099.35", "0.00", "9099.35");
        assertThat(ledgerCount()).isEqualTo(5);

        JsonNode audit = get(AUDIT).body().get("items").get(0);
        assertThat(audit.get("operation").asText()).isEqualTo("DELETE");
        assertThat(audit.get("objectType").asText()).isEqualTo("STOCK_TRADE");
        assertThat(audit.get("objectId").asLong()).isEqualTo(ids.get("S1"));
        assertThat(audit.get("summary").asText()).isEqualTo("转入 IXQZ 100 股");
        assertThat(audit.get("after").isNull()).isTrue();
        assertThat(audit.get("changes")).isEmpty();
        JsonNode b = audit.get("before");
        assertThat(b.get("type").asText()).isEqualTo("TRANSFER_IN");
        assertThat(b.get("symbol").asText()).isEqualTo("IXQZ");
        assertThat(b.get("shares").asInt()).isEqualTo(100);
        assertThat(b.get("amount").asText()).isEqualTo("750.00");
        assertThat(b.get("tradeDate").asText()).isEqualTo("2026-10-01");
        assertThat(b.get("cashChange").asText()).isEqualTo("0.00");

        String path = STOCKS + "/" + ids.get("S1");
        assertErrorShape(get(path), 404, "NOT_FOUND", path);
        assertErrorShape(deleteStock(ids.get("S1")), 404, "NOT_FOUND", path);
        assertErrorShape(putStock(ids.get("S1"), edit(100, "750.00", null, "2026-10-02", null)), 404, "NOT_FOUND",
                path);
        // still visible in the audit filtered by type
        JsonNode byType = get(AUDIT + "?objectType=STOCK_TRADE").body();
        assertThat(byType.get("totalItems").asInt()).isEqualTo(1);
        assertThat(get(AUDIT + "?objectType=STOCK_TRADE&objectId=" + ids.get("S1")).body().get("totalItems").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("AC9 删除被卖出用到的买入被拒绝；删除卖出让之后出金为负被拒绝；删除 S5 成功")
    void ac9RejectedDeletes() {
        scenarioS();
        assertThat(rejectedWrite(HttpMethod.DELETE, "S2", null, 422, "SHARES_INSUFFICIENT"))
                .isEqualTo("删除后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股");
        assertThat(rejectedWrite(HttpMethod.DELETE, "S3", null, 422, "SHARES_INSUFFICIENT"))
                .isEqualTo("删除后 2026-11-06 10:00 的卖出 XYZ 100 股将超过当时持股 0 股");
        withdraw("8000.00", "2026-11-07T10:00");
        assertThat(rejectedWrite(HttpMethod.DELETE, "S4", null, 422, "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：删除后 2026-11-07 10:00 时可用现金将变为 −100.65");
        assertThat(auditTotal()).isZero();

        long s5Ledger = getStock("S5").get("ledgerEntryId").asLong();
        assertThat(deleteStock(ids.get("S5")).status()).isEqualTo(204);
        assertSummary("699.35", "0.00", "699.35");
        assertThat(position("XYZ").get("shares").asInt()).isEqualTo(300);
        assertThat(position("XYZ").get("totalCost").asText()).isEqualTo("1000.00");
        for (JsonNode row : ledger(1, 100).get("items")) {
            assertThat(row.get("id").asLong()).isNotEqualTo(s5Ledger);
        }
        assertThat(ledgerCount()).isEqualTo(5); // D1, S2, S3, S4, W
        assertThat(get(AUDIT + "?objectType=STOCK_TRADE").body().get("totalItems").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ AC 10

    @Test
    @DisplayName("AC10 同一时间按录入顺序")
    void ac10SameTimeEntryOrder() {
        accountId = String.valueOf(createAccount("同时账户"));
        deposit("1000.00", "2026-11-02T10:00");
        assertThat(rejectedStock(stock("SELL", "QRTV", 100, "520.00", "2026-11-10T10:00", null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("持股不足：2026-11-10 10:00 卖出 QRTV 100 股，当时持有 0 股");
        buy("B", "QRTV", 100, "500.00", "2026-11-10T10:00");
        JsonNode s = sell("S", "QRTV", 100, "520.00", "2026-11-10T10:00");
        assertThat(s.get("realizedPnl").asText()).isEqualTo("20.00");
        assertThat(listLabels("")).containsExactly("S", "B");
        assertThat(rejectedWrite(HttpMethod.PUT, "B", edit(100, "500.00", "2026-11-10T10:01", null, null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("修改后 2026-11-10 10:00 的卖出 QRTV 100 股将超过当时持股 0 股");
        // ledger shows the same order (sell above buy)
        assertThat(ledgerRows()).containsExactly("STOCK_SELL 520.00 1020.00 卖出 100 股 QRTV",
                "STOCK_BUY -500.00 500.00 买入 100 股 QRTV", "DEPOSIT 1000.00 1000.00 null");
    }

    @Test
    @DisplayName("Q19 同一时间：已有出金在前、本笔买入在后，按录入顺序检查")
    void sameTimeCashOrder() {
        deposit("1000.00", "2026-11-02T10:00");
        withdraw("600.00", "2026-11-03T10:00");
        assertThat(rejectedStock(stock("BUY", "QRTV", 100, "500.00", "2026-11-03T10:00", null), 422,
                "CASH_INSUFFICIENT")).isEqualTo("可用现金不足：需要 500.00；2026-11-03 10:00 时可用现金将变为 −100.00");
        buy("B", "QRTV", 100, "400.00", "2026-11-03T10:00");
        assertSummary("0.00", "0.00", "0.00");
    }

    // ------------------------------------------------------------------ AC 11

    @Test
    @DisplayName("AC11 格式校验：标的、股数、金额、备注、类型、时间")
    void ac11Validation() {
        scenarioS();
        stockValidation(stock("BUY", "", 10, "80.00", "2026-11-12T10:00", null), "symbol=标的不能为空");
        stockValidation(stock("BUY", "   ", 10, "80.00", "2026-11-12T10:00", null), "symbol=标的不能为空");
        stockValidation(stock("BUY", "IX QZ", 10, "80.00", "2026-11-12T10:00", null), "symbol=标的只能包含字母、数字和点");
        stockValidation(stock("BUY", "$IXQZ", 10, "80.00", "2026-11-12T10:00", null), "symbol=标的只能包含字母、数字和点");
        stockValidation(stock("BUY", "ABCDEFGHIJK", 10, "80.00", "2026-11-12T10:00", null), "symbol=标的最多 10 个字符");
        stockValidation(stock("BUY", "IXQZ", 0, "80.00", "2026-11-12T10:00", null), "shares=股数必须是正整数");
        stockValidation(stock("BUY", "IXQZ", -5, "80.00", "2026-11-12T10:00", null), "shares=股数必须是正整数");
        stockValidation(stock("BUY", "IXQZ", 1.5, "80.00", "2026-11-12T10:00", null), "shares=股数必须是正整数");
        stockValidation(stock("BUY", "IXQZ", null, "80.00", "2026-11-12T10:00", null), "shares=股数不能为空");
        stockValidation(stock("TRANSFER_IN", "IXQZ", 10_000_001, "80.00", "2026-11-12", null), "shares=股数过大");
        stockValidation(stock("BUY", "IXQZ", 10, "0", "2026-11-12T10:00", null), "amount=金额必须大于 0");
        stockValidation(stock("BUY", "IXQZ", 10, "-1.00", "2026-11-12T10:00", null), "amount=金额必须大于 0");
        stockValidation(stock("BUY", "IXQZ", 10, "1.234", "2026-11-12T10:00", null), "amount=金额最多 2 位小数");
        stockValidation(stock("BUY", "IXQZ", 10, null, "2026-11-12T10:00", null), "amount=金额不能为空");
        stockValidation(stock("TRANSFER_IN", "IXQZ", 10, "10000000000000.00", "2026-11-12", null), "amount=金额过大");
        stockValidation(stock("BUY", "IXQZ", 10, "80.00", "2026-11-12T10:00", "x".repeat(501)), "note=备注最多 500 个字符");
        stockValidation(stock(null, "IXQZ", 10, "80.00", "2026-11-12T10:00", null), "type=请选择类型");
        stockValidation(stock("BUY", "IXQZ", 10, "80.00", null, null), "tradedAtLocal=成交时间不能为空");
        stockValidation(stock("TRANSFER_IN", "IXQZ", 10, "80.00", null, null), "tradeDate=日期不能为空");
        // several at once
        stockValidation(stock("BUY", "", 0, "0", "2026-11-12T10:00", null), "symbol=标的不能为空",
                "shares=股数必须是正整数", "amount=金额必须大于 0");

        stockBadRequest("{\"type\":\"FOO\",\"symbol\":\"IXQZ\",\"shares\":1,\"amount\":\"1.00\","
                + "\"tradedAtLocal\":\"2026-11-12T10:00\"}", "type");
        stockBadRequest("{\"type\":\"BUY\",\"symbol\":\"IXQZ\",\"shares\":1,\"amount\":\"abc\","
                + "\"tradedAtLocal\":\"2026-11-12T10:00\"}", "amount");
        stockBadRequest("{\"type\":\"BUY\",\"symbol\":\"IXQZ\",\"shares\":\"abc\",\"amount\":\"1.00\","
                + "\"tradedAtLocal\":\"2026-11-12T10:00\"}", "shares");
        stockBadRequest("{\"type\":\"BUY\",\"symbol\":\"IXQZ\",\"shares\":1,\"amount\":\"1.00\","
                + "\"tradedAtLocal\":\"2026-11-12 10:00\"}", "tradedAtLocal");
        stockBadRequest("{\"type\":\"TRANSFER_IN\",\"symbol\":\"IXQZ\",\"shares\":1,\"amount\":\"1.00\","
                + "\"tradeDate\":\"2026-02-30\"}", "tradeDate");

        // legal edge values
        JsonNode norm = okStock("N", stock("BUY", " ixqz ", 10, "80.00", "2026-11-12T10:00", "x".repeat(500)));
        assertThat(norm.get("symbol").asText()).isEqualTo("IXQZ");
        assertThat(position("IXQZ").get("shares").asInt()).isEqualTo(160);
        assertThat(position("IXQZ").get("totalCost").asText()).isEqualTo("1205.49");
        assertThat(okStock("BRK", stock("BUY", "BRK.B", 1, "1.00", "2026-11-12T10:00", null)).get("symbol").asText())
                .isEqualTo("BRK.B");
        JsonNode max = okStock("MAX", stock("TRANSFER_IN", "QMAX", 10_000_000, "9999999999999.99", "2026-11-12",
                null));
        assertThat(max.get("shares").asInt()).isEqualTo(10_000_000);
        assertThat(max.get("amount").asText()).isEqualTo("9999999999999.99");
        assertThat(max.get("pricePerShare").asText()).isEqualTo("1000000.0000");
    }

    @Test
    @DisplayName("Q88 每股价格 4 位小数 HALF_UP")
    void q88PricePerShareRounding() {
        deposit("5000.00", "2026-11-02T10:00");
        assertThat(buy("A", "QRTV", 200, "1500.65", "2026-11-03T10:00").get("pricePerShare").asText())
                .isEqualTo("7.5033");
        assertThat(buy("B", "QRTW", 3, "10.00", "2026-11-03T10:00").get("pricePerShare").asText())
                .isEqualTo("3.3333");
        assertThat(buy("C", "QRTX", 3, "20.00", "2026-11-03T10:00").get("pricePerShare").asText())
                .isEqualTo("6.6667");
    }

    @Test
    @DisplayName("X-Account-Id 必填；不存在的账户 404")
    void accountHeader() {
        accountId = null;
        Resp r = get(STOCKS);
        assertErrorShape(r, 400, "ACCOUNT_REQUIRED", STOCKS);
        assertErrorShape(get(STOCK_POS), 400, "ACCOUNT_REQUIRED", STOCK_POS);
        accountId = "99";
        assertErrorShape(get(STOCKS), 404, "ACCOUNT_NOT_FOUND", STOCKS);
        assertErrorShape(postStock(stock("BUY", "IXQZ", 1, "1.00", "2026-11-12T10:00", null)), 404,
                "ACCOUNT_NOT_FOUND", STOCKS);
    }

    // ------------------------------------------------------------------ AC 12

    @Test
    @DisplayName("AC12 按账户隔离")
    void ac12AccountIsolation() {
        scenarioS();
        smallAccount();
        buy("Q1", "QRTV", 100, "520.00", "2026-11-03T10:00");
        assertThat(listLabels("")).containsExactly("Q1");
        assertThat(positionRows()).containsExactly("QRTV 100 520.00 5.2000 1");
        assertThat(rejectedStock(stock("SELL", "IXQZ", 1, "8.00", "2026-11-12T10:00", null), 422,
                "SHARES_INSUFFICIENT")).isEqualTo("持股不足：2026-11-12 10:00 卖出 IXQZ 1 股，当时持有 0 股");
        String path = STOCKS + "/" + ids.get("S2");
        assertErrorShape(get(path), 404, "NOT_FOUND", path);
        assertErrorShape(putStock(ids.get("S2"), edit(200, "1500.00", "2026-11-03T10:00", null, null)), 404,
                "NOT_FOUND", path);
        assertErrorShape(deleteStock(ids.get("S2")), 404, "NOT_FOUND", path);
        transferIn("QX", "IXQZ", 100, "700.00", "2026-10-01");
        assertThat(position("IXQZ").get("shares").asInt()).isEqualTo(100);
        // ledger of the small account has no stock rows of the default account
        assertThat(ledgerRows()).hasSize(3);

        accountId = "1";
        assertThat(position("IXQZ").get("shares").asInt()).isEqualTo(150);
        assertThat(positionRows()).containsExactly("IXQZ 150 1125.49 7.5033 1", "XYZ 200 666.67 3.3334 1");
        assertThat(listLabels("")).containsExactly("S5", "S4", "S3", "S2", "S1");
        assertSummary("9099.35", "0.00", "9099.35");
        String other = STOCKS + "/" + ids.get("Q1");
        assertErrorShape(get(other), 404, "NOT_FOUND", other);
    }

    // ------------------------------------------------------------------ AC 13

    @Test
    @DisplayName("AC13 与期权互不影响：STO call 不检查覆盖、股票不锁定；已平仓/盈亏汇总/首页不含股票")
    void ac13NoOptionInteraction() {
        scenarioS();
        JsonNode call = okTrade(trade("STO", 5, "IXQZ", "2026-12-18", "9", "CALL", "150.00", "2026-11-12T10:00", 100));
        assertThat(call.get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("9249.35", "0.00", "9249.35");
        JsonNode opt = get("/api/option-positions").body();
        assertThat(opt.get("positionCount").asInt()).isEqualTo(1);
        assertThat(opt.get("positions").get(0).get("frozenCash").asText()).isEqualTo("0.00");

        sell("S6", "IXQZ", 150, "1300.00", "2026-11-12T11:00");
        assertThat(position("IXQZ")).isNull();

        JsonNode closed = get("/api/closed-trades").body();
        assertThat(closed.get("totalItems").asInt()).isZero();
        assertThat(closed.get("items")).isEmpty();
        JsonNode pnl = get("/api/pnl-summary").body();
        assertThat(pnl.get("closedCount").asInt()).isZero();
        assertThat(pnl.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(pnl.get("bySymbol")).isEmpty();
        assertThat(pnl.get("byMonth")).isEmpty();
        JsonNode dash = get("/api/dashboard").body();
        assertThat(dash.get("realizedPnl").get("closedCount").asInt()).isZero();
        assertThat(dash.get("realizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(dash.get("currentMonthRealizedPnl").get("closedCount").asInt()).isZero();
        assertThat(dash.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(dash.get("positions").get("positionCount").asInt()).isEqualTo(1);
        // cash in the dashboard includes stock cash (shared balance)
        assertThat(dash.get("cash").get("totalCash").asText()).isEqualTo("10549.35");

        JsonNode optList = get("/api/option-trades?size=100").body();
        assertThat(optList.get("totalItems").asInt()).isEqualTo(1);
        assertThat(list("").get("totalItems").asInt()).isEqualTo(6);
        for (JsonNode i : list("").get("items")) {
            assertThat(i.get("id").asLong()).isIn(ids.values());
        }
    }

    @Test
    @DisplayName("期权已实现盈亏的数字在加入股票记录后不变")
    void optionNumbersUnchangedByStocks() {
        deposit("10000.00", "2026-11-02T10:00");
        okTrade(trade("BTO", 1, "IXQZ", "2026-12-18", "9", "CALL", "100.00", "2026-11-03T10:00", 100));
        ObjectNode c = json.createObjectNode();
        c.put("symbol", "IXQZ");
        c.put("expirationDate", "2026-12-18");
        c.put("strike", "9");
        c.put("optionType", "CALL");
        c.put("action", "STC");
        c.put("contracts", 1);
        c.put("netAmount", "150.00");
        c.put("tradedAtLocal", "2026-11-04T10:00");
        assertThat(postRaw("/api/option-trades/closes", c.toString(), MediaType.APPLICATION_JSON).status())
                .isEqualTo(201);
        String closedBefore = get("/api/closed-trades").body().toString();
        String pnlBefore = get("/api/pnl-summary").body().toString();
        JsonNode dashBefore = get("/api/dashboard").body();
        String optPosBefore = get("/api/option-positions").body().toString();
        String optListBefore = get("/api/option-trades?size=100").body().toString();

        transferIn("T", "IXQZ", 100, "750.00", "2026-10-01");
        buy("B", "IXQZ", 100, "800.00", "2026-11-05T10:00");
        sell("S", "IXQZ", 150, "1300.00", "2026-11-06T10:00");

        assertThat(get("/api/closed-trades").body().toString()).isEqualTo(closedBefore);
        assertThat(get("/api/pnl-summary").body().toString()).isEqualTo(pnlBefore);
        JsonNode dashAfter = get("/api/dashboard").body();
        assertThat(dashAfter.get("realizedPnl")).isEqualTo(dashBefore.get("realizedPnl"));
        assertThat(dashAfter.get("currentMonthRealizedPnl")).isEqualTo(dashBefore.get("currentMonthRealizedPnl"));
        assertThat(dashAfter.get("positions")).isEqualTo(dashBefore.get("positions"));
        assertThat(dashAfter.get("realizedPnl").get("total").asText()).isEqualTo("50.00");
        assertThat(get("/api/option-positions").body().toString()).isEqualTo(optPosBefore);
        assertThat(get("/api/option-trades?size=100").body().toString()).isEqualTo(optListBefore);
    }

    @Test
    @DisplayName("审计：objectType=STOCK_TRADE 筛选只含股票交易；只给 objectId 报错")
    void auditFilter() {
        scenarioS();
        editOk("S2", edit(200, "1500.00", "2026-11-03T10:00", null, null));
        Resp t = exchange(HttpMethod.PUT, "/api/cash/transfers/" + ids.get("D1"),
                transferBody("DEPOSIT", "10001.00", "2026-11-02T10:00", null).toString(), MediaType.APPLICATION_JSON);
        assertThat(t.status()).isEqualTo(200);
        assertThat(get(AUDIT).body().get("totalItems").asInt()).isEqualTo(2);
        JsonNode stockOnly = get(AUDIT + "?objectType=STOCK_TRADE").body();
        assertThat(stockOnly.get("totalItems").asInt()).isEqualTo(1);
        assertThat(stockOnly.get("items").get(0).get("objectType").asText()).isEqualTo("STOCK_TRADE");
        Resp onlyId = get(AUDIT + "?objectId=" + ids.get("S2"));
        assertErrorShape(onlyId, 400, "VALIDATION_ERROR", AUDIT);
    }
}
