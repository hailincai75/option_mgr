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
import org.springframework.http.MediaType;

/**
 * FEAT-004 closing trades (BTC / STC) end-to-end over HTTP. "Now" = 2026-11-11 10:00 New York.
 * Every rejection is checked to leave cash, ledger, trades and positions untouched; frozenTotal of the
 * positions endpoint is compared with the cash summary after every write.
 */
class Feat004CloseTradeSystemTest extends OptionTradeSystemTestBase {

    static final String CLOSES = "/api/option-trades/closes";
    static final String POS = "/api/option-positions";

    long l1;
    long l2;

    @Override
    Instant now() {
        return Instant.parse("2026-11-11T15:00:00Z");
    }

    // ------------------------------------------------------------------ helpers

    ObjectNode close(String action, int contracts, String symbol, String exp, String strike, String type,
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
        n.putNull("note");
        return n;
    }

    ObjectNode aaplClose(String action, int contracts, String net, String at) {
        return close(action, contracts, "AAPL", "2026-12-18", "200", "PUT", net, at);
    }

    JsonNode okClose(ObjectNode body) {
        Resp r = postRaw(CLOSES, body.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("POST close %s -> %s", body, r.body()).isEqualTo(201);
        assertFrozenConsistent();
        return r.body();
    }

    /** Snapshot incl. positions and the full ledger (so in-place frozen corrections would be noticed). */
    record Full(State state, JsonNode positions, JsonNode ledger) {
    }

    Full full() {
        return new Full(state(), positions(), ledger(1, 100));
    }

    String rejectedClose(ObjectNode body, String code) {
        Full before = full();
        Resp r = postRaw(CLOSES, body.toString(), MediaType.APPLICATION_JSON);
        assertErrorShape(r, 422, code, CLOSES);
        assertThat(r.body().get("details")).isEmpty();
        assertThat(full()).as("nothing changed after rejected %s", body).isEqualTo(before);
        return r.body().get("message").asText();
    }

    void closeValidation(ObjectNode body, String field, String message) {
        Full before = full();
        Resp r = postRaw(CLOSES, body.toString(), MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "VALIDATION_ERROR", CLOSES);
        List<String> d = new ArrayList<>();
        r.body().get("details").forEach(x -> d.add(x.get("field").asText() + "=" + x.get("message").asText()));
        assertThat(d).containsExactly(field + "=" + message);
        assertThat(full()).isEqualTo(before);
    }

    JsonNode positions() {
        Resp r = get(POS);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
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
        assertThat(positions().get("frozenTotal").asText()).as("positions frozenTotal == cash frozenCash")
                .isEqualTo(summary().get("frozenCash").asText());
    }

    /** "tradeId action contracts netAmount frozen" per covered lot. */
    List<String> covered(JsonNode trade) {
        List<String> out = new ArrayList<>();
        trade.get("coveredLots").forEach(l -> out.add(String.join(" ", l.get("tradeId").asText(),
                l.get("action").asText(), l.get("contracts").asText(), l.get("netAmount").asText(),
                l.get("frozen").asText())));
        return out;
    }

    List<Integer> allowed(JsonNode p) {
        List<Integer> out = new ArrayList<>();
        p.get("allowedCloseContracts").forEach(x -> out.add(x.asInt()));
        return out;
    }

    long open(String action, int contracts, String symbol, String exp, String strike, String type, String net,
              String at, int multiplier) {
        long id = okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, multiplier)).get("id").asLong();
        assertFrozenConsistent();
        return id;
    }

    void scenarioA() {
        deposit("80000.00", "2026-10-26T10:00");
        l1 = open("STO", 2, "AAPL", "2026-12-18", "200", "PUT", "698.70", "2026-11-03T10:30", 100);
        l2 = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-04T11:00", 100);
        assertSummary("81058.70", "60000.00", "21058.70");
        JsonNode p = position("AAPL 2026-12-18 200 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(3);
        assertThat(p.get("lotCount").asInt()).isEqualTo(2);
        assertThat(allowed(p)).containsExactly(2, 3);
        assertThat(p.get("closeAction").asText()).isEqualTo("BTC");
        assertThat(p.get("closable").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC1 BTC 2 张平 L1：响应、流水、持仓、交易列表、按 id 查询")
    void ac1_closeEarliestLot() {
        scenarioA();
        JsonNode c = okClose(aaplClose("BTC", 2, "241.30", "2026-11-05T10:00"));
        assertThat(c.get("action").asText()).isEqualTo("BTC");
        assertThat(c.get("contracts").asInt()).isEqualTo(2);
        assertThat(c.get("multiplier").asInt()).isEqualTo(100);
        assertThat(c.get("netAmount").asText()).isEqualTo("241.30");
        assertThat(c.get("tradedAt").asText()).isEqualTo("2026-11-05T15:00:00Z");
        assertThat(c.get("tradedAtLocal").asText()).isEqualTo("2026-11-05T10:00:00");
        assertThat(c.get("cashChange").asText()).isEqualTo("-241.30");
        assertThat(c.get("frozenChange").asText()).isEqualTo("-40000.00");
        assertThat(c.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 200 P");
        assertThat(covered(c)).containsExactly(l1 + " STO 2 698.70 40000.00");
        JsonNode lot = c.get("coveredLots").get(0);
        assertThat(lot.get("tradedAt").asText()).isEqualTo("2026-11-03T15:30:00Z");
        assertThat(lot.get("tradedAtLocal").asText()).isEqualTo("2026-11-03T10:30:00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("457.40");
        assertThat(getTrade(c.get("id").asLong())).isEqualTo(c);

        assertSummary("80817.40", "20000.00", "60817.40");
        JsonNode row = ledger().get("items").get(0);
        assertThat(row.get("sourceType").asText()).isEqualTo("OPTION_CLOSE");
        assertThat(row.get("sourceId").asLong()).isEqualTo(c.get("id").asLong());
        assertThat(row.get("id").asLong()).isEqualTo(c.get("ledgerEntryId").asLong());
        assertThat(row.get("amount").asText()).isEqualTo("-241.30");
        assertThat(row.get("frozenChange").asText()).isEqualTo("-40000.00");
        assertThat(row.get("description").asText()).isEqualTo("BTC 2 张 AAPL 2026-12-18 200 P");

        JsonNode p = position("AAPL 2026-12-18 200 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("360.00");
        assertThat(p.get("averageNetPerContract").asText()).isEqualTo("360.00");
        assertThat(p.get("frozenCash").asText()).isEqualTo("20000.00");
        assertThat(p.get("lotCount").asInt()).isEqualTo(1);
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(l2);
        assertThat(allowed(p)).containsExactly(1);
        assertThat(positions().get("frozenTotal").asText()).isEqualTo("20000.00");

        JsonNode list = get("/api/option-trades").body();
        assertThat(list.get("items").get(0)).isEqualTo(c);
        JsonNode btc = get("/api/option-trades?action=BTC").body();
        assertThat(btc.get("totalItems").asInt()).isEqualTo(1);
        assertThat(btc.get("items").get(0).get("id").asLong()).isEqualTo(c.get("id").asLong());
        assertThat(get("/api/option-trades?action=STC").body().get("totalItems").asInt()).isZero();
        // opening trades: coveredLots / realizedPnl are null
        JsonNode opening = getTrade(l1);
        assertThat(opening.get("coveredLots").isNull()).isTrue();
        assertThat(opening.get("realizedPnl").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ AC 2, 3, 4

    @Test
    @DisplayName("AC2 BTC 1 张 → CLOSE_NOT_WHOLE_LOTS，什么都不变")
    void ac2_notWholeLots() {
        scenarioA();
        assertThat(rejectedClose(aaplClose("BTC", 1, "120.65", "2026-11-05T10:00"), "CLOSE_NOT_WHOLE_LOTS"))
                .isEqualTo("必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张");
    }

    @Test
    @DisplayName("AC3 BTC 3 张覆盖两批：冻结 −60,000、盈亏 +658.70、持仓清空")
    void ac3_multipleLots() {
        scenarioA();
        JsonNode c = okClose(aaplClose("BTC", 3, "400.00", "2026-11-05T10:00"));
        assertThat(c.get("frozenChange").asText()).isEqualTo("-60000.00");
        assertThat(covered(c)).containsExactly(l1 + " STO 2 698.70 40000.00", l2 + " STO 1 360.00 20000.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("658.70");
        assertSummary("80658.70", "0.00", "80658.70");
        JsonNode p = positions();
        assertThat(p.get("positionCount").asInt()).isZero();
        assertThat(p.get("frozenTotal").asText()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("AC4 超过持仓 / 方向不对 / 没有持仓 / 早于开仓")
    void ac4_rejections() {
        scenarioA();
        assertThat(rejectedClose(aaplClose("BTC", 4, "1.00", "2026-11-05T10:00"), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("平仓 4 张超过当时持仓 3 张");
        assertThat(rejectedClose(aaplClose("STC", 2, "1.00", "2026-11-05T10:00"), "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（3 张），应使用 Buy to Close（BTC）");
        assertThat(rejectedClose(close("BTC", 1, "AAPL", "2026-12-18", "205", "PUT", "1.00", "2026-11-05T10:00"),
                "NO_POSITION_TO_CLOSE")).isEqualTo("该合约（AAPL 2026-12-18 205 P）在成交时没有持仓，无法平仓");
        assertThat(rejectedClose(aaplClose("BTC", 2, "1.00", "2026-11-03T10:00"), "NO_POSITION_TO_CLOSE"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时没有持仓，无法平仓");
        // between L1 and L2: only 2 contracts exist
        assertThat(rejectedClose(aaplClose("BTC", 3, "1.00", "2026-11-03T12:00"), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("平仓 3 张超过当时持仓 2 张");
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("AC5 全部平掉后可以反方向重新开仓")
    void ac5_reopenOppositeDirection() {
        scenarioA();
        okClose(aaplClose("BTC", 2, "241.30", "2026-11-05T10:00"));
        JsonNode c = okClose(aaplClose("BTC", 1, "150.00", "2026-11-06T10:00"));
        assertThat(covered(c)).containsExactly(l2 + " STO 1 360.00 20000.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("210.00");
        assertSummary("80667.40", "0.00", "80667.40");
        assertThat(position("AAPL 2026-12-18 200 P")).isNull();

        open("BTO", 1, "AAPL", "2026-12-18", "200", "PUT", "300.00", "2026-11-06T11:00", 100);
        JsonNode p = position("AAPL 2026-12-18 200 P");
        assertThat(p.get("direction").asText()).isEqualTo("LONG");
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("-300.00");
        assertThat(p.get("closeAction").asText()).isEqualTo("STC");
        assertSummary("80367.40", "0.00", "80367.40");
        // and with a different multiplier after it is closed again
        okClose(aaplClose("STC", 1, "10.00", "2026-11-06T12:00"));
        open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "5.00", "2026-11-06T13:00", 10);
        assertThat(position("AAPL 2026-12-18 200 P").get("multiplier").asInt()).isEqualTo(10);
    }

    // ------------------------------------------------------------------ AC 6 (Q20)

    @Test
    @DisplayName("AC6 先释放冻结再查现金：BTC 500.00 成功，盈亏 −150.65")
    void ac6_releaseBeforeCashCheck() {
        deposit("20000.00", "2026-10-26T10:00");
        open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        assertSummary("20349.35", "20000.00", "349.35");
        JsonNode c = okClose(aaplClose("BTC", 1, "500.00", "2026-11-05T10:00"));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("-150.65");
        assertSummary("19849.35", "0.00", "19849.35");
    }

    @Test
    @DisplayName("AC6 边界：20,349.36 被拒（文案含释放冻结）；20,349.35 成功到 0，盈亏 −20,000.00")
    void ac6_exactBoundary() {
        deposit("20000.00", "2026-10-26T10:00");
        open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        assertThat(rejectedClose(aaplClose("BTC", 1, "20349.36", "2026-11-05T10:00"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 20,349.36，可用 349.35 + 释放冻结 20,000.00 = 20,349.35");
        JsonNode c = okClose(aaplClose("BTC", 1, "20349.35", "2026-11-05T10:00"));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("-20000.00");
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("Q19 补录 BTC 让之后的出金时点变负 → 拒绝（沿用 FEAT-007 文案）")
    void backdatedCloseBreaksLaterWithdrawal() {
        deposit("20000.00", "2026-10-26T10:00");
        open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        withdraw("300.00", "2026-11-07T10:00");
        assertSummary("20049.35", "20000.00", "49.35");
        assertThat(rejectedClose(aaplClose("BTC", 1, "20100.00", "2026-11-05T10:00"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 20,100.00；2026-11-07 10:00 时可用现金将变为 −50.65");
        okClose(aaplClose("BTC", 1, "20049.35", "2026-11-05T10:00"));
        assertSummary("0.00", "0.00", "0.00");
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("AC7 多头 call STC：+830.00、冻结 0、盈亏 +317.35、持仓消失；BTC 被拒")
    void ac7_stcLongCall() {
        deposit("10000.00", "2026-10-26T10:00");
        long bto = open("BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "512.65", "2026-11-03T10:30", 100);
        assertSummary("9487.35", "0.00", "9487.35");
        assertThat(rejectedClose(close("BTC", 1, "AAPL", "2026-12-18", "210", "CALL", "1.00", "2026-11-05T10:00"),
                "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），应使用 Sell to Close（STC）");
        JsonNode c = okClose(close("STC", 1, "AAPL", "2026-12-18", "210", "CALL", "830.00", "2026-11-05T10:00"));
        assertThat(c.get("cashChange").asText()).isEqualTo("830.00");
        assertThat(c.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(covered(c)).containsExactly(bto + " BTO 1 -512.65 0.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("317.35");
        assertSummary("10317.35", "0.00", "10317.35");
        assertThat(positions().get("positionCount").asInt()).isZero();
        assertThat(ledgerRowFor("OPTION_CLOSE", c.get("id").asLong()).get("description").asText())
                .isEqualTo("STC 1 张 AAPL 2026-12-18 210 C");
        assertThat(get("/api/option-trades?action=STC").body().get("totalItems").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ AC 8 (Q36)

    @Test
    @DisplayName("AC8 已过期：closable=false；11-09 平仓被拒（成交时间字段）；到期日 16:30 被拒（FEAT-012 Q83）、16:00 成功")
    void ac8_expiry() {
        deposit("50000.00", "2026-10-26T10:00");
        open("STO", 1, "TSLA", "2026-11-06", "250", "PUT", "300.00", "2026-11-02T10:00", 100);
        assertSummary("50300.00", "25000.00", "25300.00");
        JsonNode p = position("TSLA 2026-11-06 250 P");
        assertThat(p.get("closable").asBoolean()).isFalse();
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        ObjectNode late = close("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-09T10:00");
        closeValidation(late, "tradedAtLocal", "到期日（2026-11-06）之后不能平仓");
        // FEAT-012 (Q83) tightened Q36: on the expiration date only trades at or before 16:00:00 New York
        closeValidation(close("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-06T16:30"),
                "tradedAtLocal", "成交时间不能晚于到期日（2026-11-06）美东 16:00");
        JsonNode c = okClose(close("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-06T16:00"));
        assertThat(c.get("tradedAt").asText()).isEqualTo("2026-11-06T21:00:00Z");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("290.00");
        assertSummary("50290.00", "0.00", "50290.00");
        assertThat(position("TSLA 2026-11-06 250 P")).isNull();
    }

    @Test
    @DisplayName("Q36/Q83 美东日期边界：到期日 23:59（UTC 已次日）被拒（16:00 提示，FEAT-012）；16:00:00 允许；次日 00:00 拒绝；字段错误先于持仓检查")
    void expiryNewYorkDateBoundary() {
        deposit("50000.00", "2026-10-26T10:00");
        open("STO", 1, "TSLA", "2026-11-06", "250", "PUT", "300.00", "2026-11-02T10:00", 100);
        closeValidation(close("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-07T00:00"),
                "tradedAtLocal", "到期日（2026-11-06）之后不能平仓");
        // no position AND after expiry -> the field error wins
        closeValidation(close("BTC", 1, "NOPE", "2026-11-06", "250", "PUT", "10.00", "2026-11-07T00:00"),
                "tradedAtLocal", "到期日（2026-11-06）之后不能平仓");
        // 23:59 NY is still the expiration date in New York (UTC is already 11-07), so the Q83 16:00 message applies
        closeValidation(close("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-06T23:59"),
                "tradedAtLocal", "成交时间不能晚于到期日（2026-11-06）美东 16:00");
        closeValidation(close("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-06T16:00:01"),
                "tradedAtLocal", "成交时间不能晚于到期日（2026-11-06）美东 16:00");
        JsonNode c = okClose(close("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-06T16:00:00"));
        assertThat(c.get("tradedAt").asText()).isEqualTo("2026-11-06T21:00:00Z");
    }

    // ------------------------------------------------------------------ AC 9 (backend part)

    @Test
    @DisplayName("AC9 今天到期的多头：closable=true、closeAction=STC、可平 [1]；今天平仓成功")
    void ac9_expiresTodayIsClosable() {
        deposit("1000.00", "2026-10-26T10:00");
        open("BTO", 1, "SPY", "2026-11-11", "600", "CALL", "50.00", "2026-11-02T10:00", 100);
        JsonNode p = position("SPY 2026-11-11 600 C");
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(p.get("closable").asBoolean()).isTrue();
        assertThat(p.get("closeAction").asText()).isEqualTo("STC");
        assertThat(allowed(p)).containsExactly(1);
        JsonNode c = okClose(close("STC", 1, "SPY", "2026-11-11", "600", "CALL", "60.00", "2026-11-11T09:30"));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("10.00");
    }

    @Test
    @DisplayName("可平张数：批次 1、2、1 → [1, 3, 4]；平 2 张被拒并列出全部")
    void allowedCountsList() {
        deposit("100000.00", "2026-10-26T10:00");
        open("BTO", 1, "Q", "2026-12-18", "10", "CALL", "1.00", "2026-11-02T10:00", 100);
        open("BTO", 2, "Q", "2026-12-18", "10", "CALL", "2.00", "2026-11-03T10:00", 100);
        open("BTO", 1, "Q", "2026-12-18", "10", "CALL", "1.00", "2026-11-04T10:00", 100);
        assertThat(allowed(position("Q 2026-12-18 10 C"))).containsExactly(1, 3, 4);
        assertThat(rejectedClose(close("STC", 2, "Q", "2026-12-18", "10", "CALL", "1.00", "2026-11-05T10:00"),
                "CLOSE_NOT_WHOLE_LOTS")).isEqualTo("必须一次平掉整笔开仓：最早一笔是 1 张，可平 1 张、3 张或 4 张");
        JsonNode c = okClose(close("STC", 3, "q", "2026-12-18", "10.000", "CALL", "9.00", "2026-11-05T10:00"));
        assertThat(c.get("coveredLots")).hasSize(2);
        assertThat(c.get("realizedPnl").asText()).isEqualTo("6.00");
        assertThat(allowed(position("Q 2026-12-18 10 C"))).containsExactly(1);
    }

    // ------------------------------------------------------------------ AC 10 (Q39) and backfills

    @Test
    @DisplayName("AC10 补录 BTC 2 张让之后的平仓没有持仓 → 拒绝；补录 BTC 1 张成功，之后那笔改平 L2、盈亏 +260.00")
    void ac10_backdatedCloseRematch() {
        deposit("80000.00", "2026-10-26T10:00");
        long a = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        long b = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-04T11:00", 100);
        JsonNode later = okClose(aaplClose("BTC", 1, "100.00", "2026-11-06T10:00"));
        assertThat(covered(later)).containsExactly(a + " STO 1 349.35 20000.00");
        assertThat(later.get("realizedPnl").asText()).isEqualTo("249.35");
        assertSummary("80609.35", "20000.00", "60609.35");

        assertThat(rejectedClose(aaplClose("BTC", 2, "200.00", "2026-11-05T10:00"), "NO_POSITION_TO_CLOSE"))
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-06 10:00 的 BTC 1 张将没有持仓可平");

        JsonNode back = okClose(aaplClose("BTC", 1, "120.00", "2026-11-05T10:00"));
        assertThat(covered(back)).containsExactly(a + " STO 1 349.35 20000.00");
        assertThat(back.get("realizedPnl").asText()).isEqualTo("229.35");
        JsonNode re = getTrade(later.get("id").asLong());
        assertThat(covered(re)).containsExactly(b + " STO 1 360.00 20000.00");
        assertThat(re.get("realizedPnl").asText()).isEqualTo("260.00");
        assertSummary("80489.35", "0.00", "80489.35");
        assertThat(position("AAPL 2026-12-18 200 P")).isNull();
        // list shows the re-matched values as well
        for (JsonNode item : get("/api/option-trades?action=BTC").body().get("items")) {
            assertThat(item).isEqualTo(getTrade(item.get("id").asLong()));
        }
    }

    @Test
    @DisplayName("Q39 补录使之后平仓超过持仓 → CLOSE_EXCEEDS_POSITION")
    void backfillMakesLaterCloseExceed() {
        scenarioA();
        okClose(aaplClose("BTC", 3, "400.00", "2026-11-06T10:00"));
        assertThat(rejectedClose(aaplClose("BTC", 2, "100.00", "2026-11-05T10:00"), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-06 10:00 的 BTC 3 张将超过当时持仓 1 张");
    }

    @Test
    @DisplayName("Q27 补录较早开仓使之后的平仓不再是整笔批次 → CLOSE_NOT_WHOLE_LOTS（开仓接口）")
    void backdatedOpenBreaksLaterClose() {
        scenarioA();
        okClose(aaplClose("BTC", 2, "241.30", "2026-11-05T10:00"));
        State before = state();
        Resp r = postTrade(trade("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "100.00", "2026-11-02T10:00", 100));
        assertErrorShape(r, 422, "CLOSE_NOT_WHOLE_LOTS", TRADES);
        assertThat(r.body().get("message").asText())
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将不是整笔开仓（最早一笔是 1 张，可平 1 张、3 张或 4 张）");
        assertThat(state()).isEqualTo(before);
        // an opposite-direction backfill before the first lot conflicts with the later STO (FEAT-001 rule)
        Resp conflict = postTrade(trade("BTO", 1, "AAPL", "2026-12-18", "200", "PUT", "1.00", "2026-11-02T10:00", 100));
        assertErrorShape(conflict, 422, "POSITION_DIRECTION_CONFLICT", TRADES);
        // a backdated open after the close is fine
        open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "100.00", "2026-11-05T11:00", 100);
    }

    @Test
    @DisplayName("Q28 + Q39：补录平仓使之后那笔改平 2 张批次，释放额 24.70 → 24.69 原地改正，冻结合计一致")
    void frozenCorrectionOnRematch() {
        deposit("1000.00", "2026-10-26T10:00");
        long a = open("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", "2026-11-03T10:00", 1);
        long b = open("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", "2026-11-04T10:00", 1);
        long c = open("STO", 2, "F", "2026-12-18", "12.345", "PUT", "0.50", "2026-11-05T10:00", 1);
        assertSummary("1001.50", "49.39", "952.11");
        ObjectNode f2 = close("BTC", 2, "F", "2026-12-18", "12.345", "PUT", "0.10", "2026-11-07T10:00");
        JsonNode later = okClose(f2);
        assertThat(later.get("frozenChange").asText()).isEqualTo("-24.70");
        assertThat(covered(later)).containsExactly(a + " STO 1 0.50 12.35", b + " STO 1 0.50 12.35");
        assertThat(later.get("realizedPnl").asText()).isEqualTo("0.90");
        assertSummary("1001.40", "24.69", "976.71");

        JsonNode back = okClose(close("BTC", 2, "F", "2026-12-18", "12.345", "PUT", "0.10", "2026-11-06T10:00"));
        assertThat(back.get("frozenChange").asText()).isEqualTo("-24.70");
        JsonNode re = getTrade(later.get("id").asLong());
        assertThat(covered(re)).containsExactly(c + " STO 2 0.50 24.69");
        assertThat(re.get("frozenChange").asText()).isEqualTo("-24.69");
        assertThat(re.get("realizedPnl").asText()).isEqualTo("0.40");
        assertThat(ledgerRowFor("OPTION_CLOSE", later.get("id").asLong()).get("frozenChange").asText())
                .isEqualTo("-24.69");
        assertThat(ledgerRowFor("OPTION_CLOSE", later.get("id").asLong()).get("amount").asText()).isEqualTo("-0.10");
        assertSummary("1001.30", "0.00", "1001.30");
        assertThat(positions().get("positionCount").asInt()).isZero();
    }

    // ------------------------------------------------------------------ ordering & misc

    @Test
    @DisplayName("同一时间：先录入的开仓可被同一时间后录入的平仓平掉；平仓先录入则没有持仓")
    void sameInstant() {
        deposit("100000.00", "2026-10-26T10:00");
        assertThat(rejectedClose(aaplClose("BTC", 1, "1.00", "2026-11-03T10:30"), "NO_POSITION_TO_CLOSE"))
                .contains("在成交时没有持仓");
        open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        JsonNode c = okClose(aaplClose("BTC", 1, "1.00", "2026-11-03T10:30"));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("348.35");
    }

    @Test
    @DisplayName("AC11 multiplier 10：释放 2,000.00，说明带 multiplier，盈亏 +24.35；请求里的 multiplier 被忽略")
    void ac11_multiplier10() {
        deposit("5000.00", "2026-10-26T10:00");
        open("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-03T10:30", 10);
        assertSummary("5034.35", "2000.00", "3034.35");
        ObjectNode body = close("BTC", 1, "XYZ", "2026-12-18", "200", "PUT", "10.00", "2026-11-05T10:00");
        body.put("multiplier", 100);
        JsonNode c = okClose(body);
        assertThat(c.get("multiplier").asInt()).isEqualTo(10);
        assertThat(c.get("frozenChange").asText()).isEqualTo("-2000.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("24.35");
        assertThat(ledgerRowFor("OPTION_CLOSE", c.get("id").asLong()).get("description").asText())
                .isEqualTo("BTC 1 张 XYZ 2026-12-18 200 P（multiplier 10）");
        assertSummary("5024.35", "0.00", "5024.35");
    }

    @ParameterizedTest(name = "AC11 {0} = {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "contracts|0|张数必须是正整数",
            "contracts|-1|张数必须是正整数",
            "contracts|1.5|张数必须是正整数",
            "netAmount|\"0\"|净额必须大于 0",
            "netAmount|\"-10.00\"|净额必须大于 0",
            "netAmount|\"241.305\"|净额最多 2 位小数",
            "tradedAtLocal|\"2026-11-11T10:01\"|成交时间不能晚于当前时间",
            "action|\"STO\"|本功能只支持平仓（BTC / STC），开仓请使用开仓功能",
            "action|\"BTO\"|本功能只支持平仓（BTC / STC），开仓请使用开仓功能",
    })
    void ac11_fieldValidation(String field, String raw, String message) throws Exception {
        scenarioA();
        ObjectNode b = aaplClose("BTC", 2, "241.30", "2026-11-05T10:00");
        b.set(field, json.readTree(raw));
        closeValidation(b, field, message);
    }

    @Test
    @DisplayName("AC11 备注 501 字符被拒；开仓接口仍拒绝 BTC/STC")
    void noteAndOpenEndpoint() {
        scenarioA();
        ObjectNode b = aaplClose("BTC", 2, "241.30", "2026-11-05T10:00");
        b.put("note", "x".repeat(501));
        closeValidation(b, "note", "备注最多 500 个字符");
        validationError(trade("BTC", 2, "AAPL", "2026-12-18", "200", "PUT", "1.00", "2026-11-05T10:00", 100),
                "action", "本功能只支持开仓（BTO / STO），平仓请使用平仓功能");
        b.put("note", "平仓备注");
        JsonNode c = okClose(b);
        assertThat(c.get("note").asText()).isEqualTo("平仓备注");
        assertThat(ledgerRowFor("OPTION_CLOSE", c.get("id").asLong()).get("remark").asText()).isEqualTo("平仓备注");
    }

    @Test
    @DisplayName("读接口：positions/汇总多次查询结果一致；POST /api/option-positions 仍 405")
    void readOnlyEndpointsUnaffected() {
        scenarioA();
        okClose(aaplClose("BTC", 2, "241.30", "2026-11-05T10:00"));
        JsonNode p = positions();
        JsonNode s = summary();
        assertThat(positions()).isEqualTo(p);
        assertThat(summary()).isEqualTo(s);
        assertErrorShape(postRaw(POS, "{}", MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", POS);
    }
}
