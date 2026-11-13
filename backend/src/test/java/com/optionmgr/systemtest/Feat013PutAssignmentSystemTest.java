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
 * FEAT-013 卖出 put 被指派 (ASG), end-to-end over HTTP against the whole application (in-memory H2, profile "test"),
 * only through the REST API of docs/api/api-contract.md "FEAT-013". The clock is a {@link MutableClock}; unless a
 * test moves it, "now" = 2026-11-23 10:00 New York (Monday after the Friday 2026-11-20 expiration). Each acceptance
 * criterion states its own clock. Fictional tickers only.
 *
 * <p>Every rejected write must leave cash, ledger, option trades, option positions, closed trades, stock trades,
 * stock positions and the audit log untouched.
 */
class Feat013PutAssignmentSystemTest extends OptionTradeSystemTestBase {

    static final Instant MON_1000 = Instant.parse("2026-11-23T15:00:00Z");
    static final Instant TUE_1117_1000 = Instant.parse("2026-11-17T15:00:00Z");
    static final Instant THU_1119_1000 = Instant.parse("2026-11-19T15:00:00Z");
    static final Instant FRI_1120_1630 = Instant.parse("2026-11-20T21:30:00Z");

    static final String ASSIGNMENTS = "/api/option-trades/assignments";
    static final String EXPIRATIONS = "/api/option-trades/expirations";
    static final String CLOSES = "/api/option-trades/closes";
    static final String CLOSED = "/api/closed-trades";
    static final String AUDIT = "/api/audit-log";
    static final String STOCKS = "/api/stock-trades";
    static final String STOCK_POS = "/api/stock-positions";

    static final String XYZ = "XYZ 2026-11-20 45 P";
    static final String QRTV_P = "QRTV 2026-12-18 30 P";
    static final String QRTV_C = "QRTV 2026-11-20 25 C";
    static final String IXQZ = "IXQZ 2026-11-20 8 C";
    static final String AT_1620 = "2026-11-20T21:00:00Z";
    static final String AT_1620_LOCAL = "2026-11-20T16:00:00";

    long d1;
    long p1;
    long p2;
    long p3;
    long p4;
    long p5;

    @Override
    Instant now() {
        return MON_1000;
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

    ObjectNode xyzAsg(int contracts, String date, String fee) {
        return asgBody("XYZ", "2026-11-20", "45", "PUT", contracts, date, fee, null);
    }

    Resp postAssign(ObjectNode body) {
        return postRaw(ASSIGNMENTS, body.toString(), MediaType.APPLICATION_JSON);
    }

    /** POST an assignment that must succeed; checks the invariant parts of the ASG representation. */
    JsonNode okAssign(ObjectNode body) {
        Resp r = postAssign(body);
        assertThat(r.status()).as("assign %s -> %s", body, r.body()).isEqualTo(201);
        JsonNode t = r.body();
        assertAsgShape(t);
        assertFrozenConsistent();
        return t;
    }

    /** Contract shape of an ASG option-trade representation. */
    void assertAsgShape(JsonNode t) {
        assertThat(t.get("action").asText()).isEqualTo("ASG");
        assertThat(t.get("optionType").asText()).isEqualTo("PUT");
        assertThat(t.get("netAmount").isTextual()).isTrue();
        assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(t.get("realizedPnl").asText()).as("Q8: option side 0.00").isEqualTo("0.00");
        assertThat(t.get("cashChange").isTextual()).isTrue();
        assertThat(t.get("frozenChange").isTextual()).isTrue();
        assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(t.get("expiredDirection").isNull()).isTrue();
        assertThat(t.get("ledgerEntryId").isIntegralNumber()).as("ASG always has a ledger row").isTrue();
        assertThat(t.get("tradedAtLocal").asText()).endsWith("T16:00:00");
        assertThat(t.get("tradedAt").asText()).endsWith(":00:00Z");
        Instant.parse(t.get("createdAt").asText());
        assertThat(t.get("coveredLots").isArray()).isTrue();
        JsonNode a = t.get("assignment");
        assertThat(a).as("assignment object").isNotNull();
        assertThat(a.isObject()).isTrue();
        for (String f : List.of("assignmentDate", "assignmentFee", "premium", "purchaseAmount", "stockCost",
                "stockCostPerShare")) {
            assertThat(a.get(f).isTextual()).as("assignment.%s is a string", f).isTrue();
        }
        assertThat(a.get("shares").isIntegralNumber()).isTrue();
        assertThat(a.get("stockTradeId").isIntegralNumber()).isTrue();
        assertThat(a.get("stockCostPerShare").asText()).matches("\\d+\\.\\d{4}");
        assertThat(a.get("assignmentDate").asText()).isEqualTo(t.get("tradedAtLocal").asText().substring(0, 10));
        assertThat(a.get("shares").asInt()).isEqualTo(t.get("contracts").asInt() * t.get("multiplier").asInt());
    }

    /** Assignment that must be rejected with a 422 code; nothing changes; returns the message. */
    String rejectedAssign(ObjectNode body, String code) {
        Full before = full();
        return rejected(postAssign(body), before, code, ASSIGNMENTS);
    }

    /** 400 VALIDATION_ERROR whose details contain exactly these field=message pairs; nothing changes. */
    void assignValidation(ObjectNode body, String... pairs) {
        Full before = full();
        Resp r = postAssign(body);
        assertErrorShape(r, 400, "VALIDATION_ERROR", ASSIGNMENTS);
        assertThat(details(r)).as("details for %s", body).containsExactlyInAnyOrder(pairs);
        assertThat(full()).isEqualTo(before);
    }

    void assignBadRequest(String raw, String field) {
        Full before = full();
        Resp r = postRaw(ASSIGNMENTS, raw, MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "BAD_REQUEST", ASSIGNMENTS);
        assertThat(detailFields(r)).as("details for %s", raw).contains(field);
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

    ObjectNode closeBody(String action, int contracts, String symbol, String exp, String strike, String type,
                         String net, String at) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        return n;
    }

    Resp postClose(ObjectNode body) {
        return postRaw(CLOSES, body.toString(), MediaType.APPLICATION_JSON);
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

    Resp postStock(ObjectNode body) {
        return postRaw(STOCKS, body.toString(), MediaType.APPLICATION_JSON);
    }

    JsonNode okStock(ObjectNode body) {
        Resp r = postStock(body);
        assertThat(r.status()).as("POST stock %s -> %s", body, r.body()).isEqualTo(201);
        return r.body();
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

    String rejectedClose(ObjectNode body, String code) {
        Full before = full();
        return rejected(postClose(body), before, code, CLOSES);
    }

    String rejectedStockSell(ObjectNode body, String code) {
        Full before = full();
        return rejected(postStock(body), before, code, STOCKS);
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

    /** "contracts:shares" per assignChoices entry. */
    List<String> assignChoices(JsonNode p) {
        List<String> out = new ArrayList<>();
        p.get("assignChoices").forEach(c -> out.add(c.get("contracts").asInt() + ":" + c.get("shares").asInt()));
        return out;
    }

    void assertNotAssignable(JsonNode p) {
        assertThat(p.get("assignable").asBoolean()).as("%s assignable", p.get("contractLabel")).isFalse();
        assertThat(p.get("assignChoices")).as("%s assignChoices", p.get("contractLabel")).isEmpty();
        assertThat(p.get("assignmentDateMax").isNull()).isTrue();
        assertThat(p.get("assignmentDateDefault").isNull()).isTrue();
    }

    JsonNode stockPositions() {
        Resp r = get(STOCK_POS);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode stockPosition(String symbol) {
        for (JsonNode p : stockPositions().get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    /** "SYMBOL shares totalCost averageCost" per stock position. */
    List<String> stockPositionRows() {
        List<String> out = new ArrayList<>();
        stockPositions().get("positions").forEach(p -> out.add(p.get("symbol").asText() + " "
                + p.get("shares").asText() + " " + p.get("totalCost").asText() + " " + p.get("averageCost").asText()));
        return out;
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

    /** "symbol option.count option.pnl | stock.count stock.pnl | count pnl" of a pnl-summary bySymbol row. */
    String sumRow(JsonNode s) {
        return s.get("symbol").asText() + " " + s.get("option").get("closedCount").asInt() + " "
                + s.get("option").get("realizedPnl").asText() + " | " + s.get("stock").get("closedCount").asInt() + " "
                + s.get("stock").get("realizedPnl").asText() + " | " + s.get("closedCount").asInt() + " "
                + s.get("realizedPnl").asText();
    }

    /** Scenario P (in the current account). */
    void scenarioP() {
        d1 = deposit("20000.00", "2026-11-02T10:00").get("id").asLong();
        p1 = open("STO", 2, "XYZ", "2026-11-20", "45", "PUT", "240.00", "2026-11-03T10:00", 100);
        p2 = open("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "130.00", "2026-11-05T10:00", 100);
        p3 = open("STO", 1, "QRTV", "2026-12-18", "30", "PUT", "85.00", "2026-11-06T10:00", 100);
        p4 = open("BTO", 1, "QRTV", "2026-11-20", "25", "CALL", "40.00", "2026-11-06T11:00", 100);
        p5 = open("STO", 1, "IXQZ", "2026-11-20", "8", "CALL", "22.00", "2026-11-06T12:00", 100);
        assertSummary("20437.00", "16500.00", "3937.00");
        assertThat(ledgerCount()).isEqualTo(6);
    }

    /** AC2's assignment: XYZ 3 contracts on 2026-11-20, fee 0.00. */
    JsonNode assignXyzAll() {
        return okAssign(xyzAsg(3, "2026-11-20", "0.00"));
    }

    // ================================================================== AC 1

    @Test
    @DisplayName("AC1 按钮时间窗（Q95）：11-17 平仓+被指派；11-20 16:30 到期待确认只有被指派；11-23 作废+被指派；多头 / 空头 call 不可被指派")
    void ac1_buttonsAndChoices() {
        scenarioP();

        clock.set(TUE_1117_1000);
        JsonNode xyz = position(XYZ);
        assertThat(buttons(xyz)).isEqualTo("true/false/false/true");
        assertThat(buttons(position(QRTV_P))).isEqualTo("true/false/false/true");
        assertThat(buttons(position(QRTV_C))).isEqualTo("true/false/false/false");
        assertThat(buttons(position(IXQZ))).isEqualTo("true/false/false/false");
        assertNotAssignable(position(QRTV_C));
        assertNotAssignable(position(IXQZ));
        // before 16:00: latest selectable date is yesterday (Q94)
        assertThat(xyz.get("assignmentDateMax").asText()).isEqualTo("2026-11-16");
        assertThat(xyz.get("assignmentDateDefault").asText()).isEqualTo("2026-11-16");
        assertThat(assignChoices(xyz)).containsExactly("2:200", "3:300");

        clock.set(FRI_1120_1630);
        xyz = position(XYZ);
        assertThat(xyz.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(buttons(xyz)).as("16:30: only 被指派").isEqualTo("false/true/false/true");
        assertThat(xyz.get("assignmentDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(buttons(position(IXQZ))).isEqualTo("false/true/false/false");
        assertThat(buttons(position(QRTV_C))).isEqualTo("false/true/false/false");

        clock.set(MON_1000);
        xyz = position(XYZ);
        assertThat(xyz.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(buttons(xyz)).isEqualTo("false/false/true/true");
        assertThat(assignChoices(xyz)).containsExactly("2:200", "3:300");
        assertThat(xyz.get("assignmentDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(xyz.get("assignmentDateDefault").asText()).isEqualTo("2026-11-20");
        assertThat(buttons(position(QRTV_C))).isEqualTo("false/false/true/false");
        assertThat(buttons(position(IXQZ))).isEqualTo("false/false/true/false");
        // not yet expired short put: min(expiration, yesterday) = 11-22
        JsonNode qrtvP = position(QRTV_P);
        assertThat(qrtvP.get("assignable").asBoolean()).isTrue();
        assertThat(qrtvP.get("assignmentDateMax").asText()).isEqualTo("2026-11-22");
        assertThat(assignChoices(qrtvP)).containsExactly("1:100");

        assertThat(rejectedAssign(asgBody("IXQZ", "2026-11-20", "8", "CALL", 1, "2026-11-20", null, null),
                "ASSIGN_CALL_NOT_SUPPORTED")).isEqualTo("卖出 call 的被指派暂不支持（FEAT-041）");
        // a long position (call or put) is checked first: the long call gets the long-position text
        assertThat(rejectedAssign(asgBody("QRTV", "2026-11-20", "25", "CALL", 1, "2026-11-20", null, null),
                "ASSIGN_LONG_POSITION")).isEqualTo("多头持仓不能被指派（主动行权见 FEAT-014）");
    }

    @Test
    @DisplayName("AC1 多头 put 被指派 → ASSIGN_LONG_POSITION「多头持仓不能被指派（主动行权见 FEAT-014）」；不存在的合约 → NO_POSITION_TO_ASSIGN")
    void longPutAndNoPosition() {
        deposit("1000.00", "2026-11-02T10:00");
        open("BTO", 1, "QRTV", "2026-11-20", "25", "PUT", "40.00", "2026-11-06T11:00", 100);
        assertNotAssignable(position("QRTV 2026-11-20 25 P"));
        assertThat(rejectedAssign(asgBody("QRTV", "2026-11-20", "25", "PUT", 1, "2026-11-20", null, null),
                "ASSIGN_LONG_POSITION")).isEqualTo("多头持仓不能被指派（主动行权见 FEAT-014）");
        assertThat(rejectedAssign(asgBody("ZZZQ", "2026-11-20", "10", "PUT", 1, "2026-11-20", null, null),
                "NO_POSITION_TO_ASSIGN")).isEqualTo("2026-11-20 16:00 时该合约（ZZZQ 2026-11-20 10 P）没有持仓可被指派");
    }

    // ================================================================== AC 2

    @Test
    @DisplayName("AC2 到期被指派 3 张：响应、现金 6,937.00 / 3,000.00 / 3,937.00、股票批次、流水、交易列表、股票记录、已平仓、汇总、首页、无审计")
    void ac2_assignAll() {
        scenarioP();
        int expiredBefore = dashboard().get("positions").get("expiredUnprocessedCount").asInt();
        JsonNode t = assignXyzAll();
        long id = t.get("id").asLong();
        assertThat(id).isGreaterThan(p5);
        assertThat(List.of(t.get("symbol").asText(), t.get("expirationDate").asText(), t.get("strike").asText(),
                t.get("contractLabel").asText(), t.get("contracts").asInt(), t.get("multiplier").asInt(),
                t.get("tradedAt").asText(), t.get("tradedAtLocal").asText(), t.get("cashChange").asText(),
                t.get("frozenChange").asText(), t.get("createdAt").asText()))
                .containsExactly("XYZ", "2026-11-20", "45", XYZ, 3, 100, AT_1620, AT_1620_LOCAL, "-13500.00",
                        "-13500.00", "2026-11-23T15:00:00Z");
        assertThat(t.get("note").isNull()).isTrue();
        JsonNode lots = t.get("coveredLots");
        assertThat(lots).hasSize(2);
        assertThat(List.of(lots.get(0).get("tradeId").asLong(), lots.get(0).get("action").asText(),
                lots.get(0).get("tradedAt").asText(), lots.get(0).get("tradedAtLocal").asText(),
                lots.get(0).get("contracts").asInt(), lots.get(0).get("netAmount").asText(),
                lots.get(0).get("frozen").asText()))
                .containsExactly(p1, "STO", "2026-11-03T15:00:00Z", "2026-11-03T10:00:00", 2, "240.00", "9000.00");
        assertThat(List.of(lots.get(1).get("tradeId").asLong(), lots.get(1).get("contracts").asInt(),
                lots.get(1).get("netAmount").asText(), lots.get(1).get("frozen").asText()))
                .containsExactly(p2, 1, "130.00", "4500.00");
        JsonNode a = t.get("assignment");
        assertThat(List.of(a.get("assignmentDate").asText(), a.get("assignmentFee").asText(),
                a.get("premium").asText(), a.get("shares").asInt(), a.get("purchaseAmount").asText(),
                a.get("stockCost").asText(), a.get("stockCostPerShare").asText()))
                .containsExactly("2026-11-20", "0.00", "370.00", 300, "13500.00", "13130.00", "43.7667");
        long stockId = a.get("stockTradeId").asLong();
        assertThat(jdbc.queryForObject("SELECT traded_at FROM option_trade WHERE id = ?",
                java.time.OffsetDateTime.class, id).toInstant()).isEqualTo(Instant.parse(AT_1620));

        // cash: available unchanged
        assertSummary("6937.00", "3000.00", "3937.00");

        // positions
        assertThat(positionLabels()).doesNotContain(XYZ).contains(QRTV_P, QRTV_C, IXQZ);
        assertThat(stockPositionRows()).containsExactly("XYZ 300 13130.00 43.7667");
        JsonNode sp = stockPosition("XYZ");
        assertThat(sp.get("lots")).hasSize(1);
        JsonNode lot = sp.get("lots").get(0);
        assertThat(lot.get("type").asText()).isEqualTo("ASSIGN_BUY");
        assertThat(lot.get("tradeId").asLong()).isEqualTo(stockId);
        assertThat(lot.get("tradedAtLocal").asText()).isEqualTo(AT_1620_LOCAL);
        assertThat(lot.get("tradeDate").isNull()).isTrue();
        assertThat(lot.get("note").isNull()).isTrue();

        // ledger: new row on top
        JsonNode led = ledger(1, 100);
        assertThat(led.get("totalItems").asInt()).isEqualTo(7);
        JsonNode row = led.get("items").get(0);
        assertThat(row.get("id").asLong()).isEqualTo(t.get("ledgerEntryId").asLong());
        assertThat(List.of(row.get("occurredAt").asText(), row.get("sourceType").asText(),
                row.get("sourceId").asLong(), row.get("amount").asText(), row.get("frozenChange").asText(),
                row.get("balanceAfter").asText(), row.get("description").asText()))
                .containsExactly(AT_1620, "OPTION_ASSIGN", id, "-13500.00", "-13500.00", "6937.00",
                        "被指派 3 张 XYZ 2026-11-20 45 P，买入 300 股 XYZ");
        assertThat(row.get("remark").isNull()).isTrue();
        assertThat(row.get("hasAuditLog").asBoolean()).isFalse();

        // trade list: ASG on top; GET by id equals the POST response; action filter
        JsonNode list = tradeList("");
        assertThat(list.get("totalItems").asInt()).isEqualTo(6);
        JsonNode first = list.get("items").get(0);
        assertThat(first).isEqualTo(t);
        assertThat(getTrade(id)).isEqualTo(t);
        assertThat(list.get("items").get(1).get("assignment").isNull()).as("non-ASG rows: null").isTrue();
        assertThat(ids(tradeList("action=ASG"))).containsExactly(id);
        assertThat(tradeList("action=ASG,STO").get("totalItems").asInt()).isEqualTo(5);
        assertThat(ids(tradeList("action=ASG&startDate=2026-11-20&endDate=2026-11-20"))).containsExactly(id);
        assertThat(tradeList("action=ASG&startDate=2026-11-21").get("totalItems").asInt()).isZero();

        // generated stock record
        JsonNode s = getStock(stockId);
        assertThat(List.of(s.get("type").asText(), s.get("symbol").asText(), s.get("shares").asInt(),
                s.get("amount").asText(), s.get("cashChange").asText(), s.get("pricePerShare").asText(),
                s.get("tradedAt").asText(), s.get("tradedAtLocal").asText(), s.get("assignmentTradeId").asLong()))
                .containsExactly("ASSIGN_BUY", "XYZ", 300, "13130.00", "0.00", "43.7667", AT_1620, AT_1620_LOCAL,
                        id);
        assertThat(s.get("editable").asBoolean()).isFalse();
        assertThat(s.get("deletable").asBoolean()).isFalse();
        assertThat(s.get("hasAuditLog").asBoolean()).isFalse();
        for (String f : List.of("tradeDate", "note", "ledgerEntryId", "soldCost", "realizedPnl", "soldLots")) {
            assertThat(s.get(f).isNull()).as("ASSIGN_BUY %s is null", f).isTrue();
        }
        JsonNode stocks = stockList("");
        assertThat(stocks.get("items")).hasSize(1);
        assertThat(stocks.get("items").get(0)).isEqualTo(s);
        assertThat(stockList("type=ASSIGN_BUY").get("items")).hasSize(1);
        assertThat(stockList("type=BUY").get("items")).isEmpty();
        assertThat(stockList("type=BUY,ASSIGN_BUY").get("items")).hasSize(1);

        // closed trades: one ASG row (Q97 counted, 0.00)
        JsonNode c = closed("");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(c.get("totalItems").asInt()).isEqualTo(1);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        JsonNode cr = c.get("items").get(0);
        assertThat(List.of(cr.get("type").asText(), cr.get("id").asLong(), cr.get("tradedAt").asText(),
                cr.get("tradedAtLocal").asText(), cr.get("contractLabel").asText(), cr.get("action").asText(),
                cr.get("closedDirection").asText(), cr.get("contracts").asInt(), cr.get("multiplier").asInt(),
                cr.get("openingNetTotal").asText(), cr.get("openingNetPerContract").asText(),
                cr.get("closeNetAmount").asText(), cr.get("closeNetPerContract").asText(),
                cr.get("realizedPnl").asText()))
                .containsExactly("OPTION", id, AT_1620, AT_1620_LOCAL, XYZ, "ASG", "SHORT", 3, 100, "370.00", "123.33",
                        "0.00", "0.00", "0.00");
        assertThat(cr.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(lotIds(cr)).containsExactly(p1, p2);
        for (String f : List.of("shares", "costPerShare", "pricePerShare", "soldLots")) {
            assertThat(cr.get(f).isNull()).as("ASG closed row %s is null", f).isTrue();
        }
        JsonNode ca = cr.get("assignment");
        assertThat(List.of(ca.get("shares").asInt(), ca.get("stockCost").asText(), ca.get("stockTradeId").asLong()))
                .containsExactly(300, "13130.00", stockId);
        assertThat(closed("action=ASG").get("filteredCount").asInt()).isEqualTo(1);
        assertThat(closed("action=BTC,EXP").get("filteredCount").asInt()).isZero();
        assertThat(closed("type=OPTION").get("filteredCount").asInt()).isEqualTo(1);
        assertThat(closed("type=STOCK").get("filteredCount").asInt()).isZero();
        assertThat(closed("startDate=2026-11-20&endDate=2026-11-20").get("filteredCount").asInt()).isEqualTo(1);
        assertThat(closed("startDate=2026-11-21").get("filteredCount").asInt()).isZero();

        // pnl summary and dashboard (Q97)
        JsonNode p = pnl();
        assertThat(p.get("closedCount").asInt()).isEqualTo(1);
        assertThat(p.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(p.get("bySymbol")).hasSize(1);
        assertThat(sumRow(p.get("bySymbol").get(0))).isEqualTo("XYZ 1 0.00 | 0 0.00 | 1 0.00");
        assertThat(p.get("byMonth")).hasSize(1);
        assertThat(p.get("byMonth").get(0).get("month").asText()).isEqualTo("2026-11");
        assertThat(p.get("byMonth").get(0).get("closedCount").asInt()).isEqualTo(1);
        JsonNode d = dashboard();
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(d.get("currentMonthRealizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(d.get("positions").get("expiredUnprocessedCount").asInt()).isEqualTo(expiredBefore - 1);
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo("3000.00");

        // no audit for a new record
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    // ================================================================== AC 3

    @Test
    @DisplayName("AC3 之后卖出股票：15:00 持股不足；16:00（被指派之后录入）成功，成本 4,376.67、+23.33；已平仓 2 笔、汇总 XYZ 期权 0.00 股票 +23.33")
    void ac3_sellAfterAssignment() {
        scenarioP();
        long asg = assignXyzAll().get("id").asLong();

        assertThat(rejectedStockSell(stockBody("SELL", "XYZ", 100, "4400.00", "2026-11-20T15:00"),
                "SHARES_INSUFFICIENT")).isEqualTo("持股不足：2026-11-20 15:00 卖出 XYZ 100 股，当时持有 0 股");

        JsonNode sell = okStock(stockBody("SELL", "XYZ", 100, "4400.00", "2026-11-20T16:00"));
        long sellId = sell.get("id").asLong();
        assertThat(sell.get("soldCost").asText()).isEqualTo("4376.67");
        assertThat(sell.get("realizedPnl").asText()).isEqualTo("23.33");
        assertThat(sell.get("soldLots")).hasSize(1);
        assertThat(sell.get("soldLots").get(0).get("type").asText()).isEqualTo("ASSIGN_BUY");
        assertThat(sell.get("soldLots").get(0).get("shares").asInt()).isEqualTo(100);
        assertThat(sell.get("soldLots").get(0).get("cost").asText()).isEqualTo("4376.67");
        assertThat(stockPositionRows()).containsExactly("XYZ 200 8753.33 43.7667");
        assertSummary("11337.00", "3000.00", "8337.00");

        JsonNode c = closed("");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(2);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("23.33");
        List<String> rows = new ArrayList<>();
        c.get("items").forEach(i -> rows.add(i.get("type").asText() + " " + i.get("id").asLong() + " "
                + i.get("realizedPnl").asText()));
        assertThat(rows).as("same time: stock row above option row (Q92)")
                .containsExactly("STOCK " + sellId + " 23.33", "OPTION " + asg + " 0.00");
        JsonNode p = pnl();
        assertThat(p.get("closedCount").asInt()).isEqualTo(2);
        assertThat(p.get("realizedPnlTotal").asText()).isEqualTo("23.33");
        assertThat(sumRow(p.get("bySymbol").get(0))).isEqualTo("XYZ 1 0.00 | 1 23.33 | 2 23.33");
        // the ASG closed row keeps reporting the full stock cost of the assignment
        assertThat(closed("action=ASG").get("items").get(0).get("assignment").get("stockCost").asText())
                .isEqualTo("13130.00");
    }

    // ================================================================== AC 4

    @Test
    @DisplayName("AC4 提前指派（11-19 10:00）：1 张非整笔、日期 11-21 / 今天未到 16:00 / 11-20 未来 / 11-02 无持仓被拒；2 张 11-18 费 5.00 成功；11-23 作废剩余 P2")
    void ac4_earlyAssignmentFeeLotsDates() {
        scenarioP();
        clock.set(THU_1119_1000);
        assertThat(position(XYZ).get("assignmentDateMax").asText()).isEqualTo("2026-11-18");
        assertThat(position(XYZ).get("assignmentDateDefault").asText()).isEqualTo("2026-11-18");

        assertThat(rejectedAssign(xyzAsg(1, "2026-11-18", null), "CLOSE_NOT_WHOLE_LOTS"))
                .isEqualTo("必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张");
        assertThat(rejectedAssign(xyzAsg(4, "2026-11-18", null), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("被指派 4 张超过当时持仓 3 张");
        assertThat(rejectedAssign(xyzAsg(2, "2026-11-21", null), "ASSIGNMENT_DATE_INVALID"))
                .isEqualTo("指派日期不能晚于到期日（2026-11-20）");
        assertThat(rejectedAssign(xyzAsg(2, "2026-11-19", null), "ASSIGNMENT_DATE_INVALID"))
                .isEqualTo("指派日期为今天时，美东 16:00 之后才能记录");
        assertThat(rejectedAssign(xyzAsg(2, "2026-11-20", null), "ASSIGNMENT_DATE_INVALID"))
                .isEqualTo("指派日期不能晚于今天");
        assertThat(rejectedAssign(xyzAsg(2, "2026-11-02", null), "NO_POSITION_TO_ASSIGN"))
                .isEqualTo("2026-11-02 16:00 时该合约（XYZ 2026-11-20 45 P）没有持仓可被指派");

        JsonNode t = okAssign(xyzAsg(2, "2026-11-18", "5.00"));
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-18T21:00:00Z");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2026-11-18T16:00:00");
        assertThat(t.get("cashChange").asText()).isEqualTo("-9005.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-9000.00");
        assertThat(lotIds(t)).containsExactly(p1);
        JsonNode a = t.get("assignment");
        assertThat(List.of(a.get("assignmentDate").asText(), a.get("assignmentFee").asText(),
                a.get("premium").asText(), a.get("shares").asInt(), a.get("purchaseAmount").asText(),
                a.get("stockCost").asText(), a.get("stockCostPerShare").asText()))
                .containsExactly("2026-11-18", "5.00", "240.00", 200, "9000.00", "8765.00", "43.8250");
        assertSummary("11432.00", "7500.00", "3932.00");
        JsonNode xyz = position(XYZ);
        assertThat(xyz.get("contracts").asInt()).isEqualTo(1);
        assertThat(xyz.get("lotCount").asInt()).isEqualTo(1);
        assertThat(xyz.get("frozenCash").asText()).isEqualTo("4500.00");
        assertThat(buttons(xyz)).isEqualTo("true/false/false/true");
        assertThat(assignChoices(xyz)).containsExactly("1:100");
        assertThat(ledgerRowFor("OPTION_ASSIGN", t.get("id").asLong()).get("amount").asText()).isEqualTo("-9005.00");

        clock.set(MON_1000);
        JsonNode exp = okExpire("XYZ", "2026-11-20", "45", "PUT", 1);
        assertThat(exp.get("realizedPnl").asText()).isEqualTo("130.00");
        assertThat(lotIds(exp)).containsExactly(p2);
        assertSummary("11432.00", "3000.00", "8432.00");
        List<String> rows = new ArrayList<>();
        closed("symbol=XYZ").get("items").forEach(i -> rows.add(i.get("action").asText() + " "
                + i.get("realizedPnl").asText() + " " + i.get("tradedAtLocal").asText()));
        assertThat(rows).containsExactly("EXP 130.00 2026-11-20T16:00:00", "ASG 0.00 2026-11-18T16:00:00");
    }

    @Test
    @DisplayName("Q94 边界：今天 15:59:59 填今天被拒；16:00:00 整允许（记在今天 16:00）；最晚可选日期随之从昨天变为今天")
    void q94_todayAt1600Boundary() {
        scenarioP();
        clock.set(Instant.parse("2026-11-19T20:59:59Z"));
        assertThat(position(XYZ).get("assignmentDateMax").asText()).isEqualTo("2026-11-18");
        assertThat(rejectedAssign(xyzAsg(3, "2026-11-19", null), "ASSIGNMENT_DATE_INVALID"))
                .isEqualTo("指派日期为今天时，美东 16:00 之后才能记录");
        clock.set(Instant.parse("2026-11-19T21:00:00Z"));
        assertThat(position(XYZ).get("assignmentDateMax").asText()).isEqualTo("2026-11-19");
        JsonNode t = okAssign(xyzAsg(3, "2026-11-19", null));
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-19T21:00:00Z");
        assertThat(t.get("assignment").get("assignmentFee").asText()).as("fee omitted = 0.00").isEqualTo("0.00");
        assertThat(t.get("cashChange").asText()).isEqualTo("-13500.00");
    }

    @Test
    @DisplayName("Q95 到期待确认期间（到期日 16:30）记被指派：日期填到期日成功，记在 16:00，持仓清掉")
    void assignDuringExpiryPending() {
        scenarioP();
        clock.set(FRI_1120_1630);
        JsonNode t = okAssign(xyzAsg(3, "2026-11-20", null));
        assertThat(t.get("tradedAt").asText()).isEqualTo(AT_1620);
        assertThat(t.get("createdAt").asText()).isEqualTo("2026-11-20T21:30:00Z");
        assertThat(t.get("assignment").get("stockCost").asText()).isEqualTo("13130.00");
        assertThat(position(XYZ)).isNull();
    }

    @Test
    @DisplayName("边界：夏令时日期 2027-03-19 → 2027-03-19T20:00:00Z；周六 2027-03-20（不检查交易日）→ 2027-03-20T20:00:00Z")
    void dstAndHoliday() {
        clock.set(Instant.parse("2027-03-22T14:00:00Z"));
        deposit("10000.00", "2027-03-01T10:00");
        open("STO", 1, "XYZ", "2027-03-19", "20", "PUT", "50.00", "2027-03-02T10:00", 100);
        JsonNode t = okAssign(asgBody("XYZ", "2027-03-19", "20", "PUT", 1, "2027-03-19", null, null));
        assertThat(t.get("tradedAt").asText()).isEqualTo("2027-03-19T20:00:00Z");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2027-03-19T16:00:00");
        assertThat(t.get("assignment").get("stockCost").asText()).isEqualTo("1950.00");

        open("STO", 1, "QRTV", "2027-04-16", "10", "PUT", "20.00", "2027-03-03T10:00", 100);
        // no trading-day check (FEAT-009): a Saturday is accepted as the broker's date
        JsonNode sat = okAssign(asgBody("QRTV", "2027-04-16", "10", "PUT", 1, "2027-03-20", null, null));
        assertThat(sat.get("tradedAt").asText()).isEqualTo("2027-03-20T20:00:00Z");
    }

    @Test
    @DisplayName("边界：美股假日 2026-11-26（感恩节）作为指派日期 → 记在 2026-11-26T21:00:00Z")
    void holidayDate() {
        clock.set(Instant.parse("2026-11-30T15:00:00Z"));
        deposit("5000.00", "2026-11-16T10:00");
        open("STO", 1, "XYZ", "2026-11-27", "30", "PUT", "40.00", "2026-11-24T10:00", 100);
        JsonNode t = okAssign(asgBody("XYZ", "2026-11-27", "30", "PUT", 1, "2026-11-26", null, null));
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-26T21:00:00Z");
        assertThat(t.get("assignment").get("stockCost").asText()).isEqualTo("2960.00");
        assertSummary("2040.00", "0.00", "2040.00");
    }

    // ================================================================== AC 5

    @Test
    @DisplayName("AC5 先指派后作废：同为 16:00，作废在上；删除被指派被拒（作废不再整笔）；先删作废再删被指派都成功，全部恢复")
    void ac5_assignThenExpire() {
        scenarioP();
        JsonNode asg = okAssign(xyzAsg(2, "2026-11-20", null));
        long asgId = asg.get("id").asLong();
        assertThat(lotIds(asg)).containsExactly(p1);
        assertThat(asg.get("assignment").get("stockCost").asText()).isEqualTo("8760.00");
        JsonNode exp = okExpire("XYZ", "2026-11-20", "45", "PUT", 1);
        long expId = exp.get("id").asLong();
        assertThat(exp.get("realizedPnl").asText()).isEqualTo("130.00");
        assertThat(lotIds(exp)).containsExactly(p2);
        assertThat(exp.get("tradedAt").asText()).isEqualTo(AT_1620);
        assertThat(ids(tradeList("symbol=XYZ")).subList(0, 2)).containsExactly(expId, asgId);

        assertThat(rejectedDelete(asgId, "CLOSE_NOT_WHOLE_LOTS")).isEqualTo(
                "删除后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 1 张将不是整笔批次（最早一笔是 2 张，可为 2 张或 3 张），请先删除该到期作废");

        okDelete(expId);
        okDelete(asgId);
        JsonNode xyz = position(XYZ);
        assertThat(xyz.get("contracts").asInt()).isEqualTo(3);
        assertThat(xyz.get("lotCount").asInt()).isEqualTo(2);
        assertSummary("20437.00", "16500.00", "3937.00");
        assertThat(stockPosition("XYZ")).isNull();
        assertThat(stockList("").get("items")).isEmpty();
    }

    @Test
    @DisplayName("AC5 先作废后发现被指派：日期 = 到期日 → 无持仓（带作废提示）；日期 11-19 → 作废将超量；都什么都不变")
    void ac5_expireThenAssign() {
        scenarioP();
        okExpire("XYZ", "2026-11-20", "45", "PUT", 3);
        assertThat(rejectedAssign(xyzAsg(2, "2026-11-20", null), "NO_POSITION_TO_ASSIGN")).isEqualTo(
                "2026-11-20 16:00 时该合约（XYZ 2026-11-20 45 P）没有持仓可被指派（已于 2026-11-20 16:00 到期作废；如果实际是被指派，请先删除该到期作废）");
        assertThat(rejectedAssign(xyzAsg(2, "2026-11-19", null), "CLOSE_EXCEEDS_POSITION")).isEqualTo(
                "补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废");
    }

    // ================================================================== AC 6

    @Test
    @DisplayName("AC6 删除被指派：全部恢复、股票记录与流水一起消失；一条审计（摘要、before 完整、无 STOCK_TRADE 审计）；再次被指派得新 id、无审计")
    void ac6_deleteAndReassign() {
        scenarioP();
        JsonNode t = assignXyzAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();
        long ledgerId = t.get("ledgerEntryId").asLong();

        okDelete(id);
        assertSummary("20437.00", "16500.00", "3937.00");
        JsonNode xyz = position(XYZ);
        assertThat(xyz.get("contracts").asInt()).isEqualTo(3);
        assertThat(xyz.get("lotCount").asInt()).isEqualTo(2);
        assertThat(xyz.get("frozenCash").asText()).isEqualTo("13500.00");
        assertThat(xyz.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(buttons(xyz)).isEqualTo("false/false/true/true");
        assertThat(stockPosition("XYZ")).isNull();
        assertErrorShape(get(STOCKS + "/" + stockId), 404, "NOT_FOUND", STOCKS + "/" + stockId);
        assertErrorShape(get(TRADES + "/" + id), 404, "NOT_FOUND", TRADES + "/" + id);
        assertThat(ledgerCount()).isEqualTo(6);
        ledger(1, 100).get("items").forEach(r -> assertThat(r.get("id").asLong()).isNotEqualTo(ledgerId));
        assertThat(closed("").get("filteredCount").asInt()).isZero();
        assertThat(tradeList("").get("totalItems").asInt()).isEqualTo(5);

        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).as("one audit record, no STOCK_TRADE record").isEqualTo(1);
        JsonNode rec = a.get("items").get(0);
        assertThat(rec.get("operation").asText()).isEqualTo("DELETE");
        assertThat(rec.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(rec.get("objectId").asLong()).isEqualTo(id);
        assertThat(rec.get("summary").asText()).isEqualTo("被指派 XYZ 2026-11-20 45 P 3 张（买入 300 股 XYZ）");
        assertThat(rec.get("after").isNull()).isTrue();
        assertThat(rec.get("changes")).isEmpty();
        assertThat(rec.get("sideEffects")).isEmpty();
        JsonNode b = rec.get("before");
        assertThat(List.of(b.get("id").asLong(), b.get("symbol").asText(), b.get("expirationDate").asText(),
                b.get("strike").asText(), b.get("optionType").asText(), b.get("contractLabel").asText(),
                b.get("action").asText(), b.get("contracts").asInt(), b.get("multiplier").asInt(),
                b.get("netAmount").asText(), b.get("tradedAt").asText(), b.get("tradedAtLocal").asText(),
                b.get("cashChange").asText(), b.get("frozenChange").asText()))
                .containsExactly(id, "XYZ", "2026-11-20", "45", "PUT", XYZ, "ASG", 3, 100, "0.00", AT_1620,
                        AT_1620_LOCAL, "-13500.00", "-13500.00");
        assertThat(b.get("note").isNull()).isTrue();
        assertThat(List.of(b.get("assignmentDate").asText(), b.get("assignmentFee").asText(),
                b.get("shares").asInt(), b.get("purchaseAmount").asText(), b.get("stockCost").asText(),
                b.get("stockTradeId").asLong()))
                .containsExactly("2026-11-20", "0.00", 300, "13500.00", "13130.00", stockId);

        JsonNode again = assignXyzAll();
        assertThat(again.get("id").asLong()).isGreaterThan(id);
        assertThat(again.get("assignment").get("stockTradeId").asLong()).isNotEqualTo(stockId);
        assertThat(again.get("assignment").get("stockCost").asText()).isEqualTo("13130.00");
        assertThat(again.get("cashChange").asText()).isEqualTo("-13500.00");
        assertThat(again.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(again.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(tradeList("action=ASG").get("items").get(0).get("hasAuditLog").asBoolean()).isFalse();
        assertSummary("6937.00", "3000.00", "3937.00");
    }

    @Test
    @DisplayName("AC6 卖出 16:00 之后删除被指派 → SHARES_INSUFFICIENT，什么都不变、不写审计")
    void ac6_deleteRejectedBySale() {
        scenarioP();
        long id = assignXyzAll().get("id").asLong();
        okStock(stockBody("SELL", "XYZ", 100, "4400.00", "2026-11-20T16:00"));
        assertThat(rejectedDelete(id, "SHARES_INSUFFICIENT"))
                .isEqualTo("删除后 2026-11-20 16:00 的卖出 XYZ 100 股将超过当时持股 0 股");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("AC6 修改被指派 → ASSIGNMENT_NOT_EDITABLE；修改 / 删除生成的股票记录 → STOCK_TRADE_FROM_ASSIGNMENT；直接录入 ASSIGN_BUY / 用开仓、平仓接口录 ASG → 400")
    void ac6_notEditable() {
        scenarioP();
        JsonNode t = assignXyzAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();

        assertThat(rejectedEdit(id, edit(3, "1.00", "2026-11-20T16:00", null, "x"), "ASSIGNMENT_NOT_EDITABLE"))
                .isEqualTo("被指派不能修改，记错请删除后重新记录");
        Full before = full();
        assertErrorShape(putTrade(id, edit(-1, "1.00", "2026-11-20T16:00", null, null)), 400, "VALIDATION_ERROR",
                TRADES + "/" + id);
        assertThat(full()).isEqualTo(before);

        ObjectNode stockEdit = json.createObjectNode();
        stockEdit.put("shares", 300);
        stockEdit.put("amount", "13000.00");
        stockEdit.put("tradedAtLocal", "2026-11-20T16:00");
        String msg = "该股票记录由被指派生成，请在交易列表删除对应的被指派记录";
        before = full();
        assertThat(rejected(putStock(stockId, stockEdit), before, "STOCK_TRADE_FROM_ASSIGNMENT",
                STOCKS + "/" + stockId)).isEqualTo(msg);
        before = full();
        assertThat(rejected(deleteStock(stockId), before, "STOCK_TRADE_FROM_ASSIGNMENT", STOCKS + "/" + stockId))
                .isEqualTo(msg);

        before = full();
        Resp direct = postStock(stockBody("ASSIGN_BUY", "XYZ", 100, "4000.00", "2026-11-20T16:00"));
        assertErrorShape(direct, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(direct)).contains("type=指派买入由被指派生成，不能直接录入");
        assertThat(full()).isEqualTo(before);

        ObjectNode asOpen = trade("ASG", 1, "XYZ", "2026-11-20", "45", "PUT", "1.00", "2026-11-19T10:00", 100);
        Resp ro = postTrade(asOpen);
        assertErrorShape(ro, 400, "VALIDATION_ERROR", TRADES);
        assertThat(details(ro)).contains("action=本功能只支持开仓（BTO / STO），平仓请使用平仓功能");
        Resp rc = postClose(closeBody("ASG", 1, "XYZ", "2026-11-20", "45", "PUT", "1.00", "2026-11-19T10:00"));
        assertErrorShape(rc, 400, "VALIDATION_ERROR", CLOSES);
        assertThat(details(rc)).contains("action=本功能只支持平仓（BTC / STC），开仓请使用开仓功能");
        assertThat(full()).isEqualTo(before);
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    // ================================================================== AC 7

    @Test
    @DisplayName("AC7 修改 P2 净额 130→135：股票成本 13,125.00、每股 43.7500；现金 6,942.00 / 3,000.00 / 3,942.00；审计连带变化含股票成本")
    void ac7_editCoveredNet() {
        scenarioP();
        JsonNode t = assignXyzAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();
        okEdit(p2, edit(1, "135.00", "2026-11-05T10:00", 100, null));

        JsonNode after = getTrade(id);
        assertThat(after.get("assignment").get("premium").asText()).isEqualTo("375.00");
        assertThat(after.get("assignment").get("stockCost").asText()).isEqualTo("13125.00");
        assertThat(after.get("assignment").get("stockCostPerShare").asText()).isEqualTo("43.7500");
        assertThat(after.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(after.get("realizedPnl").asText()).isEqualTo("0.00");
        assertThat(after.get("hasAuditLog").asBoolean()).as("cascaded ASG keeps hasAuditLog false").isFalse();
        JsonNode s = getStock(stockId);
        assertThat(s.get("amount").asText()).isEqualTo("13125.00");
        assertThat(s.get("pricePerShare").asText()).isEqualTo("43.7500");
        assertThat(s.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(stockPositionRows()).containsExactly("XYZ 300 13125.00 43.7500");
        assertThat(closed("action=ASG").get("items").get(0).get("assignment").get("stockCost").asText())
                .isEqualTo("13125.00");
        assertThat(closed("action=ASG").get("items").get(0).get("openingNetTotal").asText()).isEqualTo("375.00");
        assertSummary("6942.00", "3000.00", "3942.00");

        JsonNode rec = audit().get("items").get(0);
        assertThat(rec.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(rec.get("objectId").asLong()).isEqualTo(p2);
        assertThat(texts(rec.get("sideEffects")))
                .containsExactly("被指派 #" + id + " 股票成本 13,130.00 → 13,125.00");
        JsonNode se = rec.get("sideEffects").get(0);
        assertThat(List.of(se.get("tradeId").asLong(), se.get("action").asText(), se.get("contractLabel").asText(),
                se.get("field").asText())).containsExactly(id, "ASG", XYZ, "stockCost");
    }

    @Test
    @DisplayName("AC7 被指派之后：删除 P2 / 补录 11-19 BTC 2 张 → 拒绝提示先删除该被指派；什么都不变、不写审计")
    void ac7_rejectedReplays() {
        scenarioP();
        assignXyzAll();
        assertThat(rejectedDelete(p2, "CLOSE_EXCEEDS_POSITION")).isEqualTo(
                "该开仓被 2026-11-20 16:00 的被指派 3 张覆盖（删除后被指派将超过当时持仓 2 张），请先删除该被指派");
        assertThat(rejectedEdit(p1, edit(1, "240.00", "2026-11-03T10:00", 100, null), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("该开仓被 2026-11-20 16:00 的被指派 3 张覆盖（修改后被指派将超过当时持仓 2 张），请先删除该被指派");
        assertThat(rejectedClose(closeBody("BTC", 2, "XYZ", "2026-11-20", "45", "PUT", "50.00", "2026-11-19T10:00"),
                "CLOSE_EXCEEDS_POSITION")).isEqualTo(
                "补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的被指派 3 张将超过当时持仓 1 张，请先删除该被指派");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("同一时间按录入顺序：被指派全部之后录入的 16:00 BTC → NO_POSITION_TO_CLOSE")
    void closeAtSameTimeAfterAssignment() {
        scenarioP();
        assignXyzAll();
        Full before = full();
        Resp r = postClose(closeBody("BTC", 2, "XYZ", "2026-11-20", "45", "PUT", "10.00", "2026-11-20T16:00"));
        assertErrorShape(r, 422, "NO_POSITION_TO_CLOSE", CLOSES);
        assertThat(full()).isEqualTo(before);
    }

    @Test
    @DisplayName("已有同标的股票：生成的批次按时间排进 FIFO（11-10 转入在前），卖出 150 股先用转入 100 股、再用指派买入 50 股（2,188.33）")
    void assignedLotOrderedByTime() {
        scenarioP();
        okStock(stockBody("TRANSFER_IN", "XYZ", 100, "4000.00", "2026-11-10"));
        assignXyzAll();
        JsonNode sell = okStock(stockBody("SELL", "XYZ", 150, "6600.00", "2026-11-23T09:30"));
        List<String> lots = new ArrayList<>();
        sell.get("soldLots").forEach(l -> lots.add(l.get("type").asText() + " " + l.get("shares").asInt() + " "
                + l.get("cost").asText()));
        assertThat(lots).containsExactly("TRANSFER_IN 100 4000.00", "ASSIGN_BUY 50 2188.33");
        assertThat(sell.get("soldCost").asText()).isEqualTo("6188.33");
        assertThat(sell.get("realizedPnl").asText()).isEqualTo("411.67");
        assertThat(stockPositionRows()).containsExactly("XYZ 250 10941.67 43.7667");
    }

    @Test
    @DisplayName("股票成本 ≤ 0（录错的开仓净额）→ STOCK_COST_NOT_POSITIVE「被指派后股票成本将为 −4.99…」")
    void stockCostNotPositive() {
        deposit("100.00", "2026-11-02T10:00");
        open("STO", 1, "QFZ", "2026-11-20", "0.01", "PUT", "5.00", "2026-11-03T10:00", 1);
        assertThat(rejectedAssign(asgBody("QFZ", "2026-11-20", "0.01", "PUT", 1, "2026-11-20", null, null),
                "STOCK_COST_NOT_POSITIVE")).isEqualTo("被指派后股票成本将为 −4.99，请检查被指派批次的开仓净额");
    }

    // ================================================================== AC 8

    @Test
    @DisplayName("AC8 新账户可用 0.00：指派费 5.00 → 可用现金不足；费 0.00 成功 0.00 / 0.00 / 0.00、股票 4,450.00；再删出金 → 50.00 / 0.00 / 50.00")
    void ac8_feeRejectedWhenNoAvailable() {
        accountId = String.valueOf(newAccount("测试指派账户"));
        deposit("4500.00", "2026-11-02T10:00");
        open("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "50.00", "2026-11-03T10:00", 100);
        long w = withdraw("50.00", "2026-11-10T10:00").get("id").asLong();
        assertSummary("4500.00", "4500.00", "0.00");

        assertThat(rejectedAssign(asgBody("QRTV", "2026-11-20", "45", "PUT", 1, "2026-11-20", "5.00", null),
                "CASH_INSUFFICIENT")).isEqualTo("可用现金不足：需要 5.00；2026-11-20 16:00 时可用现金将变为 −5.00");

        JsonNode t = okAssign(asgBody("QRTV", "2026-11-20", "45", "PUT", 1, "2026-11-20", "0.00", null));
        assertThat(t.get("assignment").get("stockCost").asText()).isEqualTo("4450.00");
        assertThat(t.get("assignment").get("stockCostPerShare").asText()).isEqualTo("44.5000");
        assertSummary("0.00", "0.00", "0.00");
        assertThat(stockPositionRows()).containsExactly("QRTV 100 4450.00 44.5000");

        Resp dw = exchange(HttpMethod.DELETE, "/api/cash/transfers/" + w, null, MediaType.APPLICATION_JSON);
        assertThat(dw.status()).isEqualTo(204);
        assertSummary("50.00", "0.00", "50.00");
    }

    // ================================================================== AC 9

    @Test
    @DisplayName("AC9 multiplier 1、行权价 12.345，被指派 2 张：买股 24.69、冻结 −24.70、成本 23.69、每股 11.8450；现金 76.31 / 0.00 / 76.31；流水说明带 multiplier")
    void ac9_multiplierOneTwoContracts() {
        long g1 = scenarioG();
        JsonNode t = okAssign(asgBody("QFZ", "2026-11-20", "12.345", "PUT", 2, "2026-11-20", null, null));
        assertThat(t.get("multiplier").asInt()).isEqualTo(1);
        assertThat(lotIds(t)).hasSize(2).first().isEqualTo(g1);
        JsonNode a = t.get("assignment");
        assertThat(List.of(a.get("shares").asInt(), a.get("purchaseAmount").asText(), a.get("premium").asText(),
                a.get("stockCost").asText(), a.get("stockCostPerShare").asText()))
                .containsExactly(2, "24.69", "1.00", "23.69", "11.8450");
        assertThat(t.get("cashChange").asText()).isEqualTo("-24.69");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-24.70");
        assertSummary("76.31", "0.00", "76.31");
        assertThat(ledgerRowFor("OPTION_ASSIGN", t.get("id").asLong()).get("description").asText())
                .isEqualTo("被指派 2 张 QFZ 2026-11-20 12.345 P（multiplier 1），买入 2 股 QFZ");
        assertThat(stockPositionRows()).containsExactly("QFZ 2 23.69 11.8450");
    }

    @Test
    @DisplayName("AC9 另起：被指派 1 张 → 买股 12.35（向上取整）、成本 11.85；现金 88.65 / 12.35 / 76.30")
    void ac9_multiplierOneSingleContract() {
        scenarioG();
        JsonNode t = okAssign(asgBody("QFZ", "2026-11-20", "12.345", "PUT", 1, "2026-11-20", null, null));
        assertThat(t.get("assignment").get("purchaseAmount").asText()).isEqualTo("12.35");
        assertThat(t.get("assignment").get("stockCost").asText()).isEqualTo("11.85");
        assertThat(t.get("cashChange").asText()).isEqualTo("-12.35");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-12.35");
        assertSummary("88.65", "12.35", "76.30");
    }

    /** AC9 account with G1, G2; returns G1's id. */
    long scenarioG() {
        accountId = String.valueOf(newAccount("测试乘数账户"));
        deposit("100.00", "2026-11-02T10:00");
        long g1 = open("STO", 1, "QFZ", "2026-11-20", "12.345", "PUT", "0.50", "2026-11-03T10:00", 1);
        open("STO", 1, "QFZ", "2026-11-20", "12.345", "PUT", "0.50", "2026-11-04T10:00", 1);
        assertSummary("101.00", "24.70", "76.30");
        return g1;
    }

    // ================================================================== AC 10

    @Test
    @DisplayName("AC10 按账户隔离：默认账户被指派不影响小账户；小账户被指派 1 张成本 4,400.00；跨账户 id → 404")
    void ac10_accountIsolation() {
        scenarioP();
        String small = String.valueOf(newAccount("小账户"));
        accountId = small;
        deposit("5000.00", "2026-11-02T10:00");
        open("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "100.00", "2026-11-04T10:00", 100);
        assertSummary("5100.00", "4500.00", "600.00");

        accountId = "1";
        JsonNode t = assignXyzAll();
        long id = t.get("id").asLong();
        long stockId = t.get("assignment").get("stockTradeId").asLong();

        accountId = small;
        JsonNode xyz = position(XYZ);
        assertThat(xyz.get("direction").asText()).isEqualTo("SHORT");
        assertThat(xyz.get("contracts").asInt()).isEqualTo(1);
        assertThat(buttons(xyz)).isEqualTo("false/false/true/true");
        assertThat(stockPosition("XYZ")).isNull();
        assertSummary("5100.00", "4500.00", "600.00");
        assertErrorShape(deleteTrade(id), 404, "NOT_FOUND", TRADES + "/" + id);
        assertErrorShape(get(STOCKS + "/" + stockId), 404, "NOT_FOUND", STOCKS + "/" + stockId);
        assertErrorShape(deleteStock(stockId), 404, "NOT_FOUND", STOCKS + "/" + stockId);

        JsonNode s = okAssign(xyzAsg(1, "2026-11-20", null));
        assertThat(s.get("assignment").get("stockCost").asText()).isEqualTo("4400.00");
        assertThat(stockPositionRows()).containsExactly("XYZ 100 4400.00 44.0000");

        accountId = "1";
        assertThat(stockPositionRows()).containsExactly("XYZ 300 13130.00 43.7667");
        assertThat(getTrade(id).get("assignment").get("stockCost").asText()).isEqualTo("13130.00");

        accountId = null;
        assertErrorShape(postAssign(xyzAsg(3, "2026-11-20", null)), 400, "ACCOUNT_REQUIRED", ASSIGNMENTS);
    }

    @Test
    @DisplayName("AC10 格式校验：指派费 −1.00 / 1.234 / 过大、日期为空、备注 501 字、张数 0 → VALIDATION_ERROR；日期 / 费用不是格式 → BAD_REQUEST；什么都不保存")
    void ac10_formatValidation() {
        scenarioP();
        assignValidation(xyzAsg(3, "2026-11-20", "-1.00"), "assignmentFee=指派费不能为负数");
        assignValidation(xyzAsg(3, "2026-11-20", "1.234"), "assignmentFee=指派费最多 2 位小数");
        assignValidation(xyzAsg(3, "2026-11-20", "10000000000000.00"), "assignmentFee=指派费过大");
        assignValidation(xyzAsg(3, null, null), "assignmentDate=指派日期不能为空");
        assignValidation(asgBody("XYZ", "2026-11-20", "45", "PUT", 3, "2026-11-20", null, "x".repeat(501)),
                "note=备注最多 500 个字符");
        assignValidation(xyzAsg(0, "2026-11-20", null), "contracts=张数必须是正整数");
        assignValidation(xyzAsg(-1, "2026-11-20", null), "contracts=张数必须是正整数");
        assignValidation(asgBody("XYZ", "2026-11-20", "45", "PUT", null, "2026-11-20", null, null),
                "contracts=张数不能为空");
        assignValidation(xyzAsg(100001, "2026-11-20", null), "contracts=张数过大");
        assignValidation(xyzAsg(0, "2026-11-20", "1.234"), "assignmentFee=指派费最多 2 位小数",
                "contracts=张数必须是正整数");

        ObjectNode badDate = xyzAsg(3, "2026-02-30", null);
        assignBadRequest(badDate.toString(), "assignmentDate");
        ObjectNode badFee = xyzAsg(3, "2026-11-20", "abc");
        assignBadRequest(badFee.toString(), "assignmentFee");

        // edge values that are accepted: 500-character note, fee 0 as empty string, fee with 2 decimals
        JsonNode t = okAssign(asgBody("XYZ", "2026-11-20", "45", "PUT", 3, "2026-11-20", "", "y".repeat(500)));
        assertThat(t.get("assignment").get("assignmentFee").asText()).isEqualTo("0.00");
        assertThat(t.get("note").asText()).hasSize(500);
        assertThat(ledgerRowFor("OPTION_ASSIGN", t.get("id").asLong()).get("remark").asText()).hasSize(500);
    }

    @Test
    @DisplayName("忽略多余字段：带 action / netAmount / multiplier / tradedAtLocal 仍按被指派规则保存；全空白备注保存为 null")
    void extraFieldsIgnoredAndBlankNote() {
        scenarioP();
        ObjectNode body = asgBody("xyz ", "2026-11-20", "45.000", "PUT", 3, "2026-11-20", "0", "   ");
        body.put("action", "BTC");
        body.put("netAmount", "999.00");
        body.put("multiplier", 10);
        body.put("tradedAtLocal", "2026-11-19T10:00");
        JsonNode t = okAssign(body);
        assertThat(t.get("symbol").asText()).isEqualTo("XYZ");
        assertThat(t.get("contractLabel").asText()).isEqualTo(XYZ);
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("tradedAt").asText()).isEqualTo(AT_1620);
        assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(t.get("note").isNull()).isTrue();
        assertThat(t.get("assignment").get("assignmentFee").asText()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("备注与指派费 > 0：交易、流水 remark 带备注；cashChange 含指派费")
    void noteAndFeeShown() {
        scenarioP();
        JsonNode t = okAssign(asgBody("XYZ", "2026-11-20", "45", "PUT", 3, "2026-11-20", "5.00", "券商通知"));
        assertThat(t.get("note").asText()).isEqualTo("券商通知");
        assertThat(t.get("cashChange").asText()).isEqualTo("-13505.00");
        assertThat(t.get("assignment").get("stockCost").asText()).isEqualTo("13135.00");
        JsonNode row = ledgerRowFor("OPTION_ASSIGN", t.get("id").asLong());
        assertThat(row.get("remark").asText()).isEqualTo("券商通知");
        assertThat(row.get("amount").asText()).isEqualTo("-13505.00");
        assertSummary("6932.00", "3000.00", "3932.00");
    }
}
