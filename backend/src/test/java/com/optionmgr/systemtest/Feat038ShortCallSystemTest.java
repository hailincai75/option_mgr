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
 * FEAT-038 STO call allowed without freezing cash (Q16 superseded), end-to-end over HTTP.
 * "Now" = 2026-11-12 10:00 New York. Scenario IXQZ is a fictional covered-call roll; every write is followed by a
 * consistency check positions.frozenTotal == cash frozenCash.
 */
class Feat038ShortCallSystemTest extends OptionTradeSystemTestBase {

    static final String CLOSES = "/api/option-trades/closes";
    final Map<String, Long> ids = new LinkedHashMap<>();
    final Map<Long, String> labels = new LinkedHashMap<>();

    @Override
    Instant now() {
        return Instant.parse("2026-11-12T15:00:00Z");
    }

    // ------------------------------------------------------------------ helpers

    JsonNode openOk(String label, String action, int contracts, String symbol, String exp, String strike, String type,
                    String net, String at, int multiplier) {
        JsonNode t = okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, multiplier));
        remember(label, t);
        assertFrozenConsistent();
        return t;
    }

    void remember(String label, JsonNode t) {
        ids.put(label, t.get("id").asLong());
        labels.put(t.get("id").asLong(), label);
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

    Resp close(ObjectNode body) {
        return postRaw(CLOSES, body.toString(), MediaType.APPLICATION_JSON);
    }

    JsonNode closeOk(String label, ObjectNode body) {
        Resp r = close(body);
        assertThat(r.status()).as("close %s", r.body()).isEqualTo(201);
        remember(label, r.body());
        assertFrozenConsistent();
        return r.body();
    }

    String closeRejected(ObjectNode body, String code) {
        State before = state();
        Resp r = close(body);
        assertErrorShape(r, 422, code, CLOSES);
        assertThat(state()).isEqualTo(before);
        return r.body().get("message").asText();
    }

    JsonNode positions() {
        return get("/api/option-positions").body();
    }

    JsonNode position(String label) {
        for (JsonNode p : positions().get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    void assertFrozenConsistent() {
        assertThat(positions().get("frozenTotal").asText()).isEqualTo(summary().get("frozenCash").asText());
    }

    ObjectNode ixqz(String action, String exp, String strike, String net, String at) {
        return trade(action, 1, "IXQZ", exp, strike, "CALL", net, at, 100);
    }

    void ixqzUpTo3() {
        deposit("10944.65", "2026-11-09T09:00");
        openOk("#1", "STO", 1, "IXQZ", "2026-11-20", "7.5", "CALL", "14.34", "2026-11-09T13:40", 100);
        openOk("#2", "STO", 1, "IXQZ", "2026-12-18", "9", "CALL", "18.34", "2026-11-10T12:43", 100);
        openOk("#3", "STO", 1, "IXQZ", "2026-12-18", "10", "CALL", "20.34", "2026-11-11T10:26", 100);
    }

    void roll4a() {
        closeOk("#4a", closeBody("BTC", 1, "IXQZ", "2026-11-20", "7.5", "CALL", "35.01", "2026-11-11T12:21"));
    }

    void roll4b() {
        openOk("#4b", "STO", 1, "IXQZ", "2026-12-18", "9", "CALL", "37.34", "2026-11-11T12:21", 100);
    }

    void scenarioIxqz() {
        ixqzUpTo3();
        roll4a();
        roll4b();
    }

    String row(JsonNode p) {
        return String.join(" ", p.get("direction").asText(), p.get("contracts").asText(),
                p.get("openingNetTotal").asText(), p.get("averageNetPerContract").asText(), p.get("frozenCash").asText(),
                p.get("dte").asText(), p.get("lotCount").asText());
    }

    List<String> lots(JsonNode p) {
        List<String> out = new ArrayList<>();
        p.get("lots").forEach(l -> out.add(labels.get(l.get("tradeId").asLong()) + " " + l.get("tradedAtLocal").asText()
                + " " + l.get("remainingNetAmount").asText() + " " + l.get("remainingFrozen").asText()));
        return out;
    }

    /** Same numbers for both roll orders (AC 2 / AC 3). */
    void assertIxqzResult() {
        assertSummary("11000.00", "0.00", "11000.00");
        JsonNode pos = positions();
        assertThat(pos.get("positionCount").asInt()).isEqualTo(2);
        assertThat(pos.get("frozenTotal").asText()).isEqualTo("0.00");
        List<String> order = new ArrayList<>();
        pos.get("positions").forEach(p -> order.add(p.get("contractLabel").asText()));
        assertThat(order).containsExactly("IXQZ 2026-12-18 9 C", "IXQZ 2026-12-18 10 C");
        JsonNode nine = position("IXQZ 2026-12-18 9 C");
        assertThat(row(nine)).isEqualTo("SHORT 2 55.68 27.84 0.00 36 2");
        assertThat(nine.get("optionType").asText()).isEqualTo("CALL");
        assertThat(nine.get("closeAction").asText()).isEqualTo("BTC");
        List<Integer> allowed = new ArrayList<>();
        nine.get("allowedCloseContracts").forEach(x -> allowed.add(x.asInt()));
        assertThat(allowed).containsExactly(1, 2);
        assertThat(nine.get("closable").asBoolean()).isTrue();
        assertThat(lots(nine)).containsExactly(
                "#2 2026-11-10T12:43:00 18.34 0.00", "#4b 2026-11-11T12:21:00 37.34 0.00");
        assertThat(row(position("IXQZ 2026-12-18 10 C"))).isEqualTo("SHORT 1 20.34 20.34 0.00 36 1");
        assertThat(position("IXQZ 2026-11-20 7.5 C")).isNull();

        JsonNode closed = get("/api/closed-trades").body();
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("-20.67");
        JsonNode c = closed.get("items").get(0);
        assertThat(labels.get(c.get("id").asLong())).isEqualTo("#4a");
        assertThat(c.get("tradedAtLocal").asText()).isEqualTo("2026-11-11T12:21:00");
        assertThat(c.get("closedDirection").asText()).isEqualTo("SHORT");
        assertThat(c.get("openingNetTotal").asText()).isEqualTo("14.34");
        assertThat(c.get("closeNetAmount").asText()).isEqualTo("-35.01");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("-20.67");
        assertThat(c.get("coveredLots").get(0).get("frozen").asText()).isEqualTo("0.00");
        assertThat(getTrade(c.get("id").asLong()).get("frozenChange").asText()).isEqualTo("0.00");

        JsonNode sum = get("/api/pnl-summary").body();
        assertThat(sum.get("bySymbol").get(0).get("symbol").asText()).isEqualTo("IXQZ");
        assertThat(sum.get("bySymbol").get(0).get("realizedPnl").asText()).isEqualTo("-20.67");
        assertThat(sum.get("byMonth").get(0).get("month").asText()).isEqualTo("2026-11");
        assertThat(sum.get("byMonth").get(0).get("closedCount").asInt()).isEqualTo(1);

        JsonNode d = get("/api/dashboard").body();
        assertThat(d.get("cash").get("totalCash").asText()).isEqualTo("11000.00");
        assertThat(d.get("cash").get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("-20.67");
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("-20.67");
        assertThat(d.get("positions").get("positionCount").asInt()).isEqualTo(2);
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo("0.00");

        JsonNode list = get(TRADES).body();
        assertThat(list.get("totalItems").asInt()).isEqualTo(5);
        list.get("items").forEach(i -> assertThat(i.get("frozenChange").asText()).isEqualTo("0.00"));
        for (JsonNode e : ledger(1, 100).get("items")) {
            assertThat(e.get("frozenChange").asText()).isEqualTo("0.00");
        }
    }

    List<String> listOrder() {
        List<String> out = new ArrayList<>();
        get(TRADES).body().get("items").forEach(i -> out.add(labels.get(i.get("id").asLong())));
        return out;
    }

    // ------------------------------------------------------------------ AC 1, 2, 3

    @Test
    @DisplayName("AC1 #1 STO call：现金 +14.34、冻结 0.00；汇总 10,958.99/0/10,958.99；流水说明")
    void ac1_firstShortCall() {
        deposit("10944.65", "2026-11-09T09:00");
        JsonNode t = openOk("#1", "STO", 1, "IXQZ", "2026-11-20", "7.5", "CALL", "14.34", "2026-11-09T13:40", 100);
        assertThat(t.get("cashChange").asText()).isEqualTo("14.34");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(t.get("contractLabel").asText()).isEqualTo("IXQZ 2026-11-20 7.5 C");
        assertSummary("10958.99", "0.00", "10958.99");
        JsonNode row = ledgerRowFor("OPTION_OPEN", t.get("id").asLong());
        assertThat(row.get("amount").asText()).isEqualTo("14.34");
        assertThat(row.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(row.get("description").asText()).isEqualTo("STO 1 张 IXQZ 2026-11-20 7.5 C");
    }

    @Test
    @DisplayName("AC2+AC3 场景 IXQZ（先 4a 后 4b）：15,000/0/15,000；持仓 9 C 2 张 +55.68 / 10 C；已平仓 −20.67；汇总/首页；列表 #4b 在上")
    void ac2_ixqzRollCloseFirst() {
        scenarioIxqz();
        assertIxqzResult();
        assertThat(listOrder()).containsExactly("#4b", "#4a", "#3", "#2", "#1");
    }

    @Test
    @DisplayName("AC3 同一时刻展期反过来（先 4b 后 4a）：都成功、数字相同；列表 #4a 在 #4b 上")
    void ac3_ixqzRollOpenFirst() {
        ixqzUpTo3();
        roll4b();
        roll4a();
        assertIxqzResult();
        assertThat(listOrder()).containsExactly("#4a", "#4b", "#3", "#2", "#1");
    }

    // ------------------------------------------------------------------ AC 4, 5

    @Test
    @DisplayName("AC4+AC5 零现金卖 call 25.00；BTC 30.00 被拒（无释放冻结字样）；20.00 成功 +5.00、5/0/5")
    void ac4and5_zeroCashCallThenBtc() {
        assertSummary("0.00", "0.00", "0.00");
        openOk("x", "STO", 1, "XYZ", "2026-12-18", "50", "CALL", "25.00", "2026-11-10T10:00", 100);
        assertSummary("25.00", "0.00", "25.00");
        String msg = closeRejected(closeBody("BTC", 1, "XYZ", "2026-12-18", "50", "CALL", "30.00", "2026-11-11T10:00"),
                "CASH_INSUFFICIENT");
        assertThat(msg).isEqualTo("可用现金不足：需要 30.00，可用 25.00");
        assertThat(msg).doesNotContain("释放冻结");
        JsonNode c = closeOk("xc", closeBody("BTC", 1, "XYZ", "2026-12-18", "50", "CALL", "20.00", "2026-11-11T10:00"));
        assertThat(c.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("5.00");
        assertSummary("5.00", "0.00", "5.00");
        assertThat(positions().get("positionCount").asInt()).isZero();
    }

    @Test
    @DisplayName("BTC 平空头 call 的 Q19：补录 BTC 让之后的出金时点变负 → 拒绝（FEAT-007 文案）")
    void btcShortCallLaterPointNegative() {
        openOk("x", "STO", 1, "XYZ", "2026-12-18", "50", "CALL", "25.00", "2026-11-10T10:00", 100);
        withdraw("20.00", "2026-11-12T09:00");
        assertThat(closeRejected(closeBody("BTC", 1, "XYZ", "2026-12-18", "50", "CALL", "10.00", "2026-11-11T10:00"),
                "CASH_INSUFFICIENT")).isEqualTo("可用现金不足：需要 10.00；2026-11-12 09:00 时可用现金将变为 −5.00");
        closeOk("xc", closeBody("BTC", 1, "XYZ", "2026-12-18", "50", "CALL", "5.00", "2026-11-11T10:00"));
        assertSummary("0.00", "0.00", "0.00");
    }

    // ------------------------------------------------------------------ AC 6, 7

    @Test
    @DisplayName("AC6 多头 call 不变：487.35；STO 同合约仍按 Q7 拒绝（提示 STC）；STC 830 → +317.35")
    void ac6_longCallUnchanged() {
        deposit("1000.00", "2026-11-02T10:00");
        openOk("b", "BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "512.65", "2026-11-03T10:30", 100);
        assertSummary("487.35", "0.00", "487.35");
        State before = state();
        Resp r = postTrade(aapl("STO", "210", "CALL", "100.00", "2026-11-04T10:00"));
        assertErrorShape(r, 422, "POSITION_DIRECTION_CONFLICT", TRADES);
        assertThat(r.body().get("message").asText())
                .isEqualTo("该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）");
        assertThat(state()).isEqualTo(before);
        JsonNode c = closeOk("s", closeBody("STC", 1, "AAPL", "2026-12-18", "210", "CALL", "830.00", "2026-11-05T10:00"));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("317.35");
    }

    @Test
    @DisplayName("AC7 put 与 call 共存：25,469.35 / 20,000.00 / 5,469.35；冻结只来自 put；首页冻结 20,000")
    void ac7_putAndCall() {
        deposit("25000.00", "2026-11-02T10:00");
        openOk("p", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        // the call's notional (25,000) is far above the available cash (5,349.35) - still no check
        JsonNode c = openOk("c", "STO", 1, "AAPL", "2026-12-18", "250", "CALL", "120.00", "2026-11-04T10:00", 100);
        assertThat(c.get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("25469.35", "20000.00", "5469.35");
        assertThat(positions().get("frozenTotal").asText()).isEqualTo("20000.00");
        assertThat(position("AAPL 2026-12-18 250 C").get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(get("/api/dashboard").body().get("positions").get("frozenTotal").asText()).isEqualTo("20000.00");
    }

    // ------------------------------------------------------------------ AC 8 (edit / delete)

    @Test
    @DisplayName("AC8 修改 #3 净额 → 11,001.00 + 审计；删除 #1 被拒（被 #4a 平掉）；删除 #4a → 7.5 C 恢复、已平仓为空、11,035.01")
    void ac8_editDelete() {
        scenarioIxqz();
        ObjectNode e = json.createObjectNode();
        e.put("contracts", 1);
        e.put("netAmount", "21.34");
        e.put("tradedAtLocal", "2026-11-11T10:26");
        e.put("multiplier", 100);
        assertThat(exchange(HttpMethod.PUT, TRADES + "/" + ids.get("#3"), e.toString(), MediaType.APPLICATION_JSON)
                .status()).isEqualTo(200);
        assertSummary("11001.00", "0.00", "11001.00");
        JsonNode a = get("/api/audit-log").body().get("items").get(0);
        assertThat(a.get("changes").get(0).get("text").asText()).isEqualTo("净额：20.34 → 21.34");
        assertThat(a.get("changes")).hasSize(1);
        // back to the original state of the scenario
        e.put("netAmount", "20.34");
        assertThat(exchange(HttpMethod.PUT, TRADES + "/" + ids.get("#3"), e.toString(), MediaType.APPLICATION_JSON)
                .status()).isEqualTo(200);

        State before = state();
        Resp del1 = exchange(HttpMethod.DELETE, TRADES + "/" + ids.get("#1"), null, MediaType.APPLICATION_JSON);
        assertThat(del1.status()).isEqualTo(422);
        assertThat(del1.body().get("message").asText())
                .startsWith("该开仓被 2026-11-11 12:21 的 BTC 1 张平掉")
                .endsWith("请先修改或删除那笔平仓");
        assertThat(state()).isEqualTo(before);

        assertThat(exchange(HttpMethod.DELETE, TRADES + "/" + ids.get("#4a"), null, MediaType.APPLICATION_JSON)
                .status()).isEqualTo(204);
        assertFrozenConsistent();
        assertThat(row(position("IXQZ 2026-11-20 7.5 C"))).isEqualTo("SHORT 1 14.34 14.34 0.00 8 1");
        assertThat(get("/api/closed-trades").body().get("filteredCount").asInt()).isZero();
        assertSummary("11035.01", "0.00", "11035.01");
    }

    @Test
    @DisplayName("修改空头 call 不做 Q18：零现金下把张数 1 → 5、改 multiplier、改时间都允许，冻结仍为 0")
    void editShortCallSkipsQ18() {
        JsonNode t = openOk("x", "STO", 1, "XYZ", "2026-12-18", "50", "CALL", "25.00", "2026-11-10T10:00", 100);
        ObjectNode e = json.createObjectNode();
        e.put("contracts", 5);
        e.put("netAmount", "125.00");
        e.put("tradedAtLocal", "2026-11-10T11:00");
        e.put("multiplier", 10);
        Resp r = exchange(HttpMethod.PUT, TRADES + "/" + t.get("id").asLong(), e.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.body().get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(r.body().get("contracts").asInt()).isEqualTo(5);
        assertSummary("125.00", "0.00", "125.00");
        assertFrozenConsistent();
    }

    // ------------------------------------------------------------------ AC 9, 10

    @Test
    @DisplayName("AC9 按账户：B 账户看不到 IXQZ 持仓/盈亏，B 中 BTC 9 C 没有持仓")
    void ac9_perAccount() {
        scenarioIxqz();
        Resp acc = postRaw("/api/accounts", "{\"name\":\"B\"}", MediaType.APPLICATION_JSON);
        assertThat(acc.status()).isEqualTo(201);
        accountId = acc.body().get("id").asText();
        assertThat(positions().get("positionCount").asInt()).isZero();
        assertThat(get("/api/closed-trades").body().get("filteredCount").asInt()).isZero();
        assertThat(closeRejected(closeBody("BTC", 1, "IXQZ", "2026-12-18", "9", "CALL", "1.00", "2026-11-11T13:00"),
                "NO_POSITION_TO_CLOSE")).isEqualTo("该合约（IXQZ 2026-12-18 9 C）在成交时没有持仓，无法平仓");
        // and B can sell the same call itself with zero cash
        openOk("b1", "STO", 1, "IXQZ", "2026-12-18", "9", "CALL", "1.00", "2026-11-11T13:00", 100);
        accountId = "1";
        assertIxqzResult();
    }

    @Test
    @DisplayName("AC10 Q7 / Q23 / Q21 对 call 仍生效：BTO 9 C 冲突；multiplier 10 不一致；BTC 1 张平最早批次 #2；平 2 张覆盖两批")
    void ac10_rulesStillApply() {
        scenarioIxqz();
        State before = state();
        Resp bto = postTrade(ixqz("BTO", "2026-12-18", "9", "1.00", "2026-11-11T13:00"));
        assertErrorShape(bto, 422, "POSITION_DIRECTION_CONFLICT", TRADES);
        assertThat(bto.body().get("message").asText())
                .isEqualTo("该合约（IXQZ 2026-12-18 9 C）在成交时为空头持仓（2 张），不能买入开仓；如要平仓请使用 Buy to Close（BTC）");
        Resp mult = postTrade(trade("STO", 1, "IXQZ", "2026-12-18", "9", "CALL", "1.00", "2026-11-11T13:00", 10));
        assertErrorShape(mult, 422, "MULTIPLIER_MISMATCH", TRADES);
        assertThat(mult.body().get("message").asText()).contains("multiplier 为 100");
        assertThat(state()).isEqualTo(before);

        assertThat(closeRejected(closeBody("BTC", 3, "IXQZ", "2026-12-18", "9", "CALL", "1.00", "2026-11-11T13:00"),
                "CLOSE_EXCEEDS_POSITION")).isEqualTo("平仓 3 张超过当时持仓 2 张");
        JsonNode c = closeOk("c9", closeBody("BTC", 1, "IXQZ", "2026-12-18", "9", "CALL", "10.00", "2026-11-11T13:00"));
        assertThat(labels.get(c.get("coveredLots").get(0).get("tradeId").asLong())).isEqualTo("#2");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("8.34");
        assertThat(c.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(lots(position("IXQZ 2026-12-18 9 C"))).containsExactly("#4b 2026-11-11T12:21:00 37.34 0.00");
        assertSummary("10990.00", "0.00", "10990.00");
    }
}
