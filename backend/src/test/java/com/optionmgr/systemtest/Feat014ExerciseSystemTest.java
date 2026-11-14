package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-014 主动行权 (EXR on a long call / put, generated stock record EXERCISE_BUY / EXERCISE_SELL), end-to-end over
 * HTTP against the whole application (in-memory H2, profile "test"), only through the REST API of docs/api/FEAT-014.md
 * (extending FEAT-013 / FEAT-041). The clock is a {@link MutableClock}; unless a test moves it, "now" =
 * 2026-11-23 10:00 New York. Fictional tickers only.
 *
 * <p>Scenario E (requirement): deposit 20,000.00; T1 buy XYZ 200 8,000.00; L1 BTO 2 QRTV 2026-11-20 30 C 300.00;
 * L2 BTO 1 same 160.00; L3 BTO 1 XYZ 2026-11-20 45 P 120.00; S1 STO 1 XYZ 2026-12-18 50 C 90.00 →
 * cash 11,510.00 / 0.00 / 11,510.00, XYZ 200 / 100 / 100.
 *
 * <p>Every rejected write must leave cash, ledger, option trades, option positions, closed trades, stock trades,
 * stock positions and the audit log untouched.
 */
class Feat014ExerciseSystemTest extends OptionTradeSystemTestBase {

    static final Instant MON_1123_1000 = Instant.parse("2026-11-23T15:00:00Z");
    static final Instant TUE_1117_1000 = Instant.parse("2026-11-17T15:00:00Z");
    static final Instant THU_1119_1000 = Instant.parse("2026-11-19T15:00:00Z");
    static final Instant FRI_1120_1630 = Instant.parse("2026-11-20T21:30:00Z");
    static final Instant WED_1125_1000 = Instant.parse("2026-11-25T15:00:00Z");

    static final String EXERCISES = "/api/option-trades/exercises";
    static final String EXPIRATIONS = "/api/option-trades/expirations";
    static final String CLOSES = "/api/option-trades/closes";
    static final String CLOSED = "/api/closed-trades";
    static final String AUDIT = "/api/audit-log";
    static final String STOCKS = "/api/stock-trades";
    static final String STOCK_POS = "/api/stock-positions";

    static final String QC = "QRTV 2026-11-20 30 C";
    static final String XP = "XYZ 2026-11-20 45 P";
    static final String XC = "XYZ 2026-12-18 50 C";
    static final String AT_1620 = "2026-11-20T21:00:00Z";
    static final String AT_1620_LOCAL = "2026-11-20T16:00:00";

    static final String SHORT_MSG = "空头持仓不能行权（被行权请用\"被指派\"）";
    static final String SHARES_0 =
            "持股不足：行权需要 100 股 XYZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）";

    long d1;
    long t1;
    long l1;
    long l2;
    long l3;
    long s1;

    @Override
    Instant now() {
        return MON_1123_1000;
    }

    // ================================================================== request helpers

    ObjectNode exrBody(String symbol, String exp, String strike, String type, Integer contracts, String date,
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
            n.put("exerciseDate", date);
        }
        if (fee != null) {
            n.put("exerciseFee", fee);
        }
        if (note == null) {
            n.putNull("note");
        } else {
            n.put("note", note);
        }
        return n;
    }

    /** QRTV 2026-11-20 30 C. */
    ObjectNode qcExr(Integer contracts, String date, String fee) {
        return exrBody("QRTV", "2026-11-20", "30", "CALL", contracts, date, fee, null);
    }

    /** XYZ 2026-11-20 45 P. */
    ObjectNode xpExr(Integer contracts, String date, String fee) {
        return exrBody("XYZ", "2026-11-20", "45", "PUT", contracts, date, fee, null);
    }

    Resp postExercise(ObjectNode body) {
        return postRaw(EXERCISES, body.toString(), MediaType.APPLICATION_JSON);
    }

    /** POST an exercise that must succeed; checks the invariant parts of an EXR representation. */
    JsonNode okExercise(ObjectNode body) {
        Resp r = postExercise(body);
        assertThat(r.status()).as("exercise %s -> %s", body, r.body()).isEqualTo(201);
        JsonNode t = r.body();
        assertExrShape(t);
        assertFrozenConsistent();
        return t;
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    /** Contract shape of an EXR option-trade representation (FEAT-014 "交易表示的变化"). */
    void assertExrShape(JsonNode t) {
        assertThat(t.get("action").asText()).isEqualTo("EXR");
        assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(t.get("realizedPnl").asText()).as("option side 0.00").isEqualTo("0.00");
        assertThat(t.get("frozenChange").asText()).as("long: no frozen").isEqualTo("0.00");
        assertThat(t.get("cashChange").isTextual()).isTrue();
        assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(t.get("expiredDirection").isNull()).isTrue();
        assertThat(t.get("assignment")).as("assignment present").isNotNull();
        assertThat(t.get("assignment").isNull()).as("EXR: assignment null").isTrue();
        assertThat(t.get("ledgerEntryId").isIntegralNumber()).isTrue();
        assertThat(t.get("tradedAtLocal").asText()).endsWith("T16:00:00");
        Instant.parse(t.get("createdAt").asText());
        assertThat(t.get("coveredLots").isArray()).isTrue();
        assertThat(t.get("coveredLots")).isNotEmpty();
        BigDecimal lotSum = BigDecimal.ZERO;
        for (JsonNode l : t.get("coveredLots")) {
            assertThat(l.get("action").asText()).isEqualTo("BTO");
            assertThat(l.get("frozen").asText()).isEqualTo("0.00");
            assertThat(dec(l.get("netAmount")).signum()).as("long lot net negative").isNegative();
            lotSum = lotSum.add(dec(l.get("netAmount")));
        }
        JsonNode e = t.get("exercise");
        assertThat(e).as("exercise present").isNotNull();
        assertThat(e.isObject()).isTrue();
        for (String f : List.of("exerciseDate", "exerciseFee", "premium")) {
            assertThat(e.get(f).isTextual()).as("exercise.%s is a string", f).isTrue();
        }
        assertThat(e.get("shares").isIntegralNumber()).isTrue();
        assertThat(e.get("stockTradeId").isIntegralNumber()).isTrue();
        assertThat(e.get("exerciseDate").asText()).isEqualTo(t.get("tradedAtLocal").asText().substring(0, 10));
        assertThat(e.get("shares").asInt()).isEqualTo(t.get("contracts").asInt() * t.get("multiplier").asInt());
        assertThat(dec(e.get("premium"))).as("premium = −Σ lot net").isEqualByComparingTo(lotSum.negate());
        BigDecimal fee = dec(e.get("exerciseFee"));
        List<String> callFields = List.of("purchaseAmount", "stockCost", "stockCostPerShare");
        List<String> putFields = List.of("saleAmount", "proceeds", "soldCost", "stockRealizedPnl");
        if ("CALL".equals(t.get("optionType").asText())) {
            for (String f : callFields) {
                assertThat(e.get(f).isTextual()).as("call: exercise.%s is a string", f).isTrue();
            }
            for (String f : putFields) {
                assertThat(e.get(f)).as("exercise.%s present", f).isNotNull();
                assertThat(e.get(f).isNull()).as("call: exercise.%s is null", f).isTrue();
            }
            assertThat(e.get("soldLots").isNull()).isTrue();
            assertThat(dec(e.get("stockCost"))).isEqualByComparingTo(
                    dec(e.get("purchaseAmount")).add(dec(e.get("premium"))).add(fee));
            assertThat(dec(t.get("cashChange"))).isEqualByComparingTo(
                    dec(e.get("purchaseAmount")).add(fee).negate());
        } else {
            for (String f : putFields) {
                assertThat(e.get(f).isTextual()).as("put: exercise.%s is a string", f).isTrue();
            }
            for (String f : callFields) {
                assertThat(e.get(f)).as("exercise.%s present", f).isNotNull();
                assertThat(e.get(f).isNull()).as("put: exercise.%s is null", f).isTrue();
            }
            assertThat(e.get("soldLots").isArray()).isTrue();
            assertThat(dec(e.get("proceeds"))).isEqualByComparingTo(
                    dec(e.get("saleAmount")).subtract(dec(e.get("premium"))).subtract(fee));
            assertThat(dec(e.get("stockRealizedPnl"))).isEqualByComparingTo(
                    dec(e.get("proceeds")).subtract(dec(e.get("soldCost"))));
            assertThat(dec(t.get("cashChange"))).isEqualByComparingTo(dec(e.get("saleAmount")).subtract(fee));
        }
    }

    String rejectedExercise(ObjectNode body, String code) {
        Full before = full();
        return rejected(postExercise(body), before, code, EXERCISES);
    }

    void exerciseValidation(ObjectNode body, String... pairs) {
        Full before = full();
        Resp r = postExercise(body);
        assertErrorShape(r, 400, "VALIDATION_ERROR", EXERCISES);
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

    JsonNode okClose(ObjectNode body) {
        Resp r = postRaw(CLOSES, body.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("close %s -> %s", body, r.body()).isEqualTo(201);
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

    /** "closable/expiryPending/expirable/assignable/exercisable" of a position. */
    String buttons(JsonNode p) {
        return p.get("closable").asBoolean() + "/" + p.get("expiryPending").asBoolean() + "/"
                + p.get("expirable").asBoolean() + "/" + p.get("assignable").asBoolean() + "/"
                + p.get("exercisable").asBoolean();
    }

    List<String> exerciseChoices(JsonNode p) {
        List<String> out = new ArrayList<>();
        p.get("exerciseChoices").forEach(c -> out.add(c.get("contracts").asInt() + ":" + c.get("shares").asInt()));
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

    /** "shares / coveredShares / availableShares" of a stock position (FEAT-039), or null. */
    String coverage(String symbol) {
        for (JsonNode p : stockPositions().get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p.get("shares").asInt() + " / " + p.get("coveredShares").asInt() + " / "
                        + p.get("availableShares").asInt();
            }
        }
        return null;
    }

    /** "type tradeId remainingShares" of each lot of the stock position, FIFO order. */
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

    JsonNode sideEffect(JsonNode rec, String field) {
        for (JsonNode x : rec.get("sideEffects")) {
            if (field.equals(x.get("field").asText())) {
                return x;
            }
        }
        return null;
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

    // ------------------------------------------------------------------ scenarios

    /** Scenario E (in the current account): 11,510.00 / 0.00 / 11,510.00; XYZ 200 / 100 / 100. */
    void scenarioE() {
        d1 = deposit("20000.00", "2026-11-02T10:00").get("id").asLong();
        t1 = okStock(stockBody("BUY", "XYZ", 200, "8000.00", "2026-11-03T10:00"));
        l1 = open("BTO", 2, "QRTV", "2026-11-20", "30", "CALL", "300.00", "2026-11-04T10:00", 100);
        l2 = open("BTO", 1, "QRTV", "2026-11-20", "30", "CALL", "160.00", "2026-11-05T10:00", 100);
        l3 = open("BTO", 1, "XYZ", "2026-11-20", "45", "PUT", "120.00", "2026-11-05T11:00", 100);
        s1 = open("STO", 1, "XYZ", "2026-12-18", "50", "CALL", "90.00", "2026-11-06T10:00", 100);
        assertSummary("11510.00", "0.00", "11510.00");
        assertThat(coverage("XYZ")).isEqualTo("200 / 100 / 100");
        assertThat(ledgerCount()).isEqualTo(6);
    }

    /** AC2: QRTV 3 contracts on 2026-11-20, fee 0.00. */
    JsonNode exerciseCallAll() {
        return okExercise(qcExr(3, "2026-11-20", "0.00"));
    }

    /** AC3: XYZ 45 P 1 contract on 2026-11-20, fee 0.00. */
    JsonNode exercisePut() {
        return okExercise(xpExr(1, "2026-11-20", "0.00"));
    }

    /**
     * AC6 / AC10 "小账户": deposit 1,000.00; BTO 1 XYZ 45 P 120.00 (11-03); BTO 1 QRTV 30 C 50.00 (11-04). No stock.
     * Switches to it; returns its id.
     */
    String smallAccount() {
        String small = String.valueOf(newAccount("小账户"));
        accountId = small;
        deposit("1000.00", "2026-11-02T10:00");
        open("BTO", 1, "XYZ", "2026-11-20", "45", "PUT", "120.00", "2026-11-03T10:00", 100);
        open("BTO", 1, "QRTV", "2026-11-20", "30", "CALL", "50.00", "2026-11-04T10:00", 100);
        assertSummary("830.00", "0.00", "830.00");
        return small;
    }

    // ================================================================== AC 1

    @Test
    @DisplayName("AC1 按钮（Q81）：11-17 多头 平仓+行权、S1 无行权；11-20 16:30 只有行权；11-23 作废+行权；选项 2/3 张、日期 11-20、可用现金 11,510.00；put 行持股 200 可用 100")
    void ac1_buttonsAndDialogData() {
        scenarioE();

        clock.set(TUE_1117_1000);
        JsonNode qc = position(QC);
        assertThat(qc.get("direction").asText()).isEqualTo("LONG");
        assertThat(buttons(qc)).isEqualTo("true/false/false/false/true");
        assertThat(qc.get("exerciseDateMax").asText()).isEqualTo("2026-11-16");
        assertThat(qc.get("exerciseDateDefault").asText()).isEqualTo("2026-11-16");
        assertThat(buttons(position(XP))).isEqualTo("true/false/false/false/true");
        JsonNode xc = position(XC);
        assertThat(xc.get("direction").asText()).isEqualTo("SHORT");
        assertThat(xc.get("exercisable").asBoolean()).as("short: no 行权").isFalse();
        assertThat(xc.get("exerciseChoices")).isEmpty();
        assertThat(xc.get("exerciseDateMax").isNull()).isTrue();
        assertThat(xc.get("exerciseDateDefault").isNull()).isTrue();
        assertThat(xc.get("availableCash").isNull()).isTrue();

        clock.set(FRI_1120_1630);
        qc = position(QC);
        assertThat(buttons(qc)).as("16:30: only 行权").isEqualTo("false/true/false/false/true");
        assertThat(qc.get("exerciseDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(buttons(position(XP))).isEqualTo("false/true/false/false/true");

        clock.set(MON_1123_1000);
        qc = position(QC);
        assertThat(buttons(qc)).isEqualTo("false/false/true/false/true");
        assertThat(exerciseChoices(qc)).containsExactly("2:200", "3:300");
        assertThat(qc.get("exerciseDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(qc.get("exerciseDateDefault").asText()).isEqualTo("2026-11-20");
        assertThat(qc.get("availableCash").isTextual()).as("availableCash is a JSON string").isTrue();
        assertThat(qc.get("availableCash").asText()).isEqualTo("11510.00");
        assertThat(qc.get("underlyingShares").isNull()).as("long call: underlyingShares null").isTrue();
        assertThat(qc.get("availableShares").isNull()).isTrue();

        JsonNode xp = position(XP);
        assertThat(buttons(xp)).isEqualTo("false/false/true/false/true");
        assertThat(exerciseChoices(xp)).containsExactly("1:100");
        assertThat(xp.get("exerciseDateDefault").asText()).isEqualTo("2026-11-20");
        assertThat(xp.get("availableCash").isNull()).as("put: availableCash null").isTrue();
        assertThat(xp.get("underlyingShares").isIntegralNumber()).isTrue();
        assertThat(xp.get("underlyingShares").asInt()).isEqualTo(200);
        assertThat(xp.get("availableShares").isIntegralNumber()).isTrue();
        assertThat(xp.get("availableShares").asInt()).isEqualTo(100);
        assertThat(xp.get("coveredShares") == null || xp.get("coveredShares").isNull())
                .as("long put: other coverage fields null").isTrue();

        xc = position(XC);
        assertThat(xc.get("exercisable").asBoolean()).isFalse();
        assertThat(xc.get("underlyingShares").asInt()).as("short call unchanged").isEqualTo(200);
        assertThat(xc.get("availableShares").asInt()).isEqualTo(100);

        Full before = full();
        Resp r = postExercise(exrBody("XYZ", "2026-12-18", "50", "CALL", 1, "2026-11-20", null, null));
        assertThat(rejected(r, before, "EXERCISE_SHORT_POSITION", EXERCISES)).isEqualTo(SHORT_MSG);
        assertThat(r.body().get("message").asText()).contains("\"被指派\"");
    }

    // ================================================================== AC 2

    @Test
    @DisplayName("AC2 call 全部行权：响应各项（契约示例）；现金 2,510.00；QRTV 期权无持仓、股票 300 股 9,460.00（行权买入）；已平仓 1 行 0.00 共 1 笔；流水；无审计")
    void ac2_callExerciseAll() {
        scenarioE();
        JsonNode t = exerciseCallAll();
        long id = t.get("id").asLong();
        assertThat(id).isGreaterThan(s1);
        assertThat(List.of(t.get("symbol").asText(), t.get("expirationDate").asText(), t.get("strike").asText(),
                t.get("optionType").asText(), t.get("contractLabel").asText(), t.get("contracts").asInt(),
                t.get("multiplier").asInt(), t.get("tradedAt").asText(), t.get("tradedAtLocal").asText(),
                t.get("cashChange").asText(), t.get("frozenChange").asText(), t.get("createdAt").asText()))
                .containsExactly("QRTV", "2026-11-20", "30", "CALL", QC, 3, 100, AT_1620, AT_1620_LOCAL, "-9000.00",
                        "0.00", "2026-11-23T15:00:00Z");
        assertThat(t.get("note").isNull()).isTrue();
        JsonNode lots = t.get("coveredLots");
        assertThat(lots).hasSize(2);
        assertThat(List.of(lots.get(0).get("tradeId").asLong(), lots.get(0).get("action").asText(),
                lots.get(0).get("tradedAt").asText(), lots.get(0).get("tradedAtLocal").asText(),
                lots.get(0).get("contracts").asInt(), lots.get(0).get("netAmount").asText(),
                lots.get(0).get("frozen").asText()))
                .containsExactly(l1, "BTO", "2026-11-04T15:00:00Z", "2026-11-04T10:00:00", 2, "-300.00", "0.00");
        assertThat(List.of(lots.get(1).get("tradeId").asLong(), lots.get(1).get("contracts").asInt(),
                lots.get(1).get("netAmount").asText())).containsExactly(l2, 1, "-160.00");
        JsonNode e = t.get("exercise");
        assertThat(List.of(e.get("exerciseDate").asText(), e.get("exerciseFee").asText(), e.get("premium").asText(),
                e.get("shares").asInt(), e.get("purchaseAmount").asText(), e.get("stockCost").asText(),
                e.get("stockCostPerShare").asText()))
                .containsExactly("2026-11-20", "0.00", "460.00", 300, "9000.00", "9460.00", "31.5333");
        long stockId = e.get("stockTradeId").asLong();
        assertThat(stockId).isGreaterThan(t1);

        // cash, positions
        assertSummary("2510.00", "0.00", "2510.00");
        assertThat(positionLabels()).doesNotContain(QC).contains(XP, XC);
        assertThat(stockPositionRows()).containsExactlyInAnyOrder("QRTV 300 9460.00 31.5333",
                "XYZ 200 8000.00 40.0000");
        assertThat(coverage("QRTV")).isEqualTo("300 / 0 / 300");
        assertThat(stockLots("QRTV")).containsExactly("EXERCISE_BUY " + stockId + " 300");

        // ledger
        JsonNode led = ledger(1, 100);
        assertThat(led.get("totalItems").asInt()).isEqualTo(7);
        JsonNode row = led.get("items").get(0);
        assertThat(row.get("id").asLong()).isEqualTo(t.get("ledgerEntryId").asLong());
        assertThat(List.of(row.get("occurredAt").asText(), row.get("sourceType").asText(),
                row.get("sourceId").asLong(), row.get("amount").asText(), row.get("frozenChange").asText(),
                row.get("balanceAfter").asText(), row.get("description").asText()))
                .containsExactly(AT_1620, "OPTION_EXERCISE", id, "-9000.00", "0.00", "2510.00",
                        "行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV");
        assertThat(row.get("remark").isNull()).isTrue();
        assertThat(row.get("hasAuditLog").asBoolean()).isFalse();

        // trade list
        assertThat(tradeList("").get("items").get(0)).isEqualTo(t);
        assertThat(getTrade(id)).isEqualTo(t);
        assertThat(ids(tradeList("action=EXR"))).containsExactly(id);
        assertThat(tradeList("").get("items").get(1).get("exercise").isNull()).as("non-EXR: exercise null").isTrue();

        // generated EXERCISE_BUY stock record
        JsonNode s = getStock(stockId);
        assertThat(List.of(s.get("type").asText(), s.get("symbol").asText(), s.get("shares").asInt(),
                s.get("amount").asText(), s.get("cashChange").asText(), s.get("pricePerShare").asText(),
                s.get("tradedAt").asText(), s.get("tradedAtLocal").asText(), s.get("exerciseTradeId").asLong()))
                .containsExactly("EXERCISE_BUY", "QRTV", 300, "9460.00", "0.00", "31.5333", AT_1620, AT_1620_LOCAL,
                        id);
        assertThat(s.get("editable").asBoolean()).isFalse();
        assertThat(s.get("deletable").asBoolean()).isFalse();
        assertThat(s.get("hasAuditLog").asBoolean()).isFalse();
        for (String f : List.of("tradeDate", "note", "ledgerEntryId", "soldCost", "realizedPnl", "soldLots",
                "assignmentTradeId")) {
            assertThat(s.get(f)).as("EXERCISE_BUY %s present", f).isNotNull();
            assertThat(s.get(f).isNull()).as("EXERCISE_BUY %s is null", f).isTrue();
        }
        assertThat(getStock(t1).get("exerciseTradeId").isNull()).as("other types: exerciseTradeId null").isTrue();
        assertThat(ids(stockList("type=EXERCISE_BUY"))).containsExactly(stockId);
        assertThat(ids(stockList("type=EXERCISE_BUY&type=EXERCISE_SELL"))).containsExactly(stockId);
        assertThat(stockList("type=EXERCISE_SELL").get("items")).isEmpty();

        // closed trades: 1 row (the EXERCISE_BUY does not appear, Q71)
        JsonNode c = closed("");
        assertThat(c.get("totalItems").asInt()).isEqualTo(1);
        assertThat(c.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        JsonNode or = c.get("items").get(0);
        assertThat(List.of(or.get("type").asText(), or.get("id").asLong(), or.get("action").asText(),
                or.get("closedDirection").asText(), or.get("tradedAt").asText(), or.get("contracts").asInt(),
                or.get("openingNetTotal").asText(), or.get("openingNetPerContract").asText(),
                or.get("closeNetAmount").asText(), or.get("closeNetPerContract").asText(),
                or.get("realizedPnl").asText()))
                .containsExactly("OPTION", id, "EXR", "LONG", AT_1620, 3, "-460.00", "-153.33", "0.00", "0.00",
                        "0.00");
        assertThat(or.get("coveredLots")).hasSize(2);
        assertThat(or.get("assignment").isNull()).isTrue();
        assertThat(or.get("exerciseTradeId").isNull()).isTrue();
        assertThat(or.get("hasAuditLog").asBoolean()).isFalse();
        JsonNode oe = or.get("exercise");
        assertThat(List.of(oe.get("shares").asInt(), oe.get("stockCost").asText(), oe.get("stockTradeId").asLong()))
                .containsExactly(300, "9460.00", stockId);
        assertThat(oe.get("proceeds").isNull()).isTrue();
        assertThat(ids(closed("action=EXR"))).containsExactly(id);
        assertThat(closed("type=STOCK").get("items")).isEmpty();

        // pnl summary and dashboard: call exercise = 1 笔 0.00
        JsonNode p = pnl();
        assertThat(p.get("closedCount").asInt()).isEqualTo(1);
        assertThat(p.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        JsonNode d = dashboard();
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("0.00");

        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    // ================================================================== AC 3

    @Test
    @DisplayName("AC3 put 行权：行权金额 4,500.00、实收 4,380.00、交出 T1 100 股 4,000.00、+380.00、现金 +4,500.00；16,010.00；XYZ 100/100/0；已平仓 2 行 2 笔；首页 +380.00（2 笔）")
    void ac3_putExercise() {
        scenarioE();
        JsonNode t = exercisePut();
        long id = t.get("id").asLong();
        assertThat(List.of(t.get("contractLabel").asText(), t.get("optionType").asText(), t.get("contracts").asInt(),
                t.get("tradedAt").asText(), t.get("cashChange").asText()))
                .containsExactly(XP, "PUT", 1, AT_1620, "4500.00");
        assertThat(lotIds(t)).containsExactly(l3);
        assertThat(t.get("coveredLots").get(0).get("netAmount").asText()).isEqualTo("-120.00");
        JsonNode e = t.get("exercise");
        assertThat(List.of(e.get("exerciseFee").asText(), e.get("premium").asText(), e.get("shares").asInt(),
                e.get("saleAmount").asText(), e.get("proceeds").asText(), e.get("soldCost").asText(),
                e.get("stockRealizedPnl").asText()))
                .containsExactly("0.00", "120.00", 100, "4500.00", "4380.00", "4000.00", "380.00");
        assertThat(soldLots(e.get("soldLots"))).containsExactly("BUY " + t1 + " 100 4000.00");
        JsonNode sl0 = e.get("soldLots").get(0);
        assertThat(List.of(sl0.get("tradedAt").asText(), sl0.get("tradedAtLocal").asText()))
                .containsExactly("2026-11-03T15:00:00Z", "2026-11-03T10:00:00");
        assertThat(sl0.get("tradeDate").isNull()).isTrue();
        long stockId = e.get("stockTradeId").asLong();

        assertSummary("16010.00", "0.00", "16010.00");
        assertThat(coverage("XYZ")).isEqualTo("100 / 100 / 0");
        assertThat(positionLabels()).doesNotContain(XP).contains(QC, XC);
        assertThat(ledgerRowFor("OPTION_EXERCISE", id).get("description").asText())
                .isEqualTo("行权 1 张 XYZ 2026-11-20 45 P，卖出 100 股 XYZ");

        // generated EXERCISE_SELL stock record
        JsonNode s = getStock(stockId);
        assertThat(List.of(s.get("type").asText(), s.get("symbol").asText(), s.get("shares").asInt(),
                s.get("amount").asText(), s.get("cashChange").asText(), s.get("pricePerShare").asText(),
                s.get("tradedAt").asText(), s.get("soldCost").asText(), s.get("realizedPnl").asText(),
                s.get("exerciseTradeId").asLong()))
                .containsExactly("EXERCISE_SELL", "XYZ", 100, "4380.00", "0.00", "43.8000", AT_1620, "4000.00",
                        "380.00", id);
        assertThat(s.get("soldLots")).isEqualTo(e.get("soldLots"));
        assertThat(s.get("editable").asBoolean()).isFalse();
        assertThat(s.get("deletable").asBoolean()).isFalse();
        assertThat(s.get("assignmentTradeId").isNull()).isTrue();
        assertThat(ids(stockList("type=EXERCISE_SELL"))).containsExactly(stockId);

        // closed trades: stock row above option row (Q114)
        JsonNode c = closed("");
        assertThat(c.get("totalItems").asInt()).isEqualTo(2);
        assertThat(c.get("filteredCount").asInt()).isEqualTo(2);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("380.00");
        JsonNode sr = c.get("items").get(0);
        assertThat(List.of(sr.get("type").asText(), sr.get("id").asLong(), sr.get("action").asText(),
                sr.get("tradedAt").asText(), sr.get("symbol").asText(), sr.get("shares").asInt(),
                sr.get("openingNetTotal").asText(), sr.get("costPerShare").asText(),
                sr.get("closeNetAmount").asText(), sr.get("pricePerShare").asText(), sr.get("realizedPnl").asText(),
                sr.get("exerciseTradeId").asLong()))
                .containsExactly("STOCK", stockId, "EXERCISE_SELL", AT_1620, "XYZ", 100, "-4000.00", "40.0000",
                        "4380.00", "43.8000", "380.00", id);
        assertThat(sr.get("exercise").isNull()).isTrue();
        assertThat(sr.get("soldLots")).hasSize(1);
        JsonNode or = c.get("items").get(1);
        assertThat(List.of(or.get("type").asText(), or.get("id").asLong(), or.get("action").asText(),
                or.get("closedDirection").asText(), or.get("openingNetTotal").asText(),
                or.get("realizedPnl").asText()))
                .containsExactly("OPTION", id, "EXR", "LONG", "-120.00", "0.00");
        JsonNode oe = or.get("exercise");
        assertThat(List.of(oe.get("shares").asInt(), oe.get("proceeds").asText(), oe.get("stockTradeId").asLong()))
                .containsExactly(100, "4380.00", stockId);
        assertThat(oe.get("stockCost").isNull()).isTrue();
        assertThat(ids(closed("type=STOCK"))).containsExactly(stockId);
        assertThat(ids(closed("action=EXR"))).as("action is option-only (Q90)").containsExactly(id);

        // pnl summary / dashboard: 2 笔
        JsonNode p = pnl();
        assertThat(p.get("closedCount").asInt()).isEqualTo(2);
        assertThat(p.get("realizedPnlTotal").asText()).isEqualTo("380.00");
        assertThat(p.get("bySymbol")).hasSize(1);
        assertThat(sumRow(p.get("bySymbol").get(0))).isEqualTo("XYZ 1 0.00 | 1 380.00 | 2 380.00");
        JsonNode d = dashboard();
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(2);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("380.00");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("AC2 + AC3 一起：现金 7,010.00；已平仓 3 行、+380.00；汇总 3 笔")
    void ac2and3_together() {
        scenarioE();
        exerciseCallAll();
        exercisePut();
        assertSummary("7010.00", "0.00", "7010.00");
        assertThat(closed("").get("totalItems").asInt()).isEqualTo(3);
        assertThat(closed("").get("filteredRealizedPnlTotal").asText()).isEqualTo("380.00");
        assertThat(pnl().get("closedCount").asInt()).isEqualTo(3);
        assertThat(ids(tradeList("action=EXR"))).hasSize(2);
    }

    // ================================================================== AC 4

    @Test
    @DisplayName("AC4 提前行权（11-19）：1 张非整笔、日期今天未到 16:00、晚于到期日、晚于今天 → 拒绝；2 张 11-18 费 5.00 → 成本 6,305.00、每股 31.5250、现金 −6,005.00；剩 1 张；11-23 作废 −160.00")
    void ac4_earlyPartialFeeThenExpire() {
        scenarioE();
        clock.set(THU_1119_1000);
        JsonNode qc = position(QC);
        assertThat(qc.get("exerciseDateDefault").asText()).isEqualTo("2026-11-18");
        assertThat(qc.get("exerciseDateMax").asText()).isEqualTo("2026-11-18");

        assertThat(rejectedExercise(qcExr(1, "2026-11-18", null), "CLOSE_NOT_WHOLE_LOTS"))
                .isEqualTo("必须一次行权整笔开仓：最早一笔是 2 张，可为 2 张或 3 张");
        assertThat(rejectedExercise(qcExr(2, "2026-11-19", null), "EXERCISE_DATE_INVALID"))
                .isEqualTo("行权日期为今天时，美东 16:00 之后才能记录");
        assertThat(rejectedExercise(qcExr(2, "2026-11-21", null), "EXERCISE_DATE_INVALID"))
                .isEqualTo("行权日期不能晚于到期日（2026-11-20）");
        assertThat(rejectedExercise(qcExr(2, "2026-11-20", null), "EXERCISE_DATE_INVALID"))
                .isEqualTo("行权日期不能晚于今天");
        assertThat(rejectedExercise(qcExr(4, "2026-11-18", null), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("行权 4 张超过当时持仓 3 张");

        JsonNode t = okExercise(qcExr(2, "2026-11-18", "5.00"));
        assertThat(List.of(t.get("tradedAt").asText(), t.get("tradedAtLocal").asText(), t.get("cashChange").asText()))
                .containsExactly("2026-11-18T21:00:00Z", "2026-11-18T16:00:00", "-6005.00");
        assertThat(lotIds(t)).containsExactly(l1);
        JsonNode e = t.get("exercise");
        assertThat(List.of(e.get("exerciseDate").asText(), e.get("exerciseFee").asText(), e.get("premium").asText(),
                e.get("shares").asInt(), e.get("purchaseAmount").asText(), e.get("stockCost").asText(),
                e.get("stockCostPerShare").asText()))
                .containsExactly("2026-11-18", "5.00", "300.00", 200, "6000.00", "6305.00", "31.5250");
        assertSummary("5505.00", "0.00", "5505.00");
        assertThat(position(QC).get("contracts").asInt()).isEqualTo(1);
        assertThat(exerciseChoices(position(QC))).containsExactly("1:100");
        assertThat(ledgerRowFor("OPTION_EXERCISE", t.get("id").asLong()).get("amount").asText()).isEqualTo("-6005.00");

        clock.set(MON_1123_1000);
        JsonNode exp = okExpire("QRTV", "2026-11-20", "30", "CALL", 1);
        assertThat(exp.get("realizedPnl").asText()).isEqualTo("-160.00");
        assertThat(lotIds(exp)).containsExactly(l2);
        assertThat(position(QC)).isNull();
    }

    @Test
    @DisplayName("AC4 先作废再行权（Q65）：作废 QRTV 3 张后行权 3 张 → NO_POSITION_TO_EXERCISE，含「如果实际是行权，请先删除该到期作废」")
    void ac4_expireThenExercise() {
        scenarioE();
        okExpire("QRTV", "2026-11-20", "30", "CALL", 3);
        String msg = rejectedExercise(qcExr(3, "2026-11-20", null), "NO_POSITION_TO_EXERCISE");
        assertThat(msg).isEqualTo("2026-11-20 16:00 时该合约（QRTV 2026-11-20 30 C）没有持仓可行权"
                + "（已于 2026-11-20 16:00 到期作废；如果实际是行权，请先删除该到期作废）");
        assertThat(msg).contains("如果实际是行权，请先删除该到期作废");
    }

    @Test
    @DisplayName("之后的同合约记录（第 6 步）：作废 3 张后补记更早的行权 2 张 → 「补录后…到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废」")
    void laterExpirationBlocksEarlierExercise() {
        scenarioE();
        okExpire("QRTV", "2026-11-20", "30", "CALL", 3);
        assertThat(rejectedExercise(qcExr(2, "2026-11-18", null), "CLOSE_EXCEEDS_POSITION")).isEqualTo(
                "补录后该合约（QRTV 2026-11-20 30 C）在 2026-11-20 16:00 的到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废");
    }

    @Test
    @DisplayName("没有持仓：行权日期早于开仓（11-03）→ NO_POSITION_TO_EXERCISE")
    void noPositionAtExerciseTime() {
        scenarioE();
        assertThat(rejectedExercise(qcExr(2, "2026-11-03", null), "NO_POSITION_TO_EXERCISE"))
                .isEqualTo("2026-11-03 16:00 时该合约（QRTV 2026-11-20 30 C）没有持仓可行权");
    }

    // ================================================================== AC 5

    @Test
    @DisplayName("AC5 现金不足（Q68）：加出金 3,000.00；行权 3 张 → 「可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00」；2 张成功 → 2,510.00")
    void ac5_cashInsufficient() {
        scenarioE();
        withdraw("3000.00", "2026-11-10T10:00");
        assertSummary("8510.00", "0.00", "8510.00");
        assertThat(rejectedExercise(qcExr(3, "2026-11-20", null), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00");
        JsonNode t = okExercise(qcExr(2, "2026-11-20", null));
        assertThat(t.get("cashChange").asText()).isEqualTo("-6000.00");
        assertThat(t.get("exercise").get("exerciseFee").asText()).as("fee omitted = 0.00").isEqualTo("0.00");
        assertSummary("2510.00", "0.00", "2510.00");
    }

    // ================================================================== AC 6

    @Test
    @DisplayName("AC6 持股不足（Q15）：小账户 0 股行权 put → 「持股不足：行权需要 100 股 XYZ…持有 0 股…」；按钮仍显示、持股 0 可用 0")
    void ac6_sharesInsufficient() {
        smallAccount();
        JsonNode xp = position(XP);
        assertThat(xp.get("exercisable").asBoolean()).isTrue();
        assertThat(xp.get("underlyingShares").asInt()).isZero();
        assertThat(xp.get("availableShares").asInt()).isZero();
        assertThat(position(QC).get("availableCash").asText()).isEqualTo("830.00");
        assertThat(rejectedExercise(xpExr(1, "2026-11-20", null), "SHARES_INSUFFICIENT")).isEqualTo(SHARES_0);
    }

    @Test
    @DisplayName("AC6 被覆盖（Q113）：加 STO 1 张 XYZ 50 C → 200/200/0，行权 put → COVERAGE_INSUFFICIENT；BTC 1 张后成功，实收 4,380.00、+380.00")
    void ac6_coveredSharesCannotBeSold() {
        scenarioE();
        open("STO", 1, "XYZ", "2026-12-18", "50", "CALL", "80.00", "2026-11-07T10:00", 100);
        assertThat(coverage("XYZ")).isEqualTo("200 / 200 / 0");
        assertThat(position(XP).get("availableShares").asInt()).isZero();
        assertThat(rejectedExercise(xpExr(1, "2026-11-20", null), "COVERAGE_INSUFFICIENT")).isEqualTo(
                "行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call");

        okClose(closeBody("BTC", 1, "XYZ", "2026-12-18", "50", "CALL", "30.00", "2026-11-10T10:00"));
        assertThat(coverage("XYZ")).isEqualTo("200 / 100 / 100");
        JsonNode t = okExercise(xpExr(1, "2026-11-20", null));
        assertThat(List.of(t.get("exercise").get("proceeds").asText(),
                t.get("exercise").get("stockRealizedPnl").asText())).containsExactly("4380.00", "380.00");
        assertThat(coverage("XYZ")).isEqualTo("100 / 100 / 0");
    }

    @Test
    @DisplayName("put 卖出实收 ≤ 0 → EXERCISE_PROCEEDS_NOT_POSITIVE「行权后股票卖出实收将为 −5.00 / 0.00，…」；4,379.99 → 实收 0.01")
    void putProceedsNotPositive() {
        scenarioE();
        assertThat(rejectedExercise(xpExr(1, "2026-11-20", "4385.00"), "EXERCISE_PROCEEDS_NOT_POSITIVE"))
                .isEqualTo("行权后股票卖出实收将为 −5.00，请检查行权费和被行权批次的开仓净额");
        assertThat(rejectedExercise(xpExr(1, "2026-11-20", "4380.00"), "EXERCISE_PROCEEDS_NOT_POSITIVE"))
                .isEqualTo("行权后股票卖出实收将为 0.00，请检查行权费和被行权批次的开仓净额");
        JsonNode t = okExercise(xpExr(1, "2026-11-20", "4379.99"));
        assertThat(t.get("exercise").get("proceeds").asText()).isEqualTo("0.01");
        assertThat(t.get("exercise").get("stockRealizedPnl").asText()).as("may be negative").isEqualTo("-3999.99");
        assertThat(t.get("cashChange").asText()).isEqualTo("120.01");
    }

    @Test
    @DisplayName("边界：call 行权得到的股票可用于之后的 covered call；同一时间 16:00 在行权之后录入的卖出可以用到行权买入批次")
    void exerciseBuyUsableLater() {
        scenarioE();
        long stockId = exerciseCallAll().get("exercise").get("stockTradeId").asLong();
        clock.set(WED_1125_1000);
        open("STO", 1, "QRTV", "2026-12-18", "35", "CALL", "40.00", "2026-11-24T10:00", 100);
        assertThat(coverage("QRTV")).isEqualTo("300 / 100 / 200");
        long sell = okStock(stockBody("SELL", "QRTV", 100, "3300.00", "2026-11-20T16:00"));
        JsonNode s = getStock(sell);
        assertThat(soldLots(s.get("soldLots"))).containsExactly("EXERCISE_BUY " + stockId + " 100 3153.33");
    }

    // ================================================================== AC 7

    @Test
    @DisplayName("AC7 删除行权：现金 11,510.00、QRTV 恢复 3 张、股票/流水/已平仓消失；一条审计（摘要、before 含行权字段）；可重新记录、无历史")
    void ac7_deleteAndReExercise() {
        scenarioE();
        JsonNode t = exerciseCallAll();
        long id = t.get("id").asLong();
        long stockId = t.get("exercise").get("stockTradeId").asLong();
        long ledgerId = t.get("ledgerEntryId").asLong();

        okDelete(id);
        assertSummary("11510.00", "0.00", "11510.00");
        JsonNode qc = position(QC);
        assertThat(qc.get("contracts").asInt()).isEqualTo(3);
        assertThat(qc.get("lotCount").asInt()).isEqualTo(2);
        assertThat(stockPositionRows()).containsExactly("XYZ 200 8000.00 40.0000");
        assertErrorShape(get(STOCKS + "/" + stockId), 404, "NOT_FOUND", STOCKS + "/" + stockId);
        assertErrorShape(get(TRADES + "/" + id), 404, "NOT_FOUND", TRADES + "/" + id);
        assertThat(ids(stockList(""))).containsExactly(t1);
        assertThat(closed("").get("filteredCount").asInt()).isZero();
        assertThat(ledgerCount()).isEqualTo(6);
        ledger(1, 100).get("items").forEach(r -> assertThat(r.get("id").asLong()).isNotEqualTo(ledgerId));

        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).as("one audit record, no STOCK_TRADE record").isEqualTo(1);
        JsonNode rec = a.get("items").get(0);
        assertThat(List.of(rec.get("operation").asText(), rec.get("objectType").asText(),
                rec.get("objectId").asLong(), rec.get("summary").asText()))
                .containsExactly("DELETE", "OPTION_TRADE", id, "行权 QRTV 2026-11-20 30 C 3 张（买入 300 股 QRTV）");
        assertThat(rec.get("after").isNull()).isTrue();
        assertThat(rec.get("changes")).isEmpty();
        assertThat(rec.get("sideEffects")).isEmpty();
        JsonNode b = rec.get("before");
        assertThat(List.of(b.get("id").asLong(), b.get("optionType").asText(), b.get("contractLabel").asText(),
                b.get("action").asText(), b.get("contracts").asInt(), b.get("netAmount").asText(),
                b.get("tradedAt").asText(), b.get("cashChange").asText(), b.get("frozenChange").asText()))
                .containsExactly(id, "CALL", QC, "EXR", 3, "0.00", AT_1620, "-9000.00", "0.00");
        assertThat(List.of(b.get("exerciseDate").asText(), b.get("exerciseFee").asText(), b.get("shares").asInt(),
                b.get("premium").asText(), b.get("purchaseAmount").asText(), b.get("stockCost").asText(),
                b.get("stockTradeId").asLong()))
                .containsExactly("2026-11-20", "0.00", 300, "460.00", "9000.00", "9460.00", stockId);
        for (String f : List.of("saleAmount", "proceeds", "soldCost", "stockRealizedPnl")) {
            assertThat(b.get(f) == null || b.get(f).isNull()).as("call before has no %s", f).isTrue();
        }

        JsonNode again = exerciseCallAll();
        assertThat(again.get("id").asLong()).isGreaterThan(id);
        assertThat(again.get("exercise").get("stockTradeId").asLong()).isGreaterThan(stockId);
        assertThat(again.get("exercise").get("stockCost").asText()).isEqualTo("9460.00");
        assertThat(again.get("hasAuditLog").asBoolean()).isFalse();
        assertSummary("2510.00", "0.00", "2510.00");
    }

    @Test
    @DisplayName("AC7 删除 put 行权：审计摘要「行权 XYZ 2026-11-20 45 P 1 张（卖出 100 股 XYZ）」；XYZ 恢复 200 股")
    void ac7_deletePutExercise() {
        scenarioE();
        JsonNode t = exercisePut();
        long id = t.get("id").asLong();
        okDelete(id);
        assertSummary("11510.00", "0.00", "11510.00");
        assertThat(coverage("XYZ")).isEqualTo("200 / 100 / 100");
        assertThat(position(XP).get("contracts").asInt()).isEqualTo(1);
        JsonNode rec = audit().get("items").get(0);
        assertThat(rec.get("summary").asText()).isEqualTo("行权 XYZ 2026-11-20 45 P 1 张（卖出 100 股 XYZ）");
        JsonNode b = rec.get("before");
        assertThat(List.of(b.get("saleAmount").asText(), b.get("proceeds").asText(), b.get("soldCost").asText(),
                b.get("stockRealizedPnl").asText())).containsExactly("4500.00", "4380.00", "4000.00", "380.00");
    }

    @Test
    @DisplayName("AC7 删除被拒：行权后 11-24 卖出 QRTV 100 股 → 删除行权「删除后 2026-11-24 10:00 的卖出 QRTV 100 股将超过当时持股 0 股」")
    void ac7_deleteBlockedByLaterSale() {
        scenarioE();
        long id = exerciseCallAll().get("id").asLong();
        clock.set(WED_1125_1000);
        okStock(stockBody("SELL", "QRTV", 100, "3300.00", "2026-11-24T10:00"));
        assertThat(rejectedDelete(id, "SHARES_INSUFFICIENT"))
                .isEqualTo("删除后 2026-11-24 10:00 的卖出 QRTV 100 股将超过当时持股 0 股");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("AC7 不可修改：修改行权 → EXERCISE_NOT_EDITABLE；改 / 删 EXERCISE_BUY / EXERCISE_SELL → STOCK_TRADE_FROM_EXERCISE；直接录入两种类型 → 400")
    void ac7_notEditable() {
        scenarioE();
        JsonNode call = exerciseCallAll();
        JsonNode put = exercisePut();
        long id = call.get("id").asLong();

        assertThat(rejectedEdit(id, edit(3, "1.00", "2026-11-20T16:00", null, "x"), "EXERCISE_NOT_EDITABLE"))
                .isEqualTo("行权不能修改，记错请删除后重新记录");

        String msg = "该股票记录由行权生成，请在交易列表删除对应的行权记录";
        for (JsonNode t : List.of(call, put)) {
            long stockId = t.get("exercise").get("stockTradeId").asLong();
            Full before = full();
            assertThat(rejected(putStock(stockId, stockEdit(100, "4000.00", "2026-11-20T16:00")), before,
                    "STOCK_TRADE_FROM_EXERCISE", STOCKS + "/" + stockId)).isEqualTo(msg);
            assertThat(rejectedStockDelete(stockId, "STOCK_TRADE_FROM_EXERCISE")).isEqualTo(msg);
        }

        Full before = full();
        Resp buy = postStock(stockBody("EXERCISE_BUY", "QRTV", 100, "3000.00", "2026-11-20T16:00"));
        assertErrorShape(buy, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(buy)).contains("type=行权买入由行权生成，不能直接录入");
        Resp sell = postStock(stockBody("EXERCISE_SELL", "XYZ", 100, "3000.00", "2026-11-20T16:00"));
        assertErrorShape(sell, 400, "VALIDATION_ERROR", STOCKS);
        assertThat(details(sell)).contains("type=行权卖出由行权生成，不能直接录入");
        assertThat(full()).isEqualTo(before);
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    // ================================================================== AC 8

    @Test
    @DisplayName("AC8 重放：L2 净额 → 170.00 → 股票成本 9,470.00、审计连带「行权 #id 股票成本 9,460.00 → 9,470.00」；L3 → 130.00 → 实收 4,370.00、+370.00；现金 6,990.00")
    void ac8_replayEdits() {
        scenarioE();
        JsonNode call = exerciseCallAll();
        JsonNode put = exercisePut();
        long callId = call.get("id").asLong();
        long putId = put.get("id").asLong();
        long buyId = call.get("exercise").get("stockTradeId").asLong();
        long sellId = put.get("exercise").get("stockTradeId").asLong();

        okEdit(l2, edit(1, "170.00", "2026-11-05T10:00", 100, null));
        JsonNode c = getTrade(callId);
        assertThat(List.of(c.get("exercise").get("premium").asText(), c.get("exercise").get("stockCost").asText(),
                c.get("exercise").get("purchaseAmount").asText(), c.get("cashChange").asText()))
                .containsExactly("470.00", "9470.00", "9000.00", "-9000.00");
        assertThat(c.get("hasAuditLog").asBoolean()).isFalse();
        JsonNode buy = getStock(buyId);
        assertThat(buy.get("amount").asText()).isEqualTo("9470.00");
        assertThat(buy.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(stockPositionRows()).contains("QRTV 300 9470.00 31.5667");
        JsonNode closedCall = null;
        for (JsonNode row : closed("action=EXR").get("items")) {
            if (row.get("id").asLong() == callId) {
                closedCall = row;
            }
        }
        assertThat(closedCall).as("closed EXR row of the call").isNotNull();
        assertThat(closedCall.get("exercise").get("stockCost").asText()).isEqualTo("9470.00");
        assertThat(closedCall.get("openingNetTotal").asText()).isEqualTo("-470.00");
        JsonNode rec = audit().get("items").get(0);
        assertThat(List.of(rec.get("operation").asText(), rec.get("objectId").asLong()))
                .containsExactly("UPDATE", l2);
        assertThat(texts(rec.get("sideEffects"))).contains("行权 #" + callId + " 股票成本 9,460.00 → 9,470.00");
        JsonNode se = sideEffect(rec, "stockCost");
        assertThat(se).as("sideEffect with field stockCost").isNotNull();
        assertThat(List.of(se.get("tradeId").asLong(), se.get("action").asText(), se.get("contractLabel").asText(),
                se.get("before").asText(), se.get("after").asText()))
                .containsExactly(callId, "EXR", QC, "9,460.00", "9,470.00");

        okEdit(l3, edit(1, "130.00", "2026-11-05T11:00", 100, null));
        JsonNode p = getTrade(putId).get("exercise");
        assertThat(List.of(p.get("premium").asText(), p.get("proceeds").asText(), p.get("soldCost").asText(),
                p.get("stockRealizedPnl").asText())).containsExactly("130.00", "4370.00", "4000.00", "370.00");
        JsonNode sell = getStock(sellId);
        assertThat(List.of(sell.get("amount").asText(), sell.get("realizedPnl").asText()))
                .containsExactly("4370.00", "370.00");
        rec = audit().get("items").get(0);
        assertThat(rec.get("objectId").asLong()).isEqualTo(l3);
        assertThat(texts(rec.get("sideEffects"))).contains("行权 #" + putId + " 股票卖出实收 4,380.00 → 4,370.00");
        se = sideEffect(rec, "proceeds");
        assertThat(se).isNotNull();
        assertThat(List.of(se.get("tradeId").asLong(), se.get("action").asText())).containsExactly(putId, "EXR");

        assertSummary("6990.00", "0.00", "6990.00");
        assertThat(closed("").get("filteredRealizedPnlTotal").asText()).isEqualTo("370.00");

        assertThat(rejectedDelete(l2, "CLOSE_EXCEEDS_POSITION")).isEqualTo(
                "该开仓被 2026-11-20 16:00 的行权 3 张覆盖（删除后行权将超过当时持仓 2 张），请先删除该行权");
    }

    @Test
    @DisplayName("重放（契约）：修改 T1 金额 8,000.00 → 7,800.00 → put 交出成本 3,900.00；审计连带「行权 #id 交出成本 4,000.00 → 3,900.00」")
    void replayStockEditChangesSoldCost() {
        scenarioE();
        long putId = exercisePut().get("id").asLong();
        Resp r = putStock(t1, stockEdit(200, "7800.00", "2026-11-03T10:00"));
        assertThat(r.status()).as("PUT stock -> %s", r.body()).isEqualTo(200);
        JsonNode e = getTrade(putId).get("exercise");
        assertThat(List.of(e.get("soldCost").asText(), e.get("stockRealizedPnl").asText()))
                .containsExactly("3900.00", "480.00");
        JsonNode rec = audit().get("items").get(0);
        assertThat(rec.get("objectType").asText()).isEqualTo("STOCK_TRADE");
        assertThat(texts(rec.get("sideEffects"))).containsExactly("行权 #" + putId + " 交出成本 4,000.00 → 3,900.00");
        assertThat(sideEffect(rec, "soldCost").get("action").asText()).isEqualTo("EXR");
    }

    @Test
    @DisplayName("重放被拒：put 行权后删除 T1 → 「删除后 2026-11-20 16:00 的行权 1 张 XYZ 2026-11-20 45 P 需要 100 股，当时持股 0 股，请先删除该行权」")
    void replayStockDeleteBlockedByPutExercise() {
        scenarioE();
        // S1 covers 100 shares of T1; close it first so deleting T1 is refused only because of the put exercise
        okClose(closeBody("BTC", 1, "XYZ", "2026-12-18", "50", "CALL", "30.00", "2026-11-10T10:00"));
        exercisePut();
        assertThat(rejectedStockDelete(t1, "SHARES_INSUFFICIENT")).isEqualTo(
                "删除后 2026-11-20 16:00 的行权 1 张 XYZ 2026-11-20 45 P 需要 100 股，当时持股 0 股，请先删除该行权");
    }

    // ================================================================== AC 9

    @Test
    @DisplayName("AC9 multiplier 1、行权价 12.345：call 买股 12.35（向上）、成本 12.85、现金 86.75；put 行权金额 12.34（向下）、实收 11.94、交出转入 1 股 10.00、+1.94、现金 99.09；流水带 multiplier")
    void ac9_multiplierOneRounding() {
        accountId = String.valueOf(newAccount("测试乘数账户"));
        deposit("100.00", "2026-11-02T10:00");
        long tin = okStock(stockBody("TRANSFER_IN", "QFZ", 1, "10.00", "2026-11-02"));
        open("BTO", 1, "QFZ", "2026-11-20", "12.345", "CALL", "0.50", "2026-11-03T10:00", 1);
        open("BTO", 1, "QFZ", "2026-11-20", "12.345", "PUT", "0.40", "2026-11-03T10:05", 1);
        assertSummary("99.10", "0.00", "99.10");
        assertThat(exerciseChoices(position("QFZ 2026-11-20 12.345 C"))).containsExactly("1:1");

        JsonNode c = okExercise(exrBody("QFZ", "2026-11-20", "12.345", "CALL", 1, "2026-11-20", null, null));
        assertThat(c.get("multiplier").asInt()).isEqualTo(1);
        JsonNode ce = c.get("exercise");
        assertThat(List.of(ce.get("shares").asInt(), ce.get("purchaseAmount").asText(), ce.get("premium").asText(),
                ce.get("stockCost").asText(), ce.get("stockCostPerShare").asText(), c.get("cashChange").asText()))
                .containsExactly(1, "12.35", "0.50", "12.85", "12.8500", "-12.35");
        assertSummary("86.75", "0.00", "86.75");
        assertThat(ledgerRowFor("OPTION_EXERCISE", c.get("id").asLong()).get("description").asText())
                .isEqualTo("行权 1 张 QFZ 2026-11-20 12.345 C（multiplier 1），买入 1 股 QFZ");

        JsonNode p = okExercise(exrBody("QFZ", "2026-11-20", "12.345", "PUT", 1, "2026-11-20", null, null));
        JsonNode pe = p.get("exercise");
        assertThat(List.of(pe.get("shares").asInt(), pe.get("saleAmount").asText(), pe.get("premium").asText(),
                pe.get("proceeds").asText(), pe.get("soldCost").asText(), pe.get("stockRealizedPnl").asText(),
                p.get("cashChange").asText()))
                .containsExactly(1, "12.34", "0.40", "11.94", "10.00", "1.94", "12.34");
        assertThat(soldLots(pe.get("soldLots"))).containsExactly("TRANSFER_IN " + tin + " 1 10.00");
        assertThat(pe.get("soldLots").get(0).get("tradeDate").asText()).isEqualTo("2026-11-02");
        assertSummary("99.09", "0.00", "99.09");
        assertThat(ledgerRowFor("OPTION_EXERCISE", p.get("id").asLong()).get("description").asText())
                .isEqualTo("行权 1 张 QFZ 2026-11-20 12.345 P（multiplier 1），卖出 1 股 QFZ");
        assertThat(stockPositionRows()).containsExactly("QFZ 1 12.85 12.8500");
        assertThat(stockLots("QFZ")).containsExactly("EXERCISE_BUY " + ce.get("stockTradeId").asLong() + " 1");
    }

    // ================================================================== AC 10

    @Test
    @DisplayName("AC10 账户隔离：默认账户行权后，小账户 QRTV 仍 1 张、现金不变；小账户用默认账户的行权 id 删除 → 404")
    void ac10_accountIsolation() {
        scenarioE();
        JsonNode t = exerciseCallAll();
        long id = t.get("id").asLong();
        long stockId = t.get("exercise").get("stockTradeId").asLong();

        smallAccount();
        assertThat(position(QC).get("contracts").asInt()).isEqualTo(1);
        assertThat(position(QC).get("availableCash").asText()).isEqualTo("830.00");
        assertThat(stockPositionRows()).isEmpty();
        Full before = full();
        assertErrorShape(deleteTrade(id), 404, "NOT_FOUND", TRADES + "/" + id);
        assertErrorShape(get(STOCKS + "/" + stockId), 404, "NOT_FOUND", STOCKS + "/" + stockId);
        assertThat(full()).isEqualTo(before);
        assertSummary("830.00", "0.00", "830.00");

        accountId = "1";
        assertThat(getTrade(id).get("exercise").get("stockCost").asText()).isEqualTo("9460.00");
        assertSummary("2510.00", "0.00", "2510.00");
    }

    @Test
    @DisplayName("AC10 格式校验：行权费 −1.00 / 1.234、日期为空、备注 501 字、张数 0 → VALIDATION_ERROR（字段 + 文案），什么都不保存")
    void ac10_formatValidation() {
        scenarioE();
        exerciseValidation(qcExr(3, "2026-11-20", "-1.00"), "exerciseFee=行权费不能为负数");
        exerciseValidation(qcExr(3, "2026-11-20", "1.234"), "exerciseFee=行权费最多 2 位小数");
        exerciseValidation(qcExr(3, null, null), "exerciseDate=行权日期不能为空");
        exerciseValidation(exrBody("QRTV", "2026-11-20", "30", "CALL", 3, "2026-11-20", null, "x".repeat(501)),
                "note=备注最多 500 个字符");
        exerciseValidation(qcExr(0, "2026-11-20", null), "contracts=张数必须是正整数");
        exerciseValidation(qcExr(-1, "2026-11-20", null), "contracts=张数必须是正整数");
        exerciseValidation(qcExr(null, "2026-11-20", null), "contracts=张数不能为空");
        exerciseValidation(exrBody("QRTV", "2026-11-20", "30", "CALL", 0, null, "-1.00", "x".repeat(501)),
                "contracts=张数必须是正整数", "exerciseDate=行权日期不能为空", "exerciseFee=行权费不能为负数",
                "note=备注最多 500 个字符");
    }

    @Test
    @DisplayName("格式：exerciseDate 不存在 / 格式错、行权费不是数字 → 400 BAD_REQUEST；备注 500 字可以；忽略 action / netAmount 等字段")
    void badRequestAndIgnoredFields() {
        scenarioE();
        for (String bad : List.of("2026-11-31", "2026/11/20", "20261120")) {
            Full before = full();
            Resp r = postExercise(qcExr(3, bad, null));
            assertErrorShape(r, 400, "BAD_REQUEST", EXERCISES);
            assertThat(detailFields(r)).as("exerciseDate=%s", bad).containsExactly("exerciseDate");
            assertThat(full()).isEqualTo(before);
        }
        Full before = full();
        Resp nan = postExercise(qcExr(3, "2026-11-20", "abc"));
        assertErrorShape(nan, 400, "BAD_REQUEST", EXERCISES);
        assertThat(full()).isEqualTo(before);

        ObjectNode body = exrBody("QRTV", "2026-11-20", "30", "CALL", 3, "2026-11-20", "", "x".repeat(500));
        body.put("action", "BTO");
        body.put("netAmount", "999.00");
        body.put("multiplier", 10);
        body.put("tradedAtLocal", "2026-11-05T10:00");
        JsonNode t = okExercise(body);
        assertThat(List.of(t.get("action").asText(), t.get("multiplier").asInt(), t.get("tradedAt").asText(),
                t.get("exercise").get("exerciseFee").asText())).containsExactly("EXR", 100, AT_1620, "0.00");
        assertThat(t.get("note").asText()).hasSize(500);
    }

    @Test
    @DisplayName("备注：交易 note、流水 remark 带备注；全空白保存为 null；行权费 5.00 → 现金 −9,005.00、成本 9,465.00")
    void noteAndFee() {
        scenarioE();
        JsonNode t = okExercise(exrBody("QRTV", "2026-11-20", "30", "CALL", 3, "2026-11-20", "5.00", "提前行权"));
        assertThat(t.get("note").asText()).isEqualTo("提前行权");
        JsonNode row = ledgerRowFor("OPTION_EXERCISE", t.get("id").asLong());
        assertThat(List.of(row.get("amount").asText(), row.get("remark").asText()))
                .containsExactly("-9005.00", "提前行权");
        assertThat(t.get("exercise").get("stockCost").asText()).isEqualTo("9465.00");
        assertThat(getStock(t.get("exercise").get("stockTradeId").asLong()).get("note").isNull()).isTrue();

        JsonNode p = okExercise(exrBody("XYZ", "2026-11-20", "45", "PUT", 1, "2026-11-20", null, "   "));
        assertThat(p.get("note").isNull()).isTrue();
    }

    @Test
    @DisplayName("其它接口：开仓 / 平仓接口收到 EXR → 400；交易列表方向筛选未知值文案含 EXR；股票类型筛选文案含 EXERCISE_BUY / EXERCISE_SELL；已平仓方向筛选文案含 EXR")
    void otherEndpointsAndFilterMessages() {
        scenarioE();
        Full before = full();
        Resp open = postTrade(trade("EXR", 1, "QRTV", "2026-11-20", "30", "CALL", "1.00", "2026-11-10T10:00", 100));
        assertErrorShape(open, 400, "VALIDATION_ERROR", TRADES);
        assertThat(details(open)).contains("action=本功能只支持开仓（BTO / STO），平仓请使用平仓功能");
        Resp close = postRaw(CLOSES, closeBody("EXR", 1, "QRTV", "2026-11-20", "30", "CALL", "1.00",
                "2026-11-10T10:00").toString(), MediaType.APPLICATION_JSON);
        assertErrorShape(close, 400, "VALIDATION_ERROR", CLOSES);
        assertThat(details(close)).contains("action=本功能只支持平仓（BTC / STC），开仓请使用开仓功能");
        assertThat(full()).isEqualTo(before);

        Resp badAction = get(TRADES + "?action=FOO");
        assertErrorShape(badAction, 400, "BAD_REQUEST", TRADES);
        assertThat(details(badAction)).containsExactly("action=方向只能是 BTO、STO、BTC、STC、EXP、ASG、EXR");
        Resp badType = get(STOCKS + "?type=BUY,FOO");
        assertErrorShape(badType, 400, "BAD_REQUEST", STOCKS);
        assertThat(details(badType)).containsExactly(
                "type=类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL、EXERCISE_BUY、EXERCISE_SELL");
        Resp badClosed = get(CLOSED + "?action=BTO");
        assertErrorShape(badClosed, 400, "BAD_REQUEST", CLOSED);
        assertThat(details(badClosed)).containsExactly("action=方向只能是 BTC、STC、EXP、ASG、EXR");
        assertThat(get(CLOSED + "?action=EXERCISE_SELL").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("put 行权使之后的 covered call 覆盖不足（E3）→ COVERAGE_INSUFFICIENT「行权后 … 起 XYZ 不足以覆盖卖出的 call：需要 … 股，持有 … 股」")
    void putExerciseBreaksLaterCoverage() {
        scenarioE();
        // after 11-20 16:00 the remaining 100 available shares are used by a later covered call
        open("STO", 1, "XYZ", "2026-12-18", "55", "CALL", "50.00", "2026-11-21T10:00", 100);
        assertThat(coverage("XYZ")).isEqualTo("200 / 200 / 0");
        assertThat(rejectedExercise(xpExr(1, "2026-11-20", null), "COVERAGE_INSUFFICIENT"))
                .isEqualTo("行权后 2026-11-21 10:00 起 XYZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");
    }
}
