package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-012 expire worthless (EXP) and the Q83 16:00 trading cutoff, end-to-end over HTTP against the whole
 * application (in-memory H2, profile "test"). The clock is a {@link MutableClock}; unless a test moves it, "now" =
 * 2026-11-23 10:00 New York (Monday after the Friday 2026-11-20 expiration). Fictional tickers only.
 *
 * <p>Every rejected write must leave cash, ledger, trades, positions, closed trades and the audit log untouched.
 */
class Feat012ExpireWorthlessSystemTest extends OptionTradeSystemTestBase {

    static final Instant MON_1000 = Instant.parse("2026-11-23T15:00:00Z");
    static final String EXPIRATIONS = "/api/option-trades/expirations";
    static final String CLOSES = "/api/option-trades/closes";
    static final String CLOSED = "/api/closed-trades";
    static final String AUDIT = "/api/audit-log";

    static final String XYZ = "XYZ 2026-11-20 45 P";
    static final String QRTV_C = "QRTV 2026-11-20 30 C";
    static final String IXQZ = "IXQZ 2026-11-20 8 C";
    static final String QRTV_P = "QRTV 2026-11-20 25 P";
    static final String CUTOFF = "2026-11-20T21:00:00Z";
    static final String CUTOFF_LOCAL = "2026-11-20T16:00:00";
    static final String TOO_LATE = "成交时间不能晚于到期日（2026-11-20）美东 16:00";

    long d1;
    long e1;
    long e2;
    long e3;
    long e4;
    long e5;

    @Override
    Instant now() {
        return MON_1000;
    }

    // ================================================================== helpers

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

    JsonNode okClose(ObjectNode body) {
        Resp r = postClose(body);
        assertThat(r.status()).as("close %s -> %s", body, r.body()).isEqualTo(201);
        return r.body();
    }

    ObjectNode expBody(String symbol, String exp, String strike, String type, Integer contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        if (contracts != null) {
            n.put("contracts", contracts);
        }
        return n;
    }

    Resp postExpire(ObjectNode body) {
        return postRaw(EXPIRATIONS, body.toString(), MediaType.APPLICATION_JSON);
    }

    /** POST an expiration that must succeed; checks the invariant parts of the EXP representation. */
    JsonNode okExpire(ObjectNode body) {
        Resp r = postExpire(body);
        assertThat(r.status()).as("expire %s -> %s", body, r.body()).isEqualTo(201);
        JsonNode t = r.body();
        assertThat(t.get("action").asText()).isEqualTo("EXP");
        assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(t.get("netAmount").isTextual()).isTrue();
        assertThat(t.get("cashChange").asText()).isEqualTo("0.00");
        assertThat(t.get("frozenChange").isTextual()).isTrue();
        assertThat(t.get("realizedPnl").isTextual()).isTrue();
        assertThat(t.get("note").isNull()).as("Q84: an expiry never has a note").isTrue();
        assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(t.get("expiredDirection").asText()).isIn("SHORT", "LONG");
        Instant.parse(t.get("createdAt").asText());
        assertFrozenConsistent();
        return t;
    }

    JsonNode okExpire(String symbol, String exp, String strike, String type, int contracts) {
        return okExpire(expBody(symbol, exp, strike, type, contracts));
    }

    JsonNode expireXyz() {
        return okExpire("XYZ", "2026-11-20", "45", "PUT", 3);
    }

    /** Expiry that must be rejected with a 422 code; nothing changes; returns the message. */
    String rejectedExpire(ObjectNode body, String code) {
        Full before = full();
        return rejected(postExpire(body), before, code, EXPIRATIONS);
    }

    void expireValidation(ObjectNode body, String field, String message) {
        Full before = full();
        Resp r = postExpire(body);
        assertErrorShape(r, 400, "VALIDATION_ERROR", EXPIRATIONS);
        List<String> details = new ArrayList<>();
        r.body().get("details").forEach(d -> details.add(d.get("field").asText() + "=" + d.get("message").asText()));
        assertThat(details).contains(field + "=" + message);
        assertThat(full()).isEqualTo(before);
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

    record Full(State state, JsonNode positions, JsonNode ledger, JsonNode audit, JsonNode trades, JsonNode closed) {
    }

    Full full() {
        return new Full(state(), positionsBody(), ledger(1, 100), get(AUDIT + "?size=100").body(),
                get(TRADES + "?size=100").body(), get(CLOSED + "?size=100").body());
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

    String rejectedOpen(ObjectNode body, String code) {
        Full before = full();
        return rejected(postTrade(body), before, code, TRADES);
    }

    String rejectedClose(ObjectNode body, String code) {
        Full before = full();
        return rejected(postClose(body), before, code, CLOSES);
    }

    /** 400 VALIDATION_ERROR on tradedAtLocal with exactly this message; nothing changes. */
    void timeFieldError(Resp r, Full before, String path, String message) {
        assertErrorShape(r, 400, "VALIDATION_ERROR", path);
        assertThat(detailFields(r)).containsExactly("tradedAtLocal");
        assertThat(r.body().get("details").get(0).get("message").asText()).isEqualTo(message);
        assertThat(full()).as("nothing changed").isEqualTo(before);
    }

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

    /** "closable/expiryPending/expirable" of a position. */
    String buttons(JsonNode p) {
        return p.get("closable").asBoolean() + "/" + p.get("expiryPending").asBoolean() + "/"
                + p.get("expirable").asBoolean();
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

    /** Scenario E in the current account. */
    void scenarioE() {
        d1 = deposit("20000.00", "2026-11-02T10:00").get("id").asLong();
        e1 = open("STO", 2, "XYZ", "2026-11-20", "45", "PUT", "240.00", "2026-11-03T10:00", 100);
        e2 = open("BTO", 1, "QRTV", "2026-11-20", "30", "CALL", "85.65", "2026-11-04T10:00", 100);
        e3 = open("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "130.00", "2026-11-05T10:00", 100);
        e4 = open("STO", 1, "IXQZ", "2026-11-20", "8", "CALL", "22.34", "2026-11-06T10:00", 100);
        e5 = open("BTO", 2, "QRTV", "2026-11-20", "25", "PUT", "40.00", "2026-11-06T11:00", 100);
        assertSummary("20266.69", "13500.00", "6766.69");
        assertThat(ledgerCount()).isEqualTo(6);
    }

    // ================================================================== AC 1

    @Test
    @DisplayName("AC1 到期后（周末之后，11-23 10:00）：4 行按合约排序，都已过期 3 天，只有作废；首页已过期未处理 4、冻结 13,500.00")
    void ac1_positionsAfterExpiry() {
        scenarioE();
        assertThat(positionLabels()).containsExactly(IXQZ, QRTV_P, QRTV_C, XYZ);
        for (JsonNode p : positionsBody().get("positions")) {
            assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
            assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
            assertThat(p.get("dte").asInt()).isEqualTo(-3);
            assertThat(buttons(p)).as(p.get("contractLabel").asText()).isEqualTo("false/false/true");
            assertThat(p.get("expiryContracts").asInt()).isEqualTo(p.get("contracts").asInt());
            assertThat(p.get("expiryAt").asText()).isEqualTo(CUTOFF);
            assertThat(p.get("expiryAtLocal").asText()).isEqualTo(CUTOFF_LOCAL);
        }
        assertThat(position(XYZ).get("expiryContracts").asInt()).isEqualTo(3);
        JsonNode d = dashboard();
        assertThat(d.get("positions").get("expiredUnprocessedCount").asInt()).isEqualTo(4);
        assertThat(d.get("positions").get("positionCount").asInt()).isEqualTo(4);
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo("13500.00");
    }

    // ================================================================== AC 2

    @Test
    @DisplayName("AC2 空头 put 两批次作废：响应符合契约；释放 13,500.00、+370.00；流水 7 条；交易列表、已平仓、按 id 查询；不写审计")
    void ac2_shortPutMultiLot() {
        scenarioE();
        JsonNode t = expireXyz();
        long id = t.get("id").asLong();
        assertThat(id).isGreaterThan(e5);
        assertThat(t.get("symbol").asText()).isEqualTo("XYZ");
        assertThat(t.get("expirationDate").asText()).isEqualTo("2026-11-20");
        assertThat(t.get("strike").asText()).isEqualTo("45");
        assertThat(t.get("optionType").asText()).isEqualTo("PUT");
        assertThat(t.get("contractLabel").asText()).isEqualTo(XYZ);
        assertThat(t.get("contracts").asInt()).isEqualTo(3);
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("tradedAt").asText()).isEqualTo(CUTOFF);
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo(CUTOFF_LOCAL);
        assertThat(t.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("370.00");
        assertThat(t.get("expiredDirection").asText()).isEqualTo("SHORT");
        assertThat(t.get("createdAt").asText()).isEqualTo("2026-11-23T15:00:00Z");
        JsonNode lots = t.get("coveredLots");
        assertThat(lots).hasSize(2);
        assertThat(List.of(lots.get(0).get("tradeId").asLong(), lots.get(0).get("action").asText(),
                lots.get(0).get("tradedAt").asText(), lots.get(0).get("tradedAtLocal").asText(),
                lots.get(0).get("contracts").asInt(), lots.get(0).get("netAmount").asText(),
                lots.get(0).get("frozen").asText()))
                .containsExactly(e1, "STO", "2026-11-03T15:00:00Z", "2026-11-03T10:00:00", 2, "240.00", "9000.00");
        assertThat(List.of(lots.get(1).get("tradeId").asLong(), lots.get(1).get("tradedAtLocal").asText(),
                lots.get(1).get("contracts").asInt(), lots.get(1).get("netAmount").asText(),
                lots.get(1).get("frozen").asText()))
                .containsExactly(e3, "2026-11-05T10:00:00", 1, "130.00", "4500.00");
        // stored at the cutoff in UTC
        assertThat(jdbc.queryForObject("SELECT traded_at FROM option_trade WHERE id = ?", java.time.OffsetDateTime.class,
                id).toInstant()).isEqualTo(Instant.parse(CUTOFF));

        assertSummary("20266.69", "0.00", "20266.69");
        assertThat(positionLabels()).containsExactly(IXQZ, QRTV_P, QRTV_C);

        // ledger: 7 rows, the expiry on top
        JsonNode led = ledger(1, 100);
        assertThat(led.get("totalItems").asInt()).isEqualTo(7);
        JsonNode row = led.get("items").get(0);
        assertThat(row.get("id").asLong()).isEqualTo(t.get("ledgerEntryId").asLong());
        assertThat(row.get("occurredAt").asText()).isEqualTo(CUTOFF);
        assertThat(row.get("sourceType").asText()).isEqualTo("OPTION_EXPIRE");
        assertThat(row.get("sourceId").asLong()).isEqualTo(id);
        assertThat(row.get("amount").asText()).isEqualTo("0.00");
        assertThat(row.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(row.get("balanceAfter").asText()).isEqualTo("20266.69");
        assertThat(row.get("description").asText()).isEqualTo("到期作废 3 张 XYZ 2026-11-20 45 P");
        assertThat(row.get("remark").isNull()).isTrue();
        assertThat(row.get("hasAuditLog").asBoolean()).isFalse();

        // trade list: 6, the EXP row first; GET by id equals the POST response
        JsonNode list = tradeList("");
        assertThat(list.get("totalItems").asInt()).isEqualTo(6);
        JsonNode first = list.get("items").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(id);
        assertThat(first.get("action").asText()).isEqualTo("EXP");
        assertThat(first.get("tradedAtLocal").asText()).isEqualTo(CUTOFF_LOCAL);
        assertThat(first.get("contracts").asInt()).isEqualTo(3);
        assertThat(first.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(first.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(first.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(first.get("expiredDirection").asText()).isEqualTo("SHORT");
        assertThat(list.get("items").get(1).get("expiredDirection").isNull()).as("non-EXP rows: null").isTrue();
        assertThat(getTrade(id)).isEqualTo(t);

        // closed trades: one EXP row
        JsonNode c = closed("");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("370.00");
        JsonNode cr = c.get("items").get(0);
        assertThat(List.of(cr.get("id").asLong(), cr.get("tradedAt").asText(), cr.get("tradedAtLocal").asText(),
                cr.get("contractLabel").asText(), cr.get("action").asText(), cr.get("closedDirection").asText(),
                cr.get("contracts").asInt(), cr.get("openingNetTotal").asText(),
                cr.get("openingNetPerContract").asText(), cr.get("closeNetAmount").asText(),
                cr.get("closeNetPerContract").asText(), cr.get("realizedPnl").asText()))
                .containsExactly(id, CUTOFF, CUTOFF_LOCAL, XYZ, "EXP", "SHORT", 3, "370.00", "123.33", "0.00", "0.00",
                        "370.00");
        assertThat(cr.get("note").isNull()).isTrue();
        assertThat(cr.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(lotIds(cr)).containsExactly(e1, e3);

        // no audit for a new record
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    // ================================================================== AC 3

    @Test
    @DisplayName("AC3 多头 call / 空头 call / 多头 put 作废：无流水；已平仓 4 笔 +266.69；盈亏汇总、首页；筛选 EXP / STO / BTC")
    void ac3_otherDirections() {
        scenarioE();
        long xyz = expireXyz().get("id").asLong();

        JsonNode longCall = okExpire("QRTV", "2026-11-20", "30", "CALL", 1);
        assertThat(longCall.get("realizedPnl").asText()).isEqualTo("-85.65");
        assertThat(longCall.get("expiredDirection").asText()).isEqualTo("LONG");
        assertThat(longCall.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(longCall.get("ledgerEntryId").isNull()).as("no ledger row -> null").isTrue();
        assertThat(longCall.get("coveredLots").get(0).get("netAmount").asText()).isEqualTo("-85.65");
        assertThat(longCall.get("coveredLots").get(0).get("frozen").asText()).isEqualTo("0.00");
        assertThat(lotIds(longCall)).containsExactly(e2);

        JsonNode shortCall = okExpire("IXQZ", "2026-11-20", "8", "CALL", 1);
        assertThat(shortCall.get("realizedPnl").asText()).isEqualTo("22.34");
        assertThat(shortCall.get("expiredDirection").asText()).isEqualTo("SHORT");
        assertThat(shortCall.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(shortCall.get("ledgerEntryId").isNull()).isTrue();

        JsonNode longPut = okExpire("QRTV", "2026-11-20", "25", "PUT", 2);
        assertThat(longPut.get("realizedPnl").asText()).isEqualTo("-40.00");
        assertThat(longPut.get("expiredDirection").asText()).isEqualTo("LONG");
        assertThat(longPut.get("ledgerEntryId").isNull()).isTrue();
        assertThat(longPut.get("coveredLots")).hasSize(1);
        assertThat(longPut.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(e5);
        assertThat(longPut.get("coveredLots").get(0).get("contracts").asInt()).isEqualTo(2);
        assertThat(longPut.get("coveredLots").get(0).get("netAmount").asText()).isEqualTo("-40.00");

        assertThat(ledgerCount()).isEqualTo(7);
        assertThat(ledgerRowFor("OPTION_EXPIRE", xyz)).isNotNull();
        assertSummary("20266.69", "0.00", "20266.69");

        JsonNode c = closed("");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(4);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("266.69");
        List<String> rows = new ArrayList<>();
        c.get("items").forEach(i -> rows.add(i.get("contractLabel").asText() + " " + i.get("realizedPnl").asText()));
        assertThat(rows).containsExactly(QRTV_P + " -40.00", IXQZ + " 22.34", QRTV_C + " -85.65", XYZ + " 370.00");

        JsonNode p = pnl();
        assertThat(p.get("closedCount").asInt()).isEqualTo(4);
        assertThat(p.get("realizedPnlTotal").asText()).isEqualTo("266.69");
        List<String> bySymbol = new ArrayList<>();
        p.get("bySymbol").forEach(s -> bySymbol.add(s.get("symbol").asText() + " " + s.get("closedCount").asInt()
                + " " + s.get("realizedPnl").asText()));
        assertThat(bySymbol).containsExactly("IXQZ 1 22.34", "QRTV 2 -125.65", "XYZ 1 370.00");
        List<String> byMonth = new ArrayList<>();
        p.get("byMonth").forEach(s -> byMonth.add(s.get("month").asText() + " " + s.get("closedCount").asInt()
                + " " + s.get("realizedPnl").asText()));
        assertThat(byMonth).containsExactly("2026-11 4 266.69");

        JsonNode d = dashboard();
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(4);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("266.69");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(d.get("currentMonthRealizedPnl").get("closedCount").asInt()).isEqualTo(4);
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("266.69");
        assertThat(d.get("positions").get("positionCount").asInt()).isZero();
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(d.get("positions").get("expiredUnprocessedCount").asInt()).isZero();

        // filters
        assertThat(tradeList("action=EXP").get("totalItems").asInt()).isEqualTo(4);
        assertThat(ids(tradeList("action=STO"))).containsExactly(e4, e3, e1);
        assertThat(tradeList("action=EXP,STO").get("totalItems").asInt()).isEqualTo(7);
        assertThat(tradeList("action=EXP&action=BTO").get("totalItems").asInt()).isEqualTo(6);
        JsonNode ce = closed("action=EXP");
        assertThat(ce.get("filteredCount").asInt()).isEqualTo(4);
        assertThat(ce.get("filteredRealizedPnlTotal").asText()).isEqualTo("266.69");
        JsonNode cb = closed("action=BTC");
        assertThat(cb.get("filteredCount").asInt()).isZero();
        assertThat(cb.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(closed("action=BTC,EXP").get("filteredCount").asInt()).isEqualTo(4);
        // date filters use the cutoff's New York date
        assertThat(closed("startDate=2026-11-20&endDate=2026-11-20").get("filteredCount").asInt()).isEqualTo(4);
        assertThat(closed("startDate=2026-11-21").get("filteredCount").asInt()).isZero();
        assertThat(tradeList("startDate=2026-11-20&endDate=2026-11-20").get("totalItems").asInt()).isEqualTo(4);
    }

    // ================================================================== AC 4

    @Test
    @DisplayName("AC4 到期日当天：15:59 只可平仓、作废被拒；16:00 / 17:29 到期待确认、作废被拒；11-21 已过期 1 天；17:30 可作废，记在 16:00")
    void ac4_buttonsOnExpirationDay() {
        scenarioE();
        String tooEarly = "到期日（2026-11-20）美东 17:30 起才能作废";

        clock.set(Instant.parse("2026-11-17T15:00:00Z"));
        JsonNode p = position(XYZ);
        assertThat(buttons(p)).isEqualTo("true/false/false");
        assertThat(p.get("dte").asInt()).isEqualTo(3);

        clock.set(Instant.parse("2026-11-20T20:59:00Z")); // 15:59 NY
        p = position(XYZ);
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(buttons(p)).isEqualTo("true/false/false");
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 3), "EXPIRY_TOO_EARLY"))
                .isEqualTo(tooEarly);

        clock.set(Instant.parse("2026-11-20T21:00:00Z")); // 16:00:00 NY exactly -> no longer closable
        p = position(XYZ);
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(buttons(p)).isEqualTo("false/true/false");

        clock.set(Instant.parse("2026-11-20T22:29:00Z")); // 17:29
        assertThat(buttons(position(XYZ))).isEqualTo("false/true/false");
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 3), "EXPIRY_TOO_EARLY"))
                .isEqualTo(tooEarly);
        clock.set(Instant.parse("2026-11-20T22:29:59Z")); // 17:29:59
        assertThat(buttons(position(XYZ))).isEqualTo("false/true/false");
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 3), "EXPIRY_TOO_EARLY"))
                .isEqualTo(tooEarly);

        clock.set(Instant.parse("2026-11-21T14:00:00Z")); // Sat 09:00
        p = position(XYZ);
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(1);
        assertThat(buttons(p)).isEqualTo("false/false/true");

        clock.set(Instant.parse("2026-11-20T22:30:00Z")); // 17:30:00 inclusive
        p = position(XYZ);
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(p.get("dte").asInt()).isZero();
        assertThat(buttons(p)).isEqualTo("false/false/true");
        JsonNode t = expireXyz();
        assertThat(t.get("tradedAt").asText()).isEqualTo(CUTOFF);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("370.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(t.get("createdAt").asText()).isEqualTo("2026-11-20T22:30:00Z");
        assertSummary("20266.69", "0.00", "20266.69");
    }

    @Test
    @DisplayName("边界：夏令时到期日 2027-03-19 — cutoff 20:00Z、17:30 = 21:30Z；16:00:00 平仓允许、16:00:01 拒绝；作废记在 20:00Z")
    void dstExpiration() {
        clock.set(Instant.parse("2027-03-10T15:00:00Z"));
        deposit("10000.00", "2027-03-01T10:00");
        open("STO", 2, "XYZ", "2027-03-19", "20", "PUT", "50.00", "2027-03-02T10:00", 100);
        clock.set(Instant.parse("2027-03-19T19:59:59Z"));
        assertThat(buttons(position("XYZ 2027-03-19 20 P"))).isEqualTo("true/false/false");
        clock.set(Instant.parse("2027-03-19T20:00:00Z"));
        assertThat(buttons(position("XYZ 2027-03-19 20 P"))).isEqualTo("false/true/false");
        clock.set(Instant.parse("2027-03-19T21:29:59Z"));
        assertThat(buttons(position("XYZ 2027-03-19 20 P"))).isEqualTo("false/true/false");
        assertThat(rejectedExpire(expBody("XYZ", "2027-03-19", "20", "PUT", 2), "EXPIRY_TOO_EARLY"))
                .isEqualTo("到期日（2027-03-19）美东 17:30 起才能作废");
        clock.set(Instant.parse("2027-03-19T21:30:00Z"));
        JsonNode p = position("XYZ 2027-03-19 20 P");
        assertThat(buttons(p)).isEqualTo("false/false/true");
        assertThat(p.get("expiryAt").asText()).isEqualTo("2027-03-19T20:00:00Z");
        assertThat(p.get("expiryAtLocal").asText()).isEqualTo("2027-03-19T16:00:00");
        // trades: 16:00:01 EDT rejected, 16:00:00 allowed (BTC 1 of 2 is not whole lots, so close 2)
        Full before = full();
        timeFieldError(postClose(closeBody("BTC", 2, "XYZ", "2027-03-19", "20", "PUT", "10.00", "2027-03-19T16:00:01")),
                before, CLOSES, "成交时间不能晚于到期日（2027-03-19）美东 16:00");
        open("STO", 1, "XYZ", "2027-03-19", "20", "PUT", "5.00", "2027-03-19T16:00:00", 100);
        JsonNode t = okExpire("XYZ", "2027-03-19", "20", "PUT", 3);
        assertThat(t.get("tradedAt").asText()).isEqualTo("2027-03-19T20:00:00Z");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2027-03-19T16:00:00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("55.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-6000.00");
    }

    @Test
    @DisplayName("边界：到期日是假日（2026-11-26 感恩节，周四）仍记在该日 16:00；入金后无冻结")
    void holidayExpiration() {
        clock.set(Instant.parse("2026-11-30T15:00:00Z"));
        deposit("5000.00", "2026-11-16T10:00");
        open("STO", 1, "XYZ", "2026-11-26", "30", "PUT", "40.00", "2026-11-24T10:00", 100);
        JsonNode t = okExpire("XYZ", "2026-11-26", "30", "PUT", 1);
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-26T21:00:00Z");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("40.00");
        assertSummary("5040.00", "0.00", "5040.00");
    }

    // ================================================================== AC 5

    @Test
    @DisplayName("AC5 晚几天才点：记在 11-27 16:00、归 2026-11；冻结从 16:00 起释放使 11-30 出金成功；删除作废被 Q19 拒绝；先删出金再删作废")
    void ac5_lateClickAndQ19() {
        clock.set(Instant.parse("2026-12-01T15:00:00Z"));
        accountId = String.valueOf(newAccount("测试作废账户"));
        deposit("5000.00", "2026-11-16T10:00");
        open("STO", 1, "XYZ", "2026-11-27", "40", "PUT", "95.00", "2026-11-17T10:00", 100);
        assertSummary("5095.00", "4000.00", "1095.00");

        assertThat(rejectedWithdrawal("1500.00", "2026-11-30T10:00")).contains("1,095.00");

        JsonNode t = okExpire("XYZ", "2026-11-27", "40", "PUT", 1);
        long exp = t.get("id").asLong();
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-27T21:00:00Z");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("95.00");
        assertSummary("5095.00", "0.00", "5095.00");
        JsonNode d = dashboard();
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-12");
        assertThat(d.get("currentMonthRealizedPnl").get("closedCount").asInt()).isZero();
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("95.00");
        JsonNode p = pnl();
        assertThat(p.get("byMonth")).hasSize(1);
        assertThat(p.get("byMonth").get(0).get("month").asText()).isEqualTo("2026-11");
        assertThat(p.get("byMonth").get(0).get("realizedPnl").asText()).isEqualTo("95.00");

        long w = withdraw("1500.00", "2026-11-30T10:00").get("id").asLong();
        assertSummary("3595.00", "0.00", "3595.00");
        List<String> types = new ArrayList<>();
        ledger(1, 100).get("items").forEach(i -> types.add(i.get("sourceType").asText()));
        assertThat(types).containsExactly("WITHDRAWAL", "OPTION_EXPIRE", "OPTION_OPEN", "DEPOSIT");

        long auditBefore = audit().get("totalItems").asLong();
        assertThat(rejectedDelete(exp, "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：删除后 2026-11-30 10:00 时可用现金将变为 −405.00");
        assertThat(audit().get("totalItems").asLong()).isEqualTo(auditBefore);

        Resp dw = exchange(HttpMethod.DELETE, "/api/cash/transfers/" + w, null, MediaType.APPLICATION_JSON);
        assertThat(dw.status()).isEqualTo(204);
        okDelete(exp);
        assertSummary("5095.00", "4000.00", "1095.00");
        JsonNode pos = position("XYZ 2026-11-27 40 P");
        assertThat(pos.get("daysPastExpiration").asInt()).isEqualTo(4);
        assertThat(buttons(pos)).isEqualTo("false/false/true");
    }

    // ================================================================== AC 6

    @Test
    @DisplayName("AC6 先平掉 E1（+180.00），再作废剩余 1 张（E3，+130.00）；已平仓 XYZ 两行，作废在上")
    void ac6_partialCloseThenExpire() {
        scenarioE();
        JsonNode c = okClose(closeBody("BTC", 2, "XYZ", "2026-11-20", "45", "PUT", "60.00", "2026-11-18T10:00"));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("180.00");
        assertThat(c.get("frozenChange").asText()).isEqualTo("-9000.00");
        JsonNode p = position(XYZ);
        assertThat(p.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("frozenCash").asText()).isEqualTo("4500.00");
        assertThat(p.get("lotCount").asInt()).isEqualTo(1);
        assertThat(p.get("expiryContracts").asInt()).isEqualTo(1);

        JsonNode t = okExpire("XYZ", "2026-11-20", "45", "PUT", 1);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("130.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-4500.00");
        assertThat(lotIds(t)).containsExactly(e3);
        assertSummary("20206.69", "0.00", "20206.69");
        List<String> rows = new ArrayList<>();
        closed("symbol=XYZ").get("items").forEach(i -> rows.add(i.get("action").asText() + " "
                + i.get("realizedPnl").asText() + " " + i.get("tradedAtLocal").asText()));
        assertThat(rows).containsExactly("EXP 130.00 2026-11-20T16:00:00", "BTC 180.00 2026-11-18T10:00:00");
    }

    // ================================================================== AC 7

    @Test
    @DisplayName("AC7 删除作废：恢复持仓/冻结，流水与已平仓消失，审计删除记录（摘要、before 完整）；再次作废得新 id，无审计")
    void ac7_deleteAndReExpire() {
        scenarioE();
        long exp = expireXyz().get("id").asLong();
        okDelete(exp);
        assertSummary("20266.69", "13500.00", "6766.69");
        JsonNode p = position(XYZ);
        assertThat(p.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p.get("contracts").asInt()).isEqualTo(3);
        assertThat(p.get("lotCount").asInt()).isEqualTo(2);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("370.00");
        assertThat(p.get("frozenCash").asText()).isEqualTo("13500.00");
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(buttons(p)).isEqualTo("false/false/true");
        assertThat(closed("").get("filteredCount").asInt()).isZero();
        assertThat(ledgerCount()).isEqualTo(6);
        assertThat(tradeList("").get("totalItems").asInt()).isEqualTo(5);
        Resp gone = get(TRADES + "/" + exp);
        assertErrorShape(gone, 404, "NOT_FOUND", TRADES + "/" + exp);

        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).isEqualTo(1);
        JsonNode rec = a.get("items").get(0);
        assertThat(rec.get("operation").asText()).isEqualTo("DELETE");
        assertThat(rec.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(rec.get("objectId").asLong()).isEqualTo(exp);
        assertThat(rec.get("summary").asText()).isEqualTo("到期作废 XYZ 2026-11-20 45 P 3 张");
        assertThat(rec.get("after").isNull()).isTrue();
        assertThat(rec.get("changes")).isEmpty();
        assertThat(rec.get("sideEffects")).isEmpty();
        JsonNode b = rec.get("before");
        assertThat(List.of(b.get("id").asLong(), b.get("symbol").asText(), b.get("expirationDate").asText(),
                b.get("strike").asText(), b.get("optionType").asText(), b.get("contractLabel").asText(),
                b.get("action").asText(), b.get("contracts").asInt(), b.get("multiplier").asInt(),
                b.get("netAmount").asText(), b.get("tradedAt").asText(), b.get("tradedAtLocal").asText(),
                b.get("cashChange").asText(), b.get("frozenChange").asText()))
                .containsExactly(exp, "XYZ", "2026-11-20", "45", "PUT", XYZ, "EXP", 3, 100, "0.00", CUTOFF,
                        CUTOFF_LOCAL, "0.00", "-13500.00");
        assertThat(b.get("note").isNull()).isTrue();

        JsonNode again = expireXyz();
        assertThat(again.get("id").asLong()).isGreaterThan(exp);
        assertThat(again.get("realizedPnl").asText()).isEqualTo("370.00");
        assertThat(again.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(again.get("hasAuditLog").asBoolean()).as("new record, no own audit").isFalse();
        assertThat(tradeList("action=EXP").get("items").get(0).get("hasAuditLog").asBoolean()).isFalse();
        assertSummary("20266.69", "0.00", "20266.69");
        assertThat(ledgerCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("删除无流水的作废（多头 call）：流水条数不变，持仓恢复；审计 before.frozenChange 0.00")
    void deleteExpiryWithoutLedger() {
        scenarioE();
        long exp = okExpire("QRTV", "2026-11-20", "30", "CALL", 1).get("id").asLong();
        assertThat(ledgerCount()).isEqualTo(6);
        okDelete(exp);
        assertThat(ledgerCount()).isEqualTo(6);
        assertThat(position(QRTV_C).get("contracts").asInt()).isEqualTo(1);
        JsonNode rec = audit().get("items").get(0);
        assertThat(rec.get("summary").asText()).isEqualTo("到期作废 QRTV 2026-11-20 30 C 1 张");
        assertThat(rec.get("before").get("frozenChange").asText()).isEqualTo("0.00");
    }

    // ================================================================== AC 8

    @Test
    @DisplayName("AC8 修改被作废开仓的净额 130→135：作废 +375.00，写审计")
    void ac8_editCoveredNet() {
        scenarioE();
        long exp = expireXyz().get("id").asLong();
        okEdit(e3, edit(1, "135.00", "2026-11-05T10:00", 100, null));
        assertThat(getTrade(exp).get("realizedPnl").asText()).isEqualTo("375.00");
        assertThat(closed("action=EXP").get("items").get(0).get("realizedPnl").asText()).isEqualTo("375.00");
        assertSummary("20271.69", "0.00", "20271.69");
    }

    @Test
    @DisplayName("AC8 删除被作废的开仓 / 改小张数 → 拒绝，提示先删除该到期作废；什么都不变、不写审计")
    void ac8_deleteOrShrinkCoveredOpen() {
        scenarioE();
        expireXyz();
        assertThat(rejectedDelete(e3, "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("该开仓被 2026-11-20 16:00 的到期作废 3 张覆盖（删除后作废将超过当时持仓 2 张），请先删除该到期作废");
        assertThat(rejectedEdit(e1, edit(1, "240.00", "2026-11-03T10:00", 100, null), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("该开仓被 2026-11-20 16:00 的到期作废 3 张覆盖（修改后作废将超过当时持仓 2 张），请先删除该到期作废");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("AC8 补录较晚的开仓（Q65）：原作废不变，新批次可再作废；两条 XYZ 作废后作废的在上；删较早那条被拒（不再是整笔批次）")
    void ac8_backfillLaterOpenThenExpireAgain() {
        scenarioE();
        long exp1 = expireXyz().get("id").asLong();
        long late = open("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "110.00", "2026-11-19T10:00", 100);
        JsonNode first = getTrade(exp1);
        assertThat(lotIds(first)).containsExactly(e1, e3);
        assertThat(first.get("realizedPnl").asText()).isEqualTo("370.00");
        JsonNode p = position(XYZ);
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("frozenCash").asText()).isEqualTo("4500.00");
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(buttons(p)).isEqualTo("false/false/true");
        assertThat(p.get("expiryContracts").asInt()).isEqualTo(1);
        assertSummary("20376.69", "4500.00", "15876.69");

        JsonNode exp2 = okExpire("XYZ", "2026-11-20", "45", "PUT", 1);
        assertThat(exp2.get("tradedAt").asText()).isEqualTo(CUTOFF);
        assertThat(exp2.get("realizedPnl").asText()).isEqualTo("110.00");
        assertThat(exp2.get("frozenChange").asText()).isEqualTo("-4500.00");
        assertThat(lotIds(exp2)).containsExactly(late);
        assertSummary("20376.69", "0.00", "20376.69");
        assertThat(ids(tradeList("action=EXP&symbol=XYZ"))).containsExactly(exp2.get("id").asLong(), exp1);

        assertThat(rejectedDelete(exp1, "CLOSE_NOT_WHOLE_LOTS")).isEqualTo(
                "删除后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 1 张将不是整笔批次（最早一笔是 2 张，可为 2 张、3 张或 4 张），请先删除该到期作废");
        // the later one can be deleted first, then the earlier one
        okDelete(exp2.get("id").asLong());
        okDelete(exp1);
        assertSummary("20376.69", "18000.00", "2376.69");
    }

    @Test
    @DisplayName("AC8 补录较早的开仓破坏整笔批次 / 补录到期前平仓使作废超量 / 到期后平仓（Q36）→ 拒绝；什么都不变")
    void ac8_backfillsRejected() {
        scenarioE();
        expireXyz();
        assertThat(rejectedOpen(trade("STO", 2, "XYZ", "2026-11-20", "45", "PUT", "200.00", "2026-11-02T11:00", 100),
                "CLOSE_NOT_WHOLE_LOTS")).isEqualTo(
                "补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张将不是整笔批次（最早一笔是 2 张，可为 2 张、4 张或 5 张），请先删除该到期作废");
        assertThat(rejectedClose(closeBody("BTC", 2, "XYZ", "2026-11-20", "45", "PUT", "50.00", "2026-11-19T10:00"),
                "CLOSE_EXCEEDS_POSITION")).isEqualTo(
                "补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废");
        Full before = full();
        timeFieldError(postClose(closeBody("BTC", 3, "XYZ", "2026-11-20", "45", "PUT", "50.00", "2026-11-23T09:00")),
                before, CLOSES, "到期日（2026-11-20）之后不能平仓");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("AC8 修改作废记录 → 422 EXPIRY_NOT_EDITABLE；请求体不合法先 400；其它账户 / 不存在 → 404")
    void ac8_expiryNotEditable() {
        scenarioE();
        long exp = expireXyz().get("id").asLong();
        assertThat(rejectedEdit(exp, edit(3, "1.00", "2026-11-20T16:00", null, "x"), "EXPIRY_NOT_EDITABLE"))
                .isEqualTo("到期作废不能修改，记错请删除后重新作废");
        Full before = full();
        Resp bad = putTrade(exp, edit(-1, "1.00", "2026-11-20T16:00", null, null));
        assertErrorShape(bad, 400, "VALIDATION_ERROR", TRADES + "/" + exp);
        assertThat(full()).isEqualTo(before);
        Resp missing = putTrade(999999, edit(1, "1.00", "2026-11-20T16:00", null, null));
        assertErrorShape(missing, 404, "NOT_FOUND", TRADES + "/999999");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("作废后录入正好 16:00 的同合约开仓（多头 call 作废后 STO）成为新批次；删除该作废与之冲突 → POSITION_DIRECTION_CONFLICT")
    void openAtCutoffAfterExpiryThenDeleteExpiry() {
        scenarioE();
        long exp = okExpire("QRTV", "2026-11-20", "30", "CALL", 1).get("id").asLong();
        long sto = open("STO", 1, "QRTV", "2026-11-20", "30", "CALL", "5.00", "2026-11-20T16:00", 100);
        JsonNode p = position(QRTV_C);
        assertThat(p.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(sto);
        assertThat(buttons(p)).isEqualTo("false/false/true");
        // same time, later id on top of the list
        assertThat(ids(tradeList("symbol=QRTV&expirationDate=2026-11-20&strike=30")).subList(0, 2))
                .containsExactly(sto, exp);
        assertThat(rejectedDelete(exp, "POSITION_DIRECTION_CONFLICT")).isNotBlank();
        JsonNode exp2 = okExpire("QRTV", "2026-11-20", "30", "CALL", 1);
        assertThat(exp2.get("expiredDirection").asText()).isEqualTo("SHORT");
        assertThat(exp2.get("realizedPnl").asText()).isEqualTo("5.00");
    }

    // ================================================================== AC 9

    @ParameterizedTest(name = "平仓成交 {0} 允许")
    @ValueSource(strings = {"2026-11-20T15:59", "2026-11-20T16:00", "2026-11-20T16:00:00"})
    @DisplayName("AC9 BTC 2 张成交 15:59 / 16:00 允许：平掉 E1，+210.00，释放 9,000.00")
    void ac9_closeUpToCutoffAllowed(String at) {
        scenarioE();
        JsonNode c = okClose(closeBody("BTC", 2, "XYZ", "2026-11-20", "45", "PUT", "30.00", at));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("210.00");
        assertThat(c.get("frozenChange").asText()).isEqualTo("-9000.00");
        assertThat(lotIds(c)).containsExactly(e1);
        assertSummary("20236.69", "4500.00", "15736.69");
    }

    @ParameterizedTest(name = "成交 {0} → 16:00 提示")
    @ValueSource(strings = {"2026-11-20T16:01", "2026-11-20T16:00:01", "2026-11-20T23:59"})
    @DisplayName("AC9 到期日 16:00 之后的平仓 / 开仓 → 400 tradedAtLocal「成交时间不能晚于到期日（2026-11-20）美东 16:00」")
    void ac9_afterCutoffRejected(String at) {
        scenarioE();
        Full before = full();
        timeFieldError(postClose(closeBody("BTC", 2, "XYZ", "2026-11-20", "45", "PUT", "30.00", at)), before, CLOSES,
                TOO_LATE);
        timeFieldError(postTrade(trade("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "50.00", at, 100)), before, TRADES,
                TOO_LATE);
        timeFieldError(postTrade(trade("BTO", 1, "NEWQ", "2026-11-20", "10", "CALL", "5.00", at, 100)), before,
                TRADES, TOO_LATE);
    }

    @Test
    @DisplayName("AC9 STO 正好 16:00 成功（新批次、冻结 4,500.00）；到期日之后平仓仍是 Q36 原提示；开仓到期日之后仍是 FEAT-001 原提示")
    void ac9_openAtCutoffAndOldMessages() {
        scenarioE();
        JsonNode s = okTrade(trade("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "50.00", "2026-11-20T16:00", 100));
        assertThat(s.get("frozenChange").asText()).isEqualTo("4500.00");
        assertThat(s.get("tradedAt").asText()).isEqualTo(CUTOFF);
        assertThat(position(XYZ).get("contracts").asInt()).isEqualTo(4);
        Full before = full();
        timeFieldError(postClose(closeBody("BTC", 3, "XYZ", "2026-11-20", "45", "PUT", "30.00", "2026-11-21T10:00")),
                before, CLOSES, "到期日（2026-11-20）之后不能平仓");
        Resp r = postTrade(trade("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "50.00", "2026-11-21T10:00", 100));
        assertErrorShape(r, 400, "VALIDATION_ERROR", TRADES);
        assertThat(detailFields(r)).containsExactly("expirationDate");
        assertThat(r.body().get("details").get(0).get("message").asText()).isEqualTo("到期日不能早于成交日（2026-11-21）");
        assertThat(full()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC9 修改 E3 成交时间到 16:01 → 400、不写审计；改到 16:00 → 成功、写审计")
    void ac9_editTimeAroundCutoff() {
        scenarioE();
        Full before = full();
        timeFieldError(putTrade(e3, edit(1, "130.00", "2026-11-20T16:01", 100, null)), before, TRADES + "/" + e3,
                TOO_LATE);
        assertThat(audit().get("totalItems").asInt()).isZero();
        JsonNode t = okEdit(e3, edit(1, "130.00", "2026-11-20T16:00", 100, null));
        assertThat(t.get("tradedAt").asText()).isEqualTo(CUTOFF);
        assertThat(t.get("hasAuditLog").asBoolean()).isTrue();
        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).isEqualTo(1);
        assertThat(texts(a.get("items").get(0).get("changes")))
                .containsExactly("成交时间：2026-11-05 10:00 → 2026-11-20 16:00");
    }

    @Test
    @DisplayName("AC9 作废之后录入正好 16:00 的 BTC → NO_POSITION_TO_CLOSE（排在作废之后）")
    void ac9_closeAtCutoffAfterExpiry() {
        scenarioE();
        expireXyz();
        assertThat(rejectedClose(closeBody("BTC", 1, "XYZ", "2026-11-20", "45", "PUT", "10.00", "2026-11-20T16:00"),
                "NO_POSITION_TO_CLOSE")).isEqualTo("该合约（XYZ 2026-11-20 45 P）在成交时没有持仓，无法平仓");
    }

    /** Stores a BTC 2 XYZ (closes E1) at 2026-11-20 16:10 NY, bypassing the Q83 check (pre-FEAT-012 data). */
    long legacyLateBtc() {
        long id = okClose(closeBody("BTC", 2, "XYZ", "2026-11-20", "45", "PUT", "30.00", "2026-11-20T15:50"))
                .get("id").asLong();
        java.time.OffsetDateTime late = java.time.OffsetDateTime.parse("2026-11-20T21:10:00Z");
        assertThat(jdbc.update("UPDATE option_trade SET traded_at = ? WHERE id = ?", late, id)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE cash_ledger_entry SET occurred_at = ? WHERE source_type = 'OPTION_CLOSE' "
                + "AND source_id = ?", late, id)).isEqualTo(1);
        assertThat(getTrade(id).get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:10:00");
        return id;
    }

    @Test
    @DisplayName("AC9 旧的 16:00 之后交易：照常显示、不挡其它写操作；挡住作废（请求张数 3 → EXPIRY_BLOCKED_BY_LATE_TRADE；张数 1 → POSITION_CHANGED 当前 3 张，原检查次序）；改到 15:50 后可作废 E3")
    void ac9_legacyLateTrade() {
        scenarioE();
        long late = legacyLateBtc();
        assertThat(tradeList("").get("items").get(0).get("id").asLong()).isEqualTo(late);
        JsonNode p = position(XYZ);
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(e3);
        assertThat(buttons(p)).as("backend does not pre-judge the conflict").isEqualTo("false/false/true");
        assertThat(p.get("expiryContracts").asInt()).as("holding at the cutoff").isEqualTo(3);
        // other writes are not rejected because of the stored late trade
        okEdit(e2, edit(1, "85.00", "2026-11-04T10:00", 100, null));
        okTrade(trade("STO", 1, "XYZ", "2026-12-18", "45", "PUT", "10.00", "2026-11-23T09:00", 100));

        String msg = "2026-11-20 16:10 的 BTC 2 张晚于到期日美东 16:00，与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它";
        // Requirement op 1 order (confirmed by the user): step 3 "contracts match" before step 4 "late trades".
        // AC9 request: expiryContracts (3, the cutoff quantity the positions page sends) -> passes step 3, step 4 rejects
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 3), "EXPIRY_BLOCKED_BY_LATE_TRADE"))
                .isEqualTo(msg);
        // the current-holding quantity (1) differs from the cutoff quantity -> step 3 rejects first
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 1), "POSITION_CHANGED"))
                .isEqualTo("持仓已变化（当前 3 张），请刷新后重试");

        JsonNode moved = okEdit(late, edit(2, "30.00", "2026-11-20T15:50", null, null));
        assertThat(moved.get("tradedAt").asText()).isEqualTo("2026-11-20T20:50:00Z");
        assertThat(position(XYZ).get("expiryContracts").asInt()).isEqualTo(1);
        JsonNode t = okExpire("XYZ", "2026-11-20", "45", "PUT", 1);
        assertThat(t.get("contracts").asInt()).isEqualTo(1);
        assertThat(lotIds(t)).containsExactly(e3);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("130.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-4500.00");
    }

    @Test
    @DisplayName("AC9 / Q85 旧的 16:00 之后交易只改备注 → 成功、写审计；改时间到 16:05 → 400；删除它后可作废")
    void ac9_legacyLateTradeNoteOnly() {
        scenarioE();
        long late = legacyLateBtc();
        JsonNode t = okEdit(late, edit(2, "30.00", "2026-11-20T16:10", null, "补记"));
        assertThat(t.get("note").asText()).isEqualTo("补记");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:10:00");
        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).isEqualTo(1);
        assertThat(texts(a.get("items").get(0).get("changes"))).containsExactly("备注：— → 补记");
        // also net only (Q85: time unchanged -> no Q83 check)
        okEdit(late, edit(2, "31.00", "2026-11-20T16:10", null, "补记"));
        Full before = full();
        timeFieldError(putTrade(late, edit(2, "31.00", "2026-11-20T16:05", null, "补记")), before,
                TRADES + "/" + late, TOO_LATE);
        okDelete(late);
        JsonNode exp = expireXyz();
        assertThat(exp.get("realizedPnl").asText()).isEqualTo("370.00");
    }

    // ================================================================== AC 10

    @Test
    @DisplayName("AC10 账户隔离：默认账户作废不影响小账户；小账户作废 +100.00；跨账户删除 / 修改 / 查询 → 404")
    void ac10_accounts() {
        long small = newAccount("小账户");
        accountId = String.valueOf(small);
        deposit("5000.00", "2026-11-02T10:00");
        open("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "100.00", "2026-11-04T10:00", 100);
        assertSummary("5100.00", "4500.00", "600.00");

        accountId = "1";
        scenarioE();
        long exp = expireXyz().get("id").asLong();

        accountId = String.valueOf(small);
        JsonNode p = position(XYZ);
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(buttons(p)).isEqualTo("false/false/true");
        assertSummary("5100.00", "4500.00", "600.00");
        assertThat(closed("").get("filteredCount").asInt()).isZero();
        assertThat(dashboard().get("positions").get("expiredUnprocessedCount").asInt()).isEqualTo(1);

        Full before = full();
        assertErrorShape(deleteTrade(exp), 404, "NOT_FOUND", TRADES + "/" + exp);
        assertErrorShape(putTrade(exp, edit(3, "1.00", "2026-11-20T16:00", null, null)), 404, "NOT_FOUND",
                TRADES + "/" + exp);
        assertErrorShape(get(TRADES + "/" + exp), 404, "NOT_FOUND", TRADES + "/" + exp);
        assertThat(full()).isEqualTo(before);

        JsonNode t = okExpire("XYZ", "2026-11-20", "45", "PUT", 1);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("100.00");
        assertSummary("5100.00", "0.00", "5100.00");

        accountId = "1";
        assertSummary("20266.69", "0.00", "20266.69");
        JsonNode c = closed("");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("370.00");
        assertThat(getTrade(exp).get("realizedPnl").asText()).isEqualTo("370.00");
    }

    @Test
    @DisplayName("账户隔离：合约只在另一个账户有持仓 → NO_POSITION_TO_EXPIRE")
    void expireContractOnlyInOtherAccount() {
        scenarioE();
        accountId = String.valueOf(newAccount("空账户"));
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 3), "NO_POSITION_TO_EXPIRE"))
                .isEqualTo("该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废");
    }

    // ================================================================== AC 11

    @Test
    @DisplayName("AC11 冻结按批次相加 −24.70、multiplier 1 的说明；改 G1 张数后作废只覆盖 G1，−24.69，连带变化写入审计")
    void ac11_lotsFrozenAndSideEffect() {
        accountId = String.valueOf(newAccount("QFZ 账户"));
        deposit("100.00", "2026-11-02T10:00");
        long g1 = open("STO", 1, "QFZ", "2026-11-20", "12.345", "PUT", "0.50", "2026-11-03T10:00", 1);
        long g2 = open("STO", 1, "QFZ", "2026-11-20", "12.345", "PUT", "0.50", "2026-11-04T10:00", 1);
        assertSummary("101.00", "24.70", "76.30");

        JsonNode t = okExpire("QFZ", "2026-11-20", "12.345", "PUT", 2);
        long exp = t.get("id").asLong();
        assertThat(t.get("frozenChange").asText()).isEqualTo("-24.70");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("1.00");
        assertThat(t.get("multiplier").asInt()).isEqualTo(1);
        assertSummary("101.00", "0.00", "101.00");
        assertThat(ledgerRowFor("OPTION_EXPIRE", exp).get("description").asText())
                .isEqualTo("到期作废 2 张 QFZ 2026-11-20 12.345 P（multiplier 1）");
        JsonNode row = tradeList("action=EXP").get("items").get(0);
        assertThat(row.get("contracts").asInt()).isEqualTo(2);
        assertThat(row.get("multiplier").asInt()).isEqualTo(1);

        JsonNode g1e = okEdit(g1, edit(2, "0.50", "2026-11-03T10:00", 1, null));
        assertThat(g1e.get("frozenChange").asText()).isEqualTo("24.69");
        JsonNode after = getTrade(exp);
        assertThat(after.get("contracts").asInt()).isEqualTo(2);
        assertThat(lotIds(after)).containsExactly(g1);
        assertThat(after.get("frozenChange").asText()).isEqualTo("-24.69");
        assertThat(after.get("realizedPnl").asText()).isEqualTo("0.50");
        assertThat(ledgerRowFor("OPTION_EXPIRE", exp).get("frozenChange").asText()).isEqualTo("-24.69");
        JsonNode p = position("QFZ 2026-11-20 12.345 P");
        assertThat(p.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("frozenCash").asText()).isEqualTo("12.35");
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(g2);
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(buttons(p)).isEqualTo("false/false/true");
        assertSummary("101.00", "12.35", "88.65");

        JsonNode rec = audit().get("items").get(0);
        assertThat(rec.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(rec.get("objectId").asLong()).isEqualTo(g1);
        assertThat(texts(rec.get("changes"))).contains("张数：1 → 2", "冻结变动：+12.35 → +24.69");
        assertThat(texts(rec.get("sideEffects"))).containsExactly("到期作废 #" + exp + " 冻结变动 −24.70 → −24.69");
        assertThat(rec.get("sideEffects").get(0).get("action").asText()).isEqualTo("EXP");
        assertThat(rec.get("sideEffects").get(0).get("tradeId").asLong()).isEqualTo(exp);
    }

    // ================================================================== AC 12

    @Test
    @DisplayName("AC12 没有持仓 / 重复作废 → NO_POSITION_TO_EXPIRE；张数与页面不一致 → POSITION_CHANGED；按新张数 4 作废 +480.00")
    void ac12_noPositionRepeatAndChanged() {
        scenarioE();
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "50", "PUT", 1), "NO_POSITION_TO_EXPIRE"))
                .isEqualTo("该合约（XYZ 2026-11-20 50 P）没有持仓，无法作废");

        open("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "110.00", "2026-11-19T10:00", 100);
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 3), "POSITION_CHANGED"))
                .isEqualTo("持仓已变化（当前 4 张），请刷新后重试");
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 0), "POSITION_CHANGED"))
                .isEqualTo("持仓已变化（当前 4 张），请刷新后重试");
        JsonNode t = okExpire("XYZ", "2026-11-20", "45", "PUT", 4);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("480.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-18000.00");
        assertThat(t.get("coveredLots")).hasSize(3);
        assertSummary("20376.69", "0.00", "20376.69");

        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 4), "NO_POSITION_TO_EXPIRE"))
                .isEqualTo("该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废");
        assertThat(rejectedExpire(expBody("XYZ", "2026-11-20", "45", "PUT", 3), "NO_POSITION_TO_EXPIRE"))
                .isEqualTo("该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废");
    }

    @Test
    @DisplayName("已全部平仓的合约作废 → NO_POSITION_TO_EXPIRE；时间检查先于持仓检查")
    void fullyClosedAndCheckOrder() {
        scenarioE();
        okClose(closeBody("STC", 1, "QRTV", "2026-11-20", "30", "CALL", "10.00", "2026-11-19T10:00"));
        assertThat(rejectedExpire(expBody("QRTV", "2026-11-20", "30", "CALL", 1), "NO_POSITION_TO_EXPIRE"))
                .isEqualTo("该合约（QRTV 2026-11-20 30 C）没有持仓，无法作废");
        // a contract that has not expired yet and has no position: 17:30 check first
        assertThat(rejectedExpire(expBody("NOPQ", "2026-12-18", "10", "CALL", 1), "EXPIRY_TOO_EARLY"))
                .isEqualTo("到期日（2026-12-18）美东 17:30 起才能作废");
    }

    // ================================================================== request validation

    @ParameterizedTest(name = "contracts {0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "-1|张数必须是非负整数",
            "1.5|张数必须是非负整数",
            "100001|张数过大",
    })
    @DisplayName("作废请求 contracts 校验：负数 / 小数 → 张数必须是非负整数；过大 → 张数过大")
    void contractsValidation(String value, String message) {
        scenarioE();
        String raw = "{\"symbol\":\"XYZ\",\"expirationDate\":\"2026-11-20\",\"strike\":\"45\",\"optionType\":\"PUT\","
                + "\"contracts\":" + value + "}";
        Full before = full();
        Resp r = postRaw(EXPIRATIONS, raw, MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "VALIDATION_ERROR", EXPIRATIONS);
        assertThat(detailFields(r)).containsExactly("contracts");
        assertThat(r.body().get("details").get(0).get("message").asText()).isEqualTo(message);
        assertThat(full()).isEqualTo(before);
    }

    @Test
    @DisplayName("作废请求校验：缺张数、缺标的 → VALIDATION_ERROR；类型无法解析 → BAD_REQUEST；字段校验先于 17:30 检查")
    void requestValidation() {
        scenarioE();
        expireValidation(expBody("XYZ", "2026-11-20", "45", "PUT", null), "contracts", "张数不能为空");
        ObjectNode noSymbol = expBody("XYZ", "2026-11-20", "45", "PUT", 3);
        noSymbol.remove("symbol");
        expireValidation(noSymbol, "symbol", "标的不能为空");
        expireValidation(expBody("NOPQ", "2026-12-18", "10", "CALL", -1), "contracts", "张数必须是非负整数");
        Full before = full();
        Resp bad = postExpire(expBody("XYZ", "2026-11-20", "45", "P", 3));
        assertErrorShape(bad, 400, "BAD_REQUEST", EXPIRATIONS);
        assertThat(detailFields(bad)).containsExactly("optionType");
        Resp syntax = postRaw(EXPIRATIONS, "{\"symbol\":", MediaType.APPLICATION_JSON);
        assertErrorShape(syntax, 400, "BAD_REQUEST", EXPIRATIONS);
        assertThat(full()).isEqualTo(before);
    }

    @Test
    @DisplayName("作废请求：标的大小写 / 行权价写法规范化；note、tradedAtLocal、netAmount、multiplier、action 一律忽略")
    void normalizationAndIgnoredFields() {
        scenarioE();
        ObjectNode b = expBody(" xyz ", "2026-11-20", "45.000", "PUT", 3);
        b.put("note", "不能填备注");
        b.put("tradedAtLocal", "2026-11-23T09:00");
        b.put("netAmount", "999.00");
        b.put("multiplier", 10);
        b.put("action", "BTC");
        JsonNode t = okExpire(b);
        assertThat(t.get("contractLabel").asText()).isEqualTo(XYZ);
        assertThat(t.get("note").isNull()).isTrue();
        assertThat(t.get("tradedAt").asText()).isEqualTo(CUTOFF);
        assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("action").asText()).isEqualTo("EXP");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("370.00");
    }

    @Test
    @DisplayName("开仓 / 平仓接口收到 action EXP → 400 VALIDATION_ERROR（各自原文案）")
    void expActionOnTradeEndpoints() {
        scenarioE();
        Full before = full();
        Resp o = postTrade(trade("EXP", 1, "XYZ", "2026-11-20", "45", "PUT", "1.00", "2026-11-19T10:00", 100));
        assertErrorShape(o, 400, "VALIDATION_ERROR", TRADES);
        assertThat(o.body().get("details").toString()).contains("本功能只支持开仓（BTO / STO），平仓请使用平仓功能");
        Resp c = postClose(closeBody("EXP", 1, "XYZ", "2026-11-20", "45", "PUT", "1.00", "2026-11-19T10:00"));
        assertErrorShape(c, 400, "VALIDATION_ERROR", CLOSES);
        assertThat(c.body().get("details").toString()).contains("本功能只支持平仓（BTC / STC），开仓请使用开仓功能");
        assertThat(full()).isEqualTo(before);
    }
}
