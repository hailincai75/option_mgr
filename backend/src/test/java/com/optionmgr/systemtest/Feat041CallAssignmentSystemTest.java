package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-041 卖出 call 被指派 (ASG on a short call, generated stock record ASSIGN_SELL), end-to-end over HTTP against
 * the whole application (in-memory H2, profile "test"), only through the REST API of docs/api/api-contract.md
 * "FEAT-041" (extending "FEAT-013"). The clock is a {@link MutableClock}; unless a test moves it, "now" =
 * 2026-11-23 10:00 New York. Fictional tickers only.
 *
 * <p>Cleanup: {@link CashSystemTestBase#emptyLedger()} deletes stock_trade before option_trade, so ASSIGN_SELL rows
 * (FK option_trade_id → option_trade) are removed before the ASG they reference.
 *
 * <p>Every rejected write must leave cash, ledger, option trades, option positions, closed trades, stock trades,
 * stock positions and the audit log untouched.
 */
class Feat041CallAssignmentSystemTest extends OptionTradeSystemTestBase {

    static final Instant MON_1123_1000 = Instant.parse("2026-11-23T15:00:00Z");
    static final Instant TUE_1117_1000 = Instant.parse("2026-11-17T15:00:00Z");
    static final Instant THU_1119_1000 = Instant.parse("2026-11-19T15:00:00Z");
    static final Instant FRI_1120_1630 = Instant.parse("2026-11-20T21:30:00Z");
    static final Instant WED_1125_1000 = Instant.parse("2026-11-25T15:00:00Z");
    static final Instant MON_1130_1000 = Instant.parse("2026-11-30T15:00:00Z");

    static final String ASSIGNMENTS = "/api/option-trades/assignments";
    static final String EXPIRATIONS = "/api/option-trades/expirations";
    static final String CLOSES = "/api/option-trades/closes";
    static final String CLOSED = "/api/closed-trades";
    static final String AUDIT = "/api/audit-log";
    static final String STOCKS = "/api/stock-trades";
    static final String STOCK_POS = "/api/stock-positions";

    static final String C9 = "IXQZ 2026-11-20 9 C";
    static final String C10 = "IXQZ 2026-11-20 10 C";
    static final String AT_1620 = "2026-11-20T21:00:00Z";
    static final String AT_1620_LOCAL = "2026-11-20T16:00:00";

    long d1;
    long s1;
    long s2;
    long c1;
    long c2;
    long l1;

    @Override
    Instant now() {
        return MON_1123_1000;
    }

    // ================================================================== request helpers

    ObjectNode asgBody(String symbol, String exp, String strike, String type, Integer contracts, String date,
                       String fee, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        if (contracts != null) {
            n.put("contracts", contracts);
        }
        if (date != null) {
            n.put("assignmentDate", date);
        }
        if (fee != null) {
            n.put("assignmentFee", fee);
        }
        if (note == null) {
            n.putNull("note");
        } else {
            n.put("note", note);
        }
        return n;
    }

    /** IXQZ 2026-11-20 9 C. */
    ObjectNode c9Asg(Integer contracts, String date, String fee) {
        return asgBody("IXQZ", "2026-11-20", "9", "CALL", contracts, date, fee, null);
    }

    Resp postAssign(ObjectNode body) {
        return postRaw(ASSIGNMENTS, body.toString(), MediaType.APPLICATION_JSON);
    }

    /** POST an assignment that must succeed; checks the invariant parts of a call ASG representation. */
    JsonNode okAssign(ObjectNode body) {
        Resp r = postAssign(body);
        assertThat(r.status()).as("assign %s -> %s", body, r.body()).isEqualTo(201);
        JsonNode t = r.body();
        assertCallAsgShape(t);
        assertFrozenConsistent();
        return t;
    }

    /** Contract shape of a call ASG option-trade representation (FEAT-041 "assignment 对象"). */
    void assertCallAsgShape(JsonNode t) {
        assertThat(t.get("action").asText()).isEqualTo("ASG");
        assertThat(t.get("optionType").asText()).isEqualTo("CALL");
        assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(t.get("realizedPnl").asText()).as("option side 0.00").isEqualTo("0.00");
        assertThat(t.get("cashChange").isTextual()).isTrue();
        assertThat(t.get("frozenChange").asText()).as("short call: no frozen").isEqualTo("0.00");
        assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(t.get("expiredDirection").isNull()).isTrue();
        assertThat(t.get("ledgerEntryId").isIntegralNumber()).isTrue();
        assertThat(t.get("tradedAtLocal").asText()).endsWith("T16:00:00");
        Instant.parse(t.get("createdAt").asText());
        assertThat(t.get("coveredLots").isArray()).isTrue();
        t.get("coveredLots").forEach(l -> assertThat(l.get("frozen").asText()).isEqualTo("0.00"));
        JsonNode a = t.get("assignment");
        assertThat(a).isNotNull();
        assertThat(a.isObject()).isTrue();
        for (String f : List.of("assignmentDate", "assignmentFee", "premium", "saleAmount", "proceeds", "soldCost",
                "stockRealizedPnl")) {
            assertThat(a.get(f)).as("assignment.%s", f).isNotNull();
            assertThat(a.get(f).isTextual()).as("assignment.%s is a string", f).isTrue();
        }
        for (String f : List.of("purchaseAmount", "stockCost", "stockCostPerShare")) {
            assertThat(a.get(f)).as("assignment.%s present", f).isNotNull();
            assertThat(a.get(f).isNull()).as("call: assignment.%s is null", f).isTrue();
        }
        assertThat(a.get("soldLots").isArray()).isTrue();
        assertThat(a.get("shares").isIntegralNumber()).isTrue();
        assertThat(a.get("stockTradeId").isIntegralNumber()).isTrue();
        assertThat(a.get("assignmentDate").asText()).isEqualTo(t.get("tradedAtLocal").asText().substring(0, 10));
        assertThat(a.get("shares").asInt()).isEqualTo(t.get("contracts").asInt() * t.get("multiplier").asInt());
        // internal consistency of the amounts (Q8 provisional)
        assertThat(new java.math.BigDecimal(a.get("proceeds").asText())).isEqualByComparingTo(
                new java.math.BigDecimal(a.get("saleAmount").asText()).add(new java.math.BigDecimal(
                        a.get("premium").asText())).subtract(new java.math.BigDecimal(a.get("assignmentFee").asText())));
        assertThat(new java.math.BigDecimal(a.get("stockRealizedPnl").asText())).isEqualByComparingTo(
                new java.math.BigDecimal(a.get("proceeds").asText()).subtract(new java.math.BigDecimal(
                        a.get("soldCost").asText())));
        assertThat(new java.math.BigDecimal(t.get("cashChange").asText())).isEqualByComparingTo(
                new java.math.BigDecimal(a.get("saleAmount").asText()).subtract(new java.math.BigDecimal(
                        a.get("assignmentFee").asText())));
    }

    String rejectedAssign(ObjectNode body, String code) {
        Full before = full();
        return rejected(postAssign(body), before, code, ASSIGNMENTS);
    }

    void assignValidation(ObjectNode body, String... pairs) {
        Full before = full();
        Resp r = postAssign(body);
        assertErrorShape(r, 400, "VALIDATION_ERROR", ASSIGNMENTS);
        assertThat(details(r)).as("details for %s", body).containsExactlyInAnyOrder(pairs);
        assertThat(full()).isEqualTo(before);
    }

    List<String> details(Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("details").forEach(d -> out.add(d.get("field").asText() + "=" + d.get("message").asText()));
        return out;
    }

    long open(String action, int contracts, String symbol, String exp, String strike, String type, String net,
              String at, int multiplier) {
        return okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, multiplier)).get("id").asLong();
    }

    ObjectNode expBody(String symbol, String exp, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        return n;
    }

    JsonNode okExpire(String symbol, String exp, String strike, String type, int contracts) {
        Resp r = postRaw(EXPIRATIONS, expBody(symbol, exp, strike, type, contracts).toString(),
                MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("expire -> %s", r.body()).isEqualTo(201);
        assertFrozenConsistent();
        return r.body();
    }

    ObjectNode edit(int contracts, String net, String at, Integer multiplier, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        if (note != null) {
            n.put("note", note);
        }
        return n;
    }

    Resp putTrade(long id, ObjectNode body) {
        return exchange(HttpMethod.PUT, TRADES + "/" + id, body.toString(), MediaType.APPLICATION_JSON);
    }

    Resp deleteTrade(long id) {
        return exchange(HttpMethod.DELETE, TRADES + "/" + id, null, MediaType.APPLICATION_JSON);
    }

    JsonNode okEdit(long id, ObjectNode body) {
        Resp r = putTrade(id, body);
        assertThat(r.status()).as("PUT %d %s -> %s", id, body, r.body()).isEqualTo(200);
        assertFrozenConsistent();
        return r.body();
    }

    void okDelete(long id) {
        Resp r = deleteTrade(id);
        assertThat(r.status()).as("DELETE %d -> %s", id, r.body()).isEqualTo(204);
        assertFrozenConsistent();
    }

    ObjectNode stockBody(String type, String symbol, int shares, String amount, String time) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        if ("TRANSFER_IN".equals(type)) {
            n.put("tradeDate", time);
        } else {
            n.put("tradedAtLocal", time);
        }
        n.putNull("note");
        return n;
    }

    ObjectNode stockEdit(int shares, String amount, String tradedAtLocal) {
        ObjectNode n = json.createObjectNode();
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", tradedAtLocal);
        return n;
    }

    Resp postStock(ObjectNode body) {
        return postRaw(STOCKS, body.toString(), MediaType.APPLICATION_JSON);
    }

    long okStock(ObjectNode body) {
        Resp r = postStock(body);
        assertThat(r.status()).as("POST stock %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    Resp putStock(long id, ObjectNode body) {
        return exchange(HttpMethod.PUT, STOCKS + "/" + id, body.toString(), MediaType.APPLICATION_JSON);
    }

    Resp deleteStock(long id) {
        return exchange(HttpMethod.DELETE, STOCKS + "/" + id, null, MediaType.APPLICATION_JSON);
    }

    JsonNode getStock(long id) {
        Resp r = get(STOCKS + "/" + id);
        assertThat(r.status()).as("GET stock %d -> %s", id, r.body()).isEqualTo(200);
        return r.body();
    }

    // ------------------------------------------------------------------ snapshots

    record Full(State state, JsonNode positions, JsonNode ledger, JsonNode audit, JsonNode trades, JsonNode closed,
                JsonNode stocks, JsonNode stockPositions) {
    }

    Full full() {
        return new Full(state(), positionsBody(), ledger(1, 100), get(AUDIT + "?size=100").body(),
                get(TRADES + "?size=100").body(), get(CLOSED + "?size=100").body(), get(STOCKS + "?size=100").body(),
                get(STOCK_POS).body());
    }

    String rejected(Resp r, Full before, String code, String path) {
        assertErrorShape(r, 422, code, path);
        assertThat(r.body().get("details")).isEmpty();
        assertThat(full()).as("nothing changed after rejected %s", code).isEqualTo(before);
        return r.body().get("message").asText();
    }

    String rejectedEdit(long id, ObjectNode body, String code) {
        Full before = full();
        return rejected(putTrade(id, body), before, code, TRADES + "/" + id);
    }

    String rejectedDelete(long id, String code) {
        Full before = full();
        return rejected(deleteTrade(id), before, code, TRADES + "/" + id);
    }

    String rejectedStockPost(ObjectNode body, String code) {
        Full before = full();
        return rejected(postStock(body), before, code, STOCKS);
    }

    String rejectedStockDelete(long id, String code) {
        Full before = full();
        return rejected(deleteStock(id), before, code, STOCKS + "/" + id);
    }

    // ------------------------------------------------------------------ reads

    JsonNode positionsBody() {
        Resp r = get("/api/option-positions");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode position(String label) {
        for (JsonNode p : positionsBody().get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    List<String> positionLabels() {
        List<String> out = new ArrayList<>();
        positionsBody().get("positions").forEach(p -> out.add(p.get("contractLabel").asText()));
        return out;
    }

    void assertFrozenConsistent() {
        assertThat(positionsBody().get("frozenTotal").asText()).isEqualTo(summary().get("frozenCash").asText());
    }

    /** "closable/expiryPending/expirable/assignable" of a position. */
    String buttons(JsonNode p) {
        return p.get("closable").asBoolean() + "/" + p.get("expiryPending").asBoolean() + "/"
                + p.get("expirable").asBoolean() + "/" + p.get("assignable").asBoolean();
    }

    List<String> assignChoices(JsonNode p) {
        List<String> out = new ArrayList<>();
        p.get("assignChoices").forEach(c -> out.add(c.get("contracts").asInt() + ":" + c.get("shares").asInt()));
        return out;
    }

    JsonNode stockPositions() {
        Resp r = get(STOCK_POS);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    /** "SYMBOL shares totalCost averageCost" per stock position. */
    List<String> stockPositionRows() {
        List<String> out = new ArrayList<>();
        stockPositions().get("positions").forEach(p -> out.add(p.get("symbol").asText() + " "
                + p.get("shares").asText() + " " + p.get("totalCost").asText() + " " + p.get("averageCost").asText()));
        return out;
    }

    /** "type shares cost" of each lot of the stock position, FIFO order. */
    List<String> stockLots(String symbol) {
        for (JsonNode p : stockPositions().get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                List<String> out = new ArrayList<>();
                p.get("lots").forEach(l -> out.add(l.get("type").asText() + " " + l.get("tradeId").asLong() + " "
                        + l.get("remainingShares").asInt()));
                return out;
            }
        }
        return List.of();
    }

    JsonNode closed(String query) {
        Resp r = get(CLOSED + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("closed %s -> %s", query, r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode tradeList(String query) {
        Resp r = get(TRADES + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("trades %s -> %s", query, r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode stockList(String query) {
        Resp r = get(STOCKS + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("stocks %s -> %s", query, r.body()).isEqualTo(200);
        return r.body();
    }

    List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asLong()));
        return out;
    }

    List<Long> lotIds(JsonNode t) {
        List<Long> out = new ArrayList<>();
        t.get("coveredLots").forEach(l -> out.add(l.get("tradeId").asLong()));
        return out;
    }

    /** "type tradeId shares cost" per sold lot. */
    List<String> soldLots(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(l -> out.add(l.get("type").asText() + " " + l.get("tradeId").asLong() + " "
                + l.get("shares").asInt() + " " + l.get("cost").asText()));
        return out;
    }

    JsonNode dashboard() {
        Resp r = get("/api/dashboard");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode pnl() {
        Resp r = get("/api/pnl-summary");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode audit() {
        return get(AUDIT + "?size=100").body();
    }

    List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.get("text").asText()));
        return out;
    }

    long newAccount(String name) {
        String saved = accountId;
        accountId = null;
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        Resp r = postRaw("/api/accounts", n.toString(), MediaType.APPLICATION_JSON);
        accountId = saved;
        assertThat(r.status()).as("create account -> %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    String sumRow(JsonNode s) {
        return s.get("symbol").asText() + " " + s.get("option").get("closedCount").asInt() + " "
                + s.get("option").get("realizedPnl").asText() + " | " + s.get("stock").get("closedCount").asInt() + " "
                + s.get("stock").get("realizedPnl").asText() + " | " + s.get("closedCount").asInt() + " "
                + s.get("realizedPnl").asText();
    }

    /** Scenario C (in the current account): 7,520.00 / 0.00 / 7,520.00; IXQZ 300 shares 2,550.00. */
    void scenarioC() {
        d1 = deposit("10000.00", "2026-11-02T10:00").get("id").asLong();
        s1 = okStock(stockBody("BUY", "IXQZ", 150, "1200.00", "2026-11-03T10:00"));
        s2 = okStock(stockBody("BUY", "IXQZ", 150, "1350.00", "2026-11-04T10:00"));
        c1 = open("STO", 2, "IXQZ", "2026-11-20", "9", "CALL", "60.00", "2026-11-05T10:00", 100);
        c2 = open("STO", 1, "IXQZ", "2026-11-20", "9", "CALL", "25.00", "2026-11-06T10:00", 100);
        l1 = open("BTO", 1, "IXQZ", "2026-11-20", "10", "CALL", "15.00", "2026-11-06T11:00", 100);
        assertSummary("7520.00", "0.00", "7520.00");
        assertThat(stockPositionRows()).containsExactly("IXQZ 300 2550.00 8.5000");
    }

    /** AC2's assignment: 9 C, 3 contracts on 2026-11-20, fee 0.00. */
    JsonNode assignAll() {
        return okAssign(c9Asg(3, "2026-11-20", "0.00"));
    }

    /** AC4's "小账户" without stock: deposit 1,000.00, STO 1 IXQZ 9 C 20.00. Switches to it; returns its id. */
    String smallAccount() {
        String small = String.valueOf(newAccount("小账户"));
        accountId = small;
        deposit("1000.00", "2026-11-02T10:00");
        open("STO", 1, "IXQZ", "2026-11-20", "9", "CALL", "20.00", "2026-11-05T10:00", 100);
        assertSummary("1020.00", "0.00", "1020.00");
        return small;
    }

    static final String SHARES_0 =
            "持股不足：被指派需要 100 股 IXQZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）";

    // ================================================================== AC 1

    @Test
    @DisplayName("AC1 按钮（Q95）：11-17 9 C 平仓+被指派、10 C 无被指派；11-20 16:30 9 C 只有被指派；11-23 选项 2/3 张、默认 3、日期 11-20、当前持股 300；被指派 10 C → 多头拒绝")
    void ac1_buttonsChoicesAndLongCall() {
        scenarioC();

        clock.set(TUE_1117_1000);
        JsonNode c9 = position(C9);
        assertThat(c9.get("direction").asText()).isEqualTo("SHORT");
        assertThat(c9.get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(buttons(c9)).isEqualTo("true/false/false/true");
        assertThat(c9.get("assignmentDateMax").asText()).isEqualTo("2026-11-16");
        assertThat(c9.get("assignmentDateDefault").asText()).isEqualTo("2026-11-16");
        JsonNode c10 = position(C10);
        assertThat(buttons(c10)).isEqualTo("true/false/false/false");
        assertThat(c10.get("assignChoices")).isEmpty();
        assertThat(c10.get("assignmentDateMax").isNull()).isTrue();
        assertThat(c10.get("assignmentDateDefault").isNull()).isTrue();
        assertThat(c10.get("underlyingShares").isNull()).as("long call: underlyingShares null").isTrue();

        clock.set(FRI_1120_1630);
        c9 = position(C9);
        assertThat(buttons(c9)).as("16:30: only 被指派").isEqualTo("false/true/false/true");
        assertThat(c9.get("assignmentDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(buttons(position(C10))).isEqualTo("false/true/false/false");

        clock.set(MON_1123_1000);
        c9 = position(C9);
        assertThat(buttons(c9)).isEqualTo("false/false/true/true");
        assertThat(assignChoices(c9)).containsExactly("2:200", "3:300");
        assertThat(c9.get("assignmentDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(c9.get("assignmentDateDefault").asText()).isEqualTo("2026-11-20");
        assertThat(c9.get("underlyingShares").isIntegralNumber()).isTrue();
        assertThat(c9.get("underlyingShares").asInt()).isEqualTo(300);
        assertThat(buttons(position(C10))).isEqualTo("false/false/true/false");

        assertThat(rejectedAssign(asgBody("IXQZ", "2026-11-20", "10", "CALL", 1, "2026-11-20", null, null),
                "ASSIGN_LONG_POSITION")).isEqualTo("多头持仓不能被指派（主动行权见 FEAT-014）");
    }

    // ================================================================== AC 2

    @Test
    @DisplayName("AC2 全部被指派：响应各项、交出 S1+S2、+235.00；现金 10,220.00；无 9 C、无 IXQZ 股票；股票记录 ASSIGN_SELL；流水；已平仓 2 行 2 笔；汇总、首页 +235.00（2 笔）；无审计")
    void ac2_assignAll() {
        scenarioC();
        JsonNode t = assignAll();
        long id = t.get("id").asLong();
        assertThat(id).isGreaterThan(l1);
        assertThat(List.of(t.get("symbol").asText(), t.get("expirationDate").asText(), t.get("strike").asText(),
                t.get("contractLabel").asText(), t.get("contracts").asInt(), t.get("multiplier").asInt(),
                t.get("tradedAt").asText(), t.get("tradedAtLocal").asText(), t.get("cashChange").asText(),
                t.get("frozenChange").asText(), t.get("createdAt").asText()))
                .containsExactly("IXQZ", "2026-11-20", "9", C9, 3, 100, AT_1620, AT_1620_LOCAL, "2700.00", "0.00",
                        "2026-11-23T15:00:00Z");
        assertThat(t.get("note").isNull()).isTrue();
        JsonNode lots = t.get("coveredLots");
        assertThat(lots).hasSize(2);
        assertThat(List.of(lots.get(0).get("tradeId").asLong(), lots.get(0).get("action").asText(),
                lots.get(0).get("tradedAtLocal").asText(), lots.get(0).get("contracts").asInt(),
                lots.get(0).get("netAmount").asText(), lots.get(0).get("frozen").asText()))
                .containsExactly(c1, "STO", "2026-11-05T10:00:00", 2, "60.00", "0.00");
        assertThat(List.of(lots.get(1).get("tradeId").asLong(), lots.get(1).get("contracts").asInt(),
                lots.get(1).get("netAmount").asText())).containsExactly(c2, 1, "25.00");
        JsonNode a = t.get("assignment");
        assertThat(List.of(a.get("assignmentDate").asText(), a.get("assignmentFee").asText(),
                a.get("premium").asText(), a.get("shares").asInt(), a.get("saleAmount").asText(),
                a.get("proceeds").asText(), a.get("soldCost").asText(), a.get("stockRealizedPnl").asText()))
                .containsExactly("2026-11-20", "0.00", "85.00", 300, "2700.00", "2785.00", "2550.00", "235.00");
        assertThat(soldLots(a.get("soldLots"))).containsExactly("BUY " + s1 + " 150 1200.00",
                "BUY " + s2 + " 150 1350.00");
        JsonNode sl0 = a.get("soldLots").get(0);
        assertThat(List.of(sl0.get("tradedAt").asText(), sl0.get("tradedAtLocal").asText()))
                .containsExactly("2026-11-03T15:00:00Z", "2026-11-03T10:00:00");
        assertThat(sl0.get("tradeDate").isNull()).isTrue();
        long stockId = a.get("stockTradeId").asLong();
        assertThat(stockId).isGreaterThan(s2);

        // cash, positions
        assertSummary("10220.00", "0.00", "10220.00");
        assertThat(positionLabels()).doesNotContain(C9).contains(C10);
        assertThat(stockPositionRows()).as("no IXQZ stock left").isEmpty();

        // ledger
        JsonNode led = ledger(1, 100);
        assertThat(led.get("totalItems").asInt()).isEqualTo(7);
        JsonNode row = led.get("items").get(0);
        assertThat(row.get("id").asLong()).isEqualTo(t.get("ledgerEntryId").asLong());
        assertThat(List.of(row.get("occurredAt").asText(), row.get("sourceType").asText(),
                row.get("sourceId").asLong(), row.get("amount").asText(), row.get("frozenChange").asText(),
                row.get("balanceAfter").asText(), row.get("description").asText()))
                .containsExactly(AT_1620, "OPTION_ASSIGN", id, "2700.00", "0.00", "10220.00",
                        "被指派 3 张 IXQZ 2026-11-20 9 C，卖出 300 股 IXQZ");
        assertThat(row.get("hasAuditLog").asBoolean()).isFalse();

        // trade list
        assertThat(tradeList("").get("items").get(0)).isEqualTo(t);
        assertThat(getTrade(id)).isEqualTo(t);
        assertThat(ids(tradeList("action=ASG"))).containsExactly(id);

        // generated ASSIGN_SELL stock record
        JsonNode s = getStock(stockId);
        assertThat(List.of(s.get("type").asText(), s.get("symbol").asText(), s.get("shares").asInt(),
                s.get("amount").asText(), s.get("cashChange").asText(), s.get("pricePerShare").asText(),
                s.get("tradedAt").asText(), s.get("tradedAtLocal").asText(), s.get("soldCost").asText(),
                s.get("realizedPnl").asText(), s.get("assignmentTradeId").asLong()))
                .containsExactly("ASSIGN_SELL", "IXQZ", 300, "2785.00", "0.00", "9.2833", AT_1620, AT_1620_LOCAL,
                        "2550.00", "235.00", id);
        assertThat(s.get("soldLots")).isEqualTo(a.get("soldLots"));
        assertThat(s.get("editable").asBoolean()).isFalse();
        assertThat(s.get("deletable").asBoolean()).isFalse();
        assertThat(s.get("hasAuditLog").asBoolean()).isFalse();
        for (String f : List.of("tradeDate", "note", "ledgerEntryId")) {
            assertThat(s.get(f).isNull()).as("ASSIGN_SELL %s is null", f).isTrue();
        }
        JsonNode stocks = stockList("");
        assertThat(stocks.get("items")).hasSize(3);
        assertThat(stocks.get("items").get(0)).isEqualTo(s);
        assertThat(ids(stockList("type=ASSIGN_SELL"))).containsExactly(stockId);
        assertThat(ids(stockList("type=BUY"))).containsExactly(s2, s1);
        assertThat(stockList("type=SELL,ASSIGN_BUY").get("items")).isEmpty();

        // closed trades: 2 rows (Q100), stock row above option row
        JsonNode c = closed("");
        assertThat(c.get("totalItems").asInt()).isEqualTo(2);
        assertThat(c.get("filteredCount").asInt()).isEqualTo(2);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("235.00");
        JsonNode sr = c.get("items").get(0);
        assertThat(List.of(sr.get("type").asText(), sr.get("id").asLong(), sr.get("action").asText(),
                sr.get("tradedAt").asText(), sr.get("symbol").asText(), sr.get("shares").asInt(),
                sr.get("openingNetTotal").asText(), sr.get("closeNetAmount").asText(),
                sr.get("realizedPnl").asText(), sr.get("assignmentTradeId").asLong()))
                .containsExactly("STOCK", stockId, "ASSIGN_SELL", AT_1620, "IXQZ", 300, "-2550.00", "2785.00",
                        "235.00", id);
        assertThat(sr.get("soldLots")).hasSize(2);
        assertThat(sr.get("assignment").isNull()).as("stock row assignment null").isTrue();
        JsonNode or = c.get("items").get(1);
        assertThat(List.of(or.get("type").asText(), or.get("id").asLong(), or.get("action").asText(),
                or.get("closedDirection").asText(), or.get("contracts").asInt(), or.get("openingNetTotal").asText(),
                or.get("closeNetAmount").asText(), or.get("realizedPnl").asText()))
                .containsExactly("OPTION", id, "ASG", "SHORT", 3, "85.00", "0.00", "0.00");
        assertThat(or.get("assignmentTradeId").isNull()).isTrue();
        JsonNode oa = or.get("assignment");
        assertThat(List.of(oa.get("shares").asInt(), oa.get("proceeds").asText(), oa.get("stockTradeId").asLong()))
                .containsExactly(300, "2785.00", stockId);
        assertThat(oa.get("stockCost").isNull()).isTrue();
        assertThat(ids(closed("type=STOCK"))).containsExactly(stockId);
        assertThat(ids(closed("type=OPTION"))).containsExactly(id);
        assertThat(ids(closed("action=ASG"))).as("action is option-only: no stock row").containsExactly(id);
        assertThat(closed("startDate=2026-11-20&endDate=2026-11-20").get("filteredCount").asInt()).isEqualTo(2);

        // pnl summary and dashboard: 2 笔
        JsonNode p = pnl();
        assertThat(p.get("closedCount").asInt()).isEqualTo(2);
        assertThat(p.get("realizedPnlTotal").asText()).isEqualTo("235.00");
        assertThat(p.get("bySymbol")).hasSize(1);
        assertThat(sumRow(p.get("bySymbol").get(0))).isEqualTo("IXQZ 1 0.00 | 1 235.00 | 2 235.00");
        JsonNode d = dashboard();
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(2);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("235.00");
        assertThat(d.get("currentMonthRealizedPnl").get("closedCount").asInt()).isEqualTo(2);
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("235.00");

        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    // ================================================================== AC 3

    @Test
    @DisplayName("AC3 提前指派（11-19）：1 张非整笔被拒；2 张 11-18 费 5.00 → 实收 1,855.00、交出 S1 150 + S2 50、+205.00、现金 +1,795.00；剩 100 股 900.00；11-23 作废 C2 +25.00")
    void ac3_earlyFeePartialLot() {
        scenarioC();
        clock.set(THU_1119_1000);
        assertThat(position(C9).get("assignmentDateDefault").asText()).isEqualTo("2026-11-18");
        assertThat(rejectedAssign(c9Asg(1, "2026-11-18", null), "CLOSE_NOT_WHOLE_LOTS"))
                .isEqualTo("必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张");

        JsonNode t = okAssign(c9Asg(2, "2026-11-18", "5.00"));
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-18T21:00:00Z");
        assertThat(t.get("cashChange").asText()).isEqualTo("1795.00");
        assertThat(lotIds(t)).containsExactly(c1);
        JsonNode a = t.get("assignment");
        assertThat(List.of(a.get("assignmentFee").asText(), a.get("premium").asText(), a.get("shares").asInt(),
                a.get("saleAmount").asText(), a.get("proceeds").asText(), a.get("soldCost").asText(),
                a.get("stockRealizedPnl").asText()))
                .containsExactly("5.00", "60.00", 200, "1800.00", "1855.00", "1650.00", "205.00");
        assertThat(soldLots(a.get("soldLots"))).containsExactly("BUY " + s1 + " 150 1200.00",
                "BUY " + s2 + " 50 450.00");
        assertSummary("9315.00", "0.00", "9315.00");
        assertThat(stockPositionRows()).containsExactly("IXQZ 100 900.00 9.0000");
        assertThat(position(C9).get("contracts").asInt()).isEqualTo(1);
        assertThat(position(C9).get("underlyingShares").asInt()).isEqualTo(100);
        assertThat(ledgerRowFor("OPTION_ASSIGN", t.get("id").asLong()).get("amount").asText()).isEqualTo("1795.00");

        clock.set(MON_1123_1000);
        JsonNode exp = okExpire("IXQZ", "2026-11-20", "9", "CALL", 1);
        assertThat(exp.get("realizedPnl").asText()).isEqualTo("25.00");
        assertThat(lotIds(exp)).containsExactly(c2);
        assertThat(position(C9)).isNull();
    }

    // ================================================================== AC 4

    @Test
    @DisplayName("AC4 持股不足（Q75）：小账户 0 股被拒；补 11-21 买入仍被拒；删买入、补 11-02 转入 100 股 800.00 → 实收 920.00、+120.00、现金 +900.00")
    void ac4_sharesInsufficient() {
        smallAccount();
        assertThat(position(C9).get("underlyingShares").asInt()).isZero();
        assertThat(position(C9).get("assignable").asBoolean()).as("button shown even without stock").isTrue();
        assertThat(rejectedAssign(c9Asg(1, "2026-11-20", null), "SHARES_INSUFFICIENT")).isEqualTo(SHARES_0);

        long late = okStock(stockBody("BUY", "IXQZ", 100, "900.00", "2026-11-21T10:00"));
        assertThat(position(C9).get("underlyingShares").asInt()).as("current holding").isEqualTo(100);
        assertThat(rejectedAssign(c9Asg(1, "2026-11-20", null), "SHARES_INSUFFICIENT"))
                .as("a later buy does not count").isEqualTo(SHARES_0);

        assertThat(deleteStock(late).status()).isEqualTo(204);
        long tin = okStock(stockBody("TRANSFER_IN", "IXQZ", 100, "800.00", "2026-11-02"));
        JsonNode t = okAssign(c9Asg(1, "2026-11-20", null));
        JsonNode a = t.get("assignment");
        assertThat(List.of(a.get("saleAmount").asText(), a.get("premium").asText(), a.get("proceeds").asText(),
                a.get("soldCost").asText(), a.get("stockRealizedPnl").asText(), t.get("cashChange").asText()))
                .containsExactly("900.00", "20.00", "920.00", "800.00", "120.00", "900.00");
        assertThat(soldLots(a.get("soldLots"))).containsExactly("TRANSFER_IN " + tin + " 100 800.00");
        assertSummary("1920.00", "0.00", "1920.00");
        assertThat(stockPositionRows()).isEmpty();
    }

    @Test
    @DisplayName("边界（规则 4）：同一时间 16:00、在被指派之前录入的买入算持股 → 成功；股票盈亏可为负")
    void sameTimeBuyEnteredBeforeCountsAndNegativePnl() {
        smallAccount();
        okStock(stockBody("BUY", "IXQZ", 100, "1000.00", "2026-11-20T16:00"));
        JsonNode t = okAssign(c9Asg(1, "2026-11-20", null));
        assertThat(t.get("assignment").get("proceeds").asText()).isEqualTo("920.00");
        assertThat(t.get("assignment").get("soldCost").asText()).isEqualTo("1000.00");
        assertThat(t.get("assignment").get("stockRealizedPnl").asText()).isEqualTo("-80.00");
        assertThat(closed("type=STOCK").get("filteredRealizedPnlTotal").asText()).isEqualTo("-80.00");
        assertThat(pnl().get("realizedPnlTotal").asText()).isEqualTo("-80.00");
    }

    @Test
    @DisplayName("边界（规则 4）：被指派之后录入的同一时间 16:00 卖出不能用到已交出的股票 → 持股不足")
    void sameTimeSellEnteredAfterAssignment() {
        scenarioC();
        assignAll();
        Full before = full();
        Resp r = postStock(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-20T16:00"));
        assertErrorShape(r, 422, "SHARES_INSUFFICIENT", STOCKS);
        assertThat(r.body().get("message").asText()).isEqualTo("持股不足：2026-11-20 16:00 卖出 IXQZ 100 股，当时持有 0 股");
        assertThat(full()).isEqualTo(before);
    }

    // ================================================================== AC 5

    @Test
    @DisplayName("AC5 使之后卖出不成立：加 11-24 卖出 100 股；被指派 3 张 → 「补录后 2026-11-24 10:00 的卖出 IXQZ 100 股将超过当时持股 0 股」；2 张成功，卖出改用 S2 剩余")
    void ac5_laterSaleBlocks() {
        scenarioC();
        clock.set(WED_1125_1000);
        long sell = okStock(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-24T10:00"));
        assertThat(getStock(sell).get("soldCost").asText()).isEqualTo("800.00");
        assertThat(rejectedAssign(c9Asg(3, "2026-11-20", null), "SHARES_INSUFFICIENT"))
                .isEqualTo("补录后 2026-11-24 10:00 的卖出 IXQZ 100 股将超过当时持股 0 股");

        JsonNode t = okAssign(c9Asg(2, "2026-11-20", null));
        assertThat(t.get("assignment").get("soldCost").asText()).isEqualTo("1650.00");
        JsonNode after = getStock(sell);
        assertThat(after.get("soldCost").asText()).as("re-matched by FIFO: rest of S2").isEqualTo("900.00");
        assertThat(after.get("realizedPnl").asText()).isEqualTo("100.00");
        assertThat(soldLots(after.get("soldLots"))).containsExactly("BUY " + s2 + " 100 900.00");
        assertThat(stockPositionRows()).isEmpty();
        assertThat(audit().get("totalItems").asInt()).as("补录 writes no audit").isZero();
    }

    @Test
    @DisplayName("之后已有的 call 被指派不成立：「补录后 2026-11-27 16:00 的被指派 1 张 IXQZ 2026-11-27 10 C 需要 100 股，当时持股 0 股，请先删除该被指派」")
    void laterCallAssignmentBlocks() {
        clock.set(MON_1130_1000);
        accountId = String.valueOf(newAccount("测试 call 账户"));
        deposit("1000.00", "2026-11-02T10:00");
        okStock(stockBody("TRANSFER_IN", "IXQZ", 100, "800.00", "2026-11-02"));
        open("STO", 1, "IXQZ", "2026-11-20", "9", "CALL", "20.00", "2026-11-05T10:00", 100);
        open("STO", 1, "IXQZ", "2026-11-27", "10", "CALL", "15.00", "2026-11-05T11:00", 100);
        okAssign(asgBody("IXQZ", "2026-11-27", "10", "CALL", 1, "2026-11-27", null, null));
        assertThat(rejectedAssign(c9Asg(1, "2026-11-20", null), "SHARES_INSUFFICIENT")).isEqualTo(
                "补录后 2026-11-27 16:00 的被指派 1 张 IXQZ 2026-11-27 10 C 需要 100 股，当时持股 0 股，请先删除该被指派");
    }

    // ================================================================== AC 6

    @Test
    @DisplayName("AC6 删除被指派：现金 7,520.00、9 C 恢复 3 张、IXQZ 300 股 2,550.00；股票页 / 已平仓 / 流水中消失；一条审计（摘要、before 含各金额）；可重新记录")
    void ac6_deleteAndReassign() {
        scenarioC();
        JsonNode t = assignAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();
        long ledgerId = t.get("ledgerEntryId").asLong();

        okDelete(id);
        assertSummary("7520.00", "0.00", "7520.00");
        JsonNode c9 = position(C9);
        assertThat(c9.get("contracts").asInt()).isEqualTo(3);
        assertThat(c9.get("lotCount").asInt()).isEqualTo(2);
        assertThat(c9.get("underlyingShares").asInt()).isEqualTo(300);
        assertThat(stockPositionRows()).containsExactly("IXQZ 300 2550.00 8.5000");
        assertThat(stockLots("IXQZ")).containsExactly("BUY " + s1 + " 150", "BUY " + s2 + " 150");
        assertErrorShape(get(STOCKS + "/" + stockId), 404, "NOT_FOUND", STOCKS + "/" + stockId);
        assertErrorShape(get(TRADES + "/" + id), 404, "NOT_FOUND", TRADES + "/" + id);
        assertThat(ids(stockList(""))).containsExactly(s2, s1);
        assertThat(closed("").get("filteredCount").asInt()).isZero();
        assertThat(ledgerCount()).isEqualTo(6);
        ledger(1, 100).get("items").forEach(r -> assertThat(r.get("id").asLong()).isNotEqualTo(ledgerId));

        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).as("one audit record, no STOCK_TRADE record").isEqualTo(1);
        JsonNode rec = a.get("items").get(0);
        assertThat(List.of(rec.get("operation").asText(), rec.get("objectType").asText(),
                rec.get("objectId").asLong(), rec.get("summary").asText()))
                .containsExactly("DELETE", "OPTION_TRADE", id, "被指派 IXQZ 2026-11-20 9 C 3 张（卖出 300 股 IXQZ）");
        assertThat(rec.get("after").isNull()).isTrue();
        assertThat(rec.get("changes")).isEmpty();
        assertThat(rec.get("sideEffects")).isEmpty();
        JsonNode b = rec.get("before");
        assertThat(List.of(b.get("id").asLong(), b.get("optionType").asText(), b.get("contractLabel").asText(),
                b.get("action").asText(), b.get("contracts").asInt(), b.get("netAmount").asText(),
                b.get("tradedAt").asText(), b.get("cashChange").asText(), b.get("frozenChange").asText()))
                .containsExactly(id, "CALL", C9, "ASG", 3, "0.00", AT_1620, "2700.00", "0.00");
        assertThat(List.of(b.get("assignmentDate").asText(), b.get("assignmentFee").asText(),
                b.get("shares").asInt(), b.get("premium").asText(), b.get("saleAmount").asText(),
                b.get("proceeds").asText(), b.get("soldCost").asText(), b.get("stockRealizedPnl").asText(),
                b.get("stockTradeId").asLong()))
                .containsExactly("2026-11-20", "0.00", 300, "85.00", "2700.00", "2785.00", "2550.00", "235.00",
                        stockId);
        for (String f : List.of("purchaseAmount", "stockCost")) {
            assertThat(b.get(f) == null || b.get(f).isNull()).as("call before has no %s", f).isTrue();
        }

        JsonNode again = assignAll();
        assertThat(again.get("id").asLong()).isGreaterThan(id);
        assertThat(again.get("assignment").get("stockTradeId").asLong()).isGreaterThan(stockId);
        assertThat(again.get("assignment").get("proceeds").asText()).isEqualTo("2785.00");
        assertThat(again.get("hasAuditLog").asBoolean()).isFalse();
        assertSummary("10220.00", "0.00", "10220.00");
    }

    @Test
    @DisplayName("AC6 不可修改：修改被指派 → ASSIGNMENT_NOT_EDITABLE；改 / 删 ASSIGN_SELL → STOCK_TRADE_FROM_ASSIGNMENT；直接录入 ASSIGN_SELL → 400")
    void ac6_notEditable() {
        scenarioC();
        JsonNode t = assignAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();

        assertThat(rejectedEdit(id, edit(3, "1.00", "2026-11-20T16:00", null, "x"), "ASSIGNMENT_NOT_EDITABLE"))
                .isEqualTo("被指派不能修改，记错请删除后重新记录");

        String msg = "该股票记录由被指派生成，请在交易列表删除对应的被指派记录";
        Full before = full();
        assertThat(rejected(putStock(stockId, stockEdit(300, "2800.00", "2026-11-20T16:00")), before,
                "STOCK_TRADE_FROM_ASSIGNMENT", STOCKS + "/" + stockId)).isEqualTo(msg);
        assertThat(rejectedStockDelete(stockId, "STOCK_TRADE_FROM_ASSIGNMENT")).isEqualTo(msg);

        before = full();
        Resp direct = postStock(stockBody("ASSIGN_SELL", "IXQZ", 100, "900.00", "2026-11-20T16:00"));
        assertErrorShape(direct, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(direct)).contains("type=指派卖出由被指派生成，不能直接录入");
        assertThat(full()).isEqualTo(before);
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("股票类型筛选：未知类型文案含 ASSIGN_SELL")
    void stockTypeFilterMessage() {
        Resp bad = get(STOCKS + "?type=BUY,FOO");
        assertErrorShape(bad, 400, "BAD_REQUEST", STOCKS);
        assertThat(details(bad)).containsExactly("type=类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL");
        Resp closedBad = get(CLOSED + "?action=ASSIGN_SELL");
        assertErrorShape(closedBad, 400, "BAD_REQUEST", CLOSED);
    }

    // ================================================================== AC 7

    @Test
    @DisplayName("AC7 重放：C2 净额 25→30 → 实收 2,790.00、+240.00；现金 10,225.00；审计连带「被指派 #id 股票卖出实收 2,785.00 → 2,790.00」")
    void ac7_editCoveredNet() {
        scenarioC();
        JsonNode t = assignAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();
        okEdit(c2, edit(1, "30.00", "2026-11-06T10:00", 100, null));

        JsonNode after = getTrade(id);
        JsonNode a = after.get("assignment");
        assertThat(List.of(a.get("premium").asText(), a.get("proceeds").asText(), a.get("soldCost").asText(),
                a.get("stockRealizedPnl").asText(), after.get("cashChange").asText()))
                .containsExactly("90.00", "2790.00", "2550.00", "240.00", "2700.00");
        assertThat(after.get("hasAuditLog").asBoolean()).isFalse();
        JsonNode s = getStock(stockId);
        assertThat(List.of(s.get("amount").asText(), s.get("realizedPnl").asText(), s.get("pricePerShare").asText()))
                .containsExactly("2790.00", "240.00", "9.3000");
        assertThat(s.get("hasAuditLog").asBoolean()).isFalse();
        assertSummary("10225.00", "0.00", "10225.00");
        assertThat(closed("").get("filteredRealizedPnlTotal").asText()).isEqualTo("240.00");
        assertThat(closed("action=ASG").get("items").get(0).get("assignment").get("proceeds").asText())
                .isEqualTo("2790.00");

        JsonNode rec = audit().get("items").get(0);
        assertThat(rec.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(rec.get("objectId").asLong()).isEqualTo(c2);
        assertThat(texts(rec.get("sideEffects"))).contains("被指派 #" + id + " 股票卖出实收 2,785.00 → 2,790.00");
        JsonNode se = null;
        for (JsonNode x : rec.get("sideEffects")) {
            if ("proceeds".equals(x.get("field").asText())) {
                se = x;
            }
        }
        assertThat(se).as("sideEffect with field proceeds").isNotNull();
        assertThat(List.of(se.get("tradeId").asLong(), se.get("action").asText(), se.get("contractLabel").asText()))
                .containsExactly(id, "ASG", C9);
    }

    @Test
    @DisplayName("AC7 重放：补录 11-01 转入 100 股 700.00 → 交出 转入 100 + S1 150 + S2 50，成本 2,350.00、+435.00；剩 100 股 900.00；补录不写审计")
    void ac7_earlierTransferRematches() {
        scenarioC();
        long id = assignAll().get("id").asLong();
        long tin = okStock(stockBody("TRANSFER_IN", "IXQZ", 100, "700.00", "2026-11-01"));
        JsonNode a = getTrade(id).get("assignment");
        assertThat(soldLots(a.get("soldLots"))).containsExactly("TRANSFER_IN " + tin + " 100 700.00",
                "BUY " + s1 + " 150 1200.00", "BUY " + s2 + " 50 450.00");
        assertThat(a.get("soldCost").asText()).isEqualTo("2350.00");
        assertThat(a.get("stockRealizedPnl").asText()).isEqualTo("435.00");
        assertThat(a.get("proceeds").asText()).isEqualTo("2785.00");
        assertThat(stockPositionRows()).containsExactly("IXQZ 100 900.00 9.0000");
        assertThat(stockLots("IXQZ")).containsExactly("BUY " + s2 + " 100");
        assertThat(audit().get("totalItems").asInt()).isZero();
        assertSummary("10220.00", "0.00", "10220.00");
    }

    @Test
    @DisplayName("AC7 重放（契约「连带变化」）：修改 S1 金额 1,200.00 → 1,210.00 → 交出成本 2,560.00、+225.00；STOCK_TRADE 审计 sideEffects「被指派 #id 交出成本 2,550.00 → 2,560.00」")
    void editSoldLotWritesSoldCostSideEffect() {
        scenarioC();
        long id = assignAll().get("id").asLong();
        Resp r = putStock(s1, stockEdit(150, "1210.00", "2026-11-03T10:00"));
        assertThat(r.status()).as("PUT stock -> %s", r.body()).isEqualTo(200);
        JsonNode a = getTrade(id).get("assignment");
        assertThat(List.of(a.get("soldCost").asText(), a.get("stockRealizedPnl").asText()))
                .containsExactly("2560.00", "225.00");
        assertSummary("10210.00", "0.00", "10210.00");
        JsonNode rec = audit().get("items").get(0);
        assertThat(rec.get("objectType").asText()).isEqualTo("STOCK_TRADE");
        assertThat(texts(rec.get("sideEffects"))).containsExactly("被指派 #" + id + " 交出成本 2,550.00 → 2,560.00");
        JsonNode se = rec.get("sideEffects").get(0);
        assertThat(List.of(se.get("tradeId").asLong(), se.get("action").asText(), se.get("field").asText()))
                .containsExactly(id, "ASG", "soldCost");
    }

    @Test
    @DisplayName("AC7 重放被拒：删除 S2 → 「删除后…需要 300 股，当时持股 150 股，请先删除该被指派」；补录 11-10 卖出 100 股 → 同样（持股 200 股）；都不变、不写审计")
    void ac7_rejectedStockReplays() {
        scenarioC();
        assignAll();
        assertThat(rejectedStockDelete(s2, "SHARES_INSUFFICIENT")).isEqualTo(
                "删除后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 150 股，请先删除该被指派");
        assertThat(rejectedStockPost(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-10T10:00"),
                "SHARES_INSUFFICIENT")).isEqualTo(
                "补录后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 200 股，请先删除该被指派");
        Full before = full();
        assertThat(rejected(putStock(s2, stockEdit(50, "450.00", "2026-11-04T10:00")), before, "SHARES_INSUFFICIENT",
                STOCKS + "/" + s2)).isEqualTo(
                "修改后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 200 股，请先删除该被指派");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("Wheel：put 被指派得到的 ASSIGN_BUY 批次照常被 call 被指派交出；之后删除 put 被指派 → 「删除后…需要 100 股，当时持股 0 股，请先删除该被指派」")
    void wheelPutThenCall() {
        accountId = String.valueOf(newAccount("测试 wheel 账户"));
        deposit("5000.00", "2026-11-02T10:00");
        open("STO", 1, "QRTV", "2026-11-06", "40", "PUT", "50.00", "2026-11-02T11:00", 100);
        Resp pr = postAssign(asgBody("QRTV", "2026-11-06", "40", "PUT", 1, "2026-11-06", null, null));
        assertThat(pr.status()).as("put assignment -> %s", pr.body()).isEqualTo(201);
        long putAsg = pr.body().get("id").asLong();
        long assignBuy = pr.body().get("assignment").get("stockTradeId").asLong();
        open("STO", 1, "QRTV", "2026-11-20", "42", "CALL", "30.00", "2026-11-09T10:00", 100);

        JsonNode t = okAssign(asgBody("QRTV", "2026-11-20", "42", "CALL", 1, "2026-11-20", null, null));
        JsonNode a = t.get("assignment");
        assertThat(soldLots(a.get("soldLots"))).containsExactly("ASSIGN_BUY " + assignBuy + " 100 3950.00");
        assertThat(List.of(a.get("saleAmount").asText(), a.get("proceeds").asText(), a.get("soldCost").asText(),
                a.get("stockRealizedPnl").asText())).containsExactly("4200.00", "4230.00", "3950.00", "280.00");
        assertThat(stockPositionRows()).isEmpty();

        assertThat(rejectedDelete(putAsg, "SHARES_INSUFFICIENT")).isEqualTo(
                "删除后 2026-11-20 16:00 的被指派 1 张 QRTV 2026-11-20 42 C 需要 100 股，当时持股 0 股，请先删除该被指派");
    }

    // ================================================================== proceeds / cash

    @Test
    @DisplayName("卖出实收 ≤ 0 → ASSIGNMENT_PROCEEDS_NOT_POSITIVE「被指派后股票卖出实收将为 −5.00 / 0.00，请检查指派费」")
    void proceedsNotPositive() {
        scenarioC();
        assertThat(rejectedAssign(c9Asg(3, "2026-11-20", "2790.00"), "ASSIGNMENT_PROCEEDS_NOT_POSITIVE"))
                .isEqualTo("被指派后股票卖出实收将为 −5.00，请检查指派费");
        assertThat(rejectedAssign(c9Asg(3, "2026-11-20", "2785.00"), "ASSIGNMENT_PROCEEDS_NOT_POSITIVE"))
                .isEqualTo("被指派后股票卖出实收将为 0.00，请检查指派费");
        JsonNode t = okAssign(c9Asg(3, "2026-11-20", "2784.99"));
        assertThat(t.get("assignment").get("proceeds").asText()).isEqualTo("0.01");
        assertThat(t.get("cashChange").asText()).isEqualTo("-84.99");
    }

    @Test
    @DisplayName("现金（Q19）：指派费 > 行权金额且可用现金为 0 → CASH_INSUFFICIENT「可用现金不足：需要 0.46；2026-11-20 16:00 时可用现金将变为 −0.46」")
    void cashInsufficientByFee() {
        accountId = String.valueOf(newAccount("测试现金账户"));
        okStock(stockBody("TRANSFER_IN", "QFZ", 2, "20.00", "2026-11-02"));
        open("STO", 1, "QFZ", "2026-11-20", "12.345", "CALL", "0.50", "2026-11-03T10:00", 1);
        withdraw("0.50", "2026-11-04T10:00");
        assertSummary("0.00", "0.00", "0.00");
        assertThat(rejectedAssign(asgBody("QFZ", "2026-11-20", "12.345", "CALL", 1, "2026-11-20", "12.80", null),
                "CASH_INSUFFICIENT")).isEqualTo("可用现金不足：需要 0.46；2026-11-20 16:00 时可用现金将变为 −0.46");
    }

    // ================================================================== AC 8

    @Test
    @DisplayName("AC8 multiplier 1、行权价 12.345（Q99 向下取整）：行权金额 12.34、实收 12.84、交出 1 股 10.00、+2.84、现金 +12.34；流水说明带 multiplier")
    void ac8_multiplierOneRoundDown() {
        accountId = String.valueOf(newAccount("测试乘数账户"));
        deposit("100.00", "2026-11-02T10:00");
        long tin = okStock(stockBody("TRANSFER_IN", "QFZ", 2, "20.00", "2026-11-02"));
        open("STO", 1, "QFZ", "2026-11-20", "12.345", "CALL", "0.50", "2026-11-03T10:00", 1);
        JsonNode t = okAssign(asgBody("QFZ", "2026-11-20", "12.345", "CALL", 1, "2026-11-20", null, null));
        assertThat(t.get("multiplier").asInt()).isEqualTo(1);
        JsonNode a = t.get("assignment");
        assertThat(List.of(a.get("shares").asInt(), a.get("saleAmount").asText(), a.get("premium").asText(),
                a.get("proceeds").asText(), a.get("soldCost").asText(), a.get("stockRealizedPnl").asText(),
                t.get("cashChange").asText()))
                .containsExactly(1, "12.34", "0.50", "12.84", "10.00", "2.84", "12.34");
        assertThat(soldLots(a.get("soldLots"))).containsExactly("TRANSFER_IN " + tin + " 1 10.00");
        assertThat(ledgerRowFor("OPTION_ASSIGN", t.get("id").asLong()).get("description").asText())
                .isEqualTo("被指派 1 张 QFZ 2026-11-20 12.345 C（multiplier 1），卖出 1 股 QFZ");
        assertSummary("112.84", "0.00", "112.84");
        assertThat(stockPositionRows()).containsExactly("QFZ 1 10.00 10.0000");
    }

    // ================================================================== AC 9

    @Test
    @DisplayName("AC9 账户隔离：默认账户被指派后，小账户 9 C 仍 1 张、仍因 0 股被拒；小账户用默认账户的被指派 id 删除 → 404")
    void ac9_accountIsolation() {
        scenarioC();
        JsonNode t = assignAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();

        smallAccount();
        JsonNode c9 = position(C9);
        assertThat(c9.get("contracts").asInt()).isEqualTo(1);
        assertThat(c9.get("underlyingShares").asInt()).isZero();
        assertThat(rejectedAssign(c9Asg(1, "2026-11-20", null), "SHARES_INSUFFICIENT")).isEqualTo(SHARES_0);
        Full before = full();
        assertErrorShape(deleteTrade(id), 404, "NOT_FOUND", TRADES + "/" + id);
        assertErrorShape(get(STOCKS + "/" + stockId), 404, "NOT_FOUND", STOCKS + "/" + stockId);
        assertThat(full()).isEqualTo(before);

        accountId = "1";
        assertThat(getTrade(id).get("assignment").get("proceeds").asText()).isEqualTo("2785.00");
        assertSummary("10220.00", "0.00", "10220.00");
    }

    @Test
    @DisplayName("AC9 格式校验：指派费 −1.00 / 1.234、日期为空、备注 501 字、张数 0 → VALIDATION_ERROR，什么都不保存")
    void ac9_formatValidation() {
        scenarioC();
        assignValidation(c9Asg(3, "2026-11-20", "-1.00"), "assignmentFee=指派费不能为负数");
        assignValidation(c9Asg(3, "2026-11-20", "1.234"), "assignmentFee=指派费最多 2 位小数");
        assignValidation(c9Asg(3, null, null), "assignmentDate=指派日期不能为空");
        assignValidation(asgBody("IXQZ", "2026-11-20", "9", "CALL", 3, "2026-11-20", null, "x".repeat(501)),
                "note=备注最多 500 个字符");
        assignValidation(c9Asg(0, "2026-11-20", null), "contracts=张数必须是正整数");
    }

    @Test
    @DisplayName("日期：晚于到期日 / 今天未到 16:00 → ASSIGNMENT_DATE_INVALID（同 FEAT-013）；不存在的 call 合约 → NO_POSITION_TO_ASSIGN")
    void datesAndNoPosition() {
        scenarioC();
        clock.set(THU_1119_1000);
        assertThat(rejectedAssign(c9Asg(2, "2026-11-21", null), "ASSIGNMENT_DATE_INVALID"))
                .isEqualTo("指派日期不能晚于到期日（2026-11-20）");
        assertThat(rejectedAssign(c9Asg(2, "2026-11-19", null), "ASSIGNMENT_DATE_INVALID"))
                .isEqualTo("指派日期为今天时，美东 16:00 之后才能记录");
        assertThat(rejectedAssign(c9Asg(2, "2026-11-04", null), "NO_POSITION_TO_ASSIGN"))
                .isEqualTo("2026-11-04 16:00 时该合约（IXQZ 2026-11-20 9 C）没有持仓可被指派");
    }

    @Test
    @DisplayName("备注与指派费：交易 note、流水 remark 带备注；交易列表 / 流水金额为正")
    void noteAndRemark() {
        scenarioC();
        JsonNode t = okAssign(asgBody("IXQZ", "2026-11-20", "9", "CALL", 3, "2026-11-20", "5.00", "提前被指派"));
        assertThat(t.get("note").asText()).isEqualTo("提前被指派");
        JsonNode row = ledgerRowFor("OPTION_ASSIGN", t.get("id").asLong());
        assertThat(List.of(row.get("amount").asText(), row.get("remark").asText()))
                .containsExactly("2695.00", "提前被指派");
        assertThat(t.get("assignment").get("proceeds").asText()).isEqualTo("2780.00");
        assertThat(getStock(t.get("assignment").get("stockTradeId").asLong()).get("note").isNull()).isTrue();
    }
}
