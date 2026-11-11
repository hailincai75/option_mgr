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
 * FEAT-003 option positions end-to-end over HTTP. "Today" = 2026-11-11 (clock 2026-11-11 10:00 New York);
 * single tests move the clock for DTE boundaries.
 */
class Feat003OptionPositionsSystemTest extends OptionTradeSystemTestBase {

    static final String POS = "/api/option-positions";
    static final Instant TODAY_10AM = Instant.parse("2026-11-11T15:00:00Z");

    final List<Long> t = new ArrayList<>(); // t.get(0) = T1 id ...

    @Override
    Instant now() {
        return TODAY_10AM;
    }

    void baseline() {
        deposit("100000.00", "2026-10-26T10:00");
        t.add(add("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100, "第一笔 CSP"));
        t.add(add("BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "512.65", "2026-11-03T10:30", 100, null));
        t.add(add("STO", 2, "F", "2026-12-18", "12.5", "PUT", "48.70", "2026-11-02T15:59", 100, null));
        t.add(add("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-04T11:00", 100, "加仓"));
        t.add(add("BTO", 1, "MSFT", "2027-01-15", "450", "CALL", "900.00", "2026-11-03T23:30", 100, null));
        t.add(add("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-10-30T10:00", 10, null));
        assertSummary("99379.75", "44500.00", "54879.75");
    }

    long add(String action, int contracts, String symbol, String exp, String strike, String type, String net,
             String at, int multiplier, String note) {
        ObjectNode b = trade(action, contracts, symbol, exp, strike, type, net, at, multiplier);
        if (note != null) {
            b.put("note", note);
        }
        return okTrade(b).get("id").asLong();
    }

    JsonNode positions() {
        Resp r = get(POS);
        assertThat(r.status()).isEqualTo(200);
        JsonNode body = r.body();
        assertThat(body.get("positionCount").asInt()).isEqualTo(body.get("positions").size());
        return body;
    }

    List<String> order(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("positions").forEach(p -> out.add(p.get("contractLabel").asText()));
        return out;
    }

    JsonNode position(JsonNode body, String label) {
        for (JsonNode p : body.get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    /** "direction contracts multiplier openingNetTotal average frozen expiration dte status daysPast lotCount". */
    String row(JsonNode p) {
        return String.join(" ", p.get("direction").asText(), p.get("contracts").asText(), p.get("multiplier").asText(),
                p.get("openingNetTotal").asText(), p.get("averageNetPerContract").asText(), p.get("frozenCash").asText(),
                p.get("expirationDate").asText(), p.get("dte").asText(), p.get("expirationStatus").asText(),
                p.get("daysPastExpiration").asText(), p.get("lotCount").asText());
    }

    /** "tradeId action tradedAt tradedAtLocal orig/rem contracts orig/rem net orig/rem frozen note". */
    List<String> lots(JsonNode p) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : p.get("lots")) {
            out.add(String.join(" ", l.get("tradeId").asText(), l.get("action").asText(), l.get("tradedAt").asText(),
                    l.get("tradedAtLocal").asText(),
                    l.get("originalContracts").asText() + "/" + l.get("remainingContracts").asText(),
                    l.get("originalNetAmount").asText() + "/" + l.get("remainingNetAmount").asText(),
                    l.get("originalFrozen").asText() + "/" + l.get("remainingFrozen").asText(),
                    l.get("note").isNull() ? "null" : l.get("note").asText()));
        }
        return out;
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC1 基准数据：截至 2026-11-11、5 个持仓、冻结合计 44,500.00 = 现金汇总；顺序与各列")
    void ac1_positionsAndOrder() {
        baseline();
        JsonNode body = positions();
        assertThat(body.get("asOfDate").asText()).isEqualTo("2026-11-11");
        assertThat(body.get("positionCount").asInt()).isEqualTo(5);
        assertThat(body.get("frozenTotal").asText()).isEqualTo("44500.00");
        assertThat(body.get("frozenTotal").asText()).isEqualTo(summary().get("frozenCash").asText());
        assertThat(order(body)).containsExactly("AAPL 2026-12-18 200 P", "AAPL 2026-12-18 210 C",
                "F 2026-12-18 12.5 P", "XYZ 2026-12-18 200 P", "MSFT 2027-01-15 450 C");
        List<String> rows = new ArrayList<>();
        body.get("positions").forEach(p -> rows.add(row(p)));
        assertThat(rows).containsExactly(
                "SHORT 2 100 709.35 354.68 40000.00 2026-12-18 37 ACTIVE 0 2",
                "LONG 1 100 -512.65 -512.65 0.00 2026-12-18 37 ACTIVE 0 1",
                "SHORT 2 100 48.70 24.35 2500.00 2026-12-18 37 ACTIVE 0 1",
                "SHORT 1 10 34.35 34.35 2000.00 2026-12-18 37 ACTIVE 0 1",
                "LONG 1 100 -900.00 -900.00 0.00 2027-01-15 65 ACTIVE 0 1");
        JsonNode f = position(body, "F 2026-12-18 12.5 P");
        assertThat(f.get("symbol").asText()).isEqualTo("F");
        assertThat(f.get("strike").asText()).isEqualTo("12.5");
        assertThat(f.get("optionType").asText()).isEqualTo("PUT");
        assertThat(f.get("contracts").isIntegralNumber()).isTrue();
        assertThat(f.get("openingNetTotal").isTextual()).isTrue();
        assertThat(f.get("dte").isIntegralNumber()).isTrue();
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("AC2 批次：AAPL 200 P 两批 FIFO（T1、T4）；F 一笔 2 张 = 1 批；BTO 批次负净额、冻结 0")
    void ac2_lots() {
        baseline();
        JsonNode body = positions();
        assertThat(lots(position(body, "AAPL 2026-12-18 200 P"))).containsExactly(
                t.get(0) + " STO 2026-11-03T15:30:00Z 2026-11-03T10:30:00 1/1 349.35/349.35 20000.00/20000.00 第一笔 CSP",
                t.get(3) + " STO 2026-11-04T16:00:00Z 2026-11-04T11:00:00 1/1 360.00/360.00 20000.00/20000.00 加仓");
        assertThat(lots(position(body, "F 2026-12-18 12.5 P"))).containsExactly(
                t.get(2) + " STO 2026-11-02T20:59:00Z 2026-11-02T15:59:00 2/2 48.70/48.70 2500.00/2500.00 null");
        assertThat(lots(position(body, "AAPL 2026-12-18 210 C"))).containsExactly(
                t.get(1) + " BTO 2026-11-03T15:30:00Z 2026-11-03T10:30:00 1/1 -512.65/-512.65 0.00/0.00 null");
        assertThat(lots(position(body, "MSFT 2027-01-15 450 C"))).containsExactly(
                t.get(4) + " BTO 2026-11-04T04:30:00Z 2026-11-03T23:30:00 1/1 -900.00/-900.00 0.00/0.00 null");
        assertThat(lots(position(body, "XYZ 2026-12-18 200 P"))).containsExactly(
                t.get(5) + " STO 2026-10-30T14:00:00Z 2026-10-30T10:00:00 1/1 34.35/34.35 2000.00/2000.00 null");
    }

    // ------------------------------------------------------------------ AC 3 (Q22)

    @Test
    @DisplayName("AC3 平均净额四舍五入：KO 200/3 → −66.67；PEP 100/3 → −33.33")
    void ac3_averageRounding() {
        deposit("10000.00", "2026-10-26T10:00");
        add("BTO", 3, "KO", "2026-12-18", "60", "CALL", "200.00", "2026-11-02T10:00", 100, null);
        JsonNode ko = position(positions(), "KO 2026-12-18 60 C");
        assertThat(ko.get("openingNetTotal").asText()).isEqualTo("-200.00");
        assertThat(ko.get("averageNetPerContract").asText()).isEqualTo("-66.67");
        add("BTO", 3, "PEP", "2026-12-18", "150", "CALL", "100.00", "2026-11-02T10:00", 100, null);
        assertThat(position(positions(), "PEP 2026-12-18 150 C").get("averageNetPerContract").asText())
                .isEqualTo("-33.33");
    }

    @Test
    @DisplayName("Q22 半分处向上（HALF_UP，不是银行家舍入）：0.125 → 0.13，−0.125 → −0.13，2.005 → 2.01，0.0033 → 0.00")
    void q22_halfUp() {
        deposit("100000.00", "2026-10-26T10:00");
        add("STO", 2, "A", "2026-12-18", "1", "PUT", "0.25", "2026-11-02T10:00", 100, null);
        add("BTO", 2, "B", "2026-12-18", "1", "CALL", "0.25", "2026-11-02T10:00", 100, null);
        add("STO", 2, "C", "2026-12-18", "1", "PUT", "4.01", "2026-11-02T10:00", 100, null);
        add("STO", 3, "D", "2026-12-18", "1", "PUT", "0.01", "2026-11-02T10:00", 100, null);
        add("STO", 2, "E", "2026-12-18", "1", "PUT", "0.35", "2026-11-02T10:00", 100, null);
        JsonNode body = positions();
        assertThat(position(body, "A 2026-12-18 1 P").get("averageNetPerContract").asText()).isEqualTo("0.13");
        assertThat(position(body, "B 2026-12-18 1 C").get("averageNetPerContract").asText()).isEqualTo("-0.13");
        assertThat(position(body, "C 2026-12-18 1 P").get("averageNetPerContract").asText()).isEqualTo("2.01");
        assertThat(position(body, "D 2026-12-18 1 P").get("averageNetPerContract").asText()).isEqualTo("0.00");
        assertThat(position(body, "E 2026-12-18 1 P").get("averageNetPerContract").asText()).isEqualTo("0.18");
        // totals are not affected by rounding
        assertThat(position(body, "D 2026-12-18 1 P").get("openingNetTotal").asText()).isEqualTo("0.01");
    }

    // ------------------------------------------------------------------ AC 4 (Q28)

    @Test
    @DisplayName("AC4 冻结按批次相加：12.35 + 12.35 = 24.70（不是 24.69）；合计 = 现金汇总冻结")
    void ac4_frozenSumOfLots() {
        deposit("100.00", "2026-10-26T10:00");
        add("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", "2026-11-02T10:00", 1, null);
        add("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", "2026-11-03T10:00", 1, null);
        JsonNode body = positions();
        JsonNode p = position(body, "F 2026-12-18 12.345 P");
        assertThat(row(p)).isEqualTo("SHORT 2 1 1.00 0.50 24.70 2026-12-18 37 ACTIVE 0 2");
        assertThat(body.get("frozenTotal").asText()).isEqualTo("24.70");
        assertThat(summary().get("frozenCash").asText()).isEqualTo("24.70");
        List<String> frozen = new ArrayList<>();
        p.get("lots").forEach(l -> frozen.add(l.get("remainingFrozen").asText()));
        assertThat(frozen).containsExactly("12.35", "12.35");
    }

    // ------------------------------------------------------------------ AC 5 (DTE)

    @Test
    @DisplayName("AC5 DTE 按美东日期：11-12 00:30 NY → 36；UTC 已 11-12 但 NY 11-11 23:30 → 37")
    void ac5_dteNewYorkMidnight() {
        baseline();
        clock.set(Instant.parse("2026-11-12T05:30:00Z")); // 00:30 EST on 11-12
        JsonNode next = positions();
        assertThat(next.get("asOfDate").asText()).isEqualTo("2026-11-12");
        assertThat(position(next, "AAPL 2026-12-18 200 P").get("dte").asInt()).isEqualTo(36);
        assertThat(position(next, "MSFT 2027-01-15 450 C").get("dte").asInt()).isEqualTo(64);

        clock.set(Instant.parse("2026-11-12T04:30:00Z")); // 23:30 EST on 11-11
        JsonNode same = positions();
        assertThat(same.get("asOfDate").asText()).isEqualTo("2026-11-11");
        assertThat(position(same, "AAPL 2026-12-18 200 P").get("dte").asInt()).isEqualTo(37);

        clock.set(Instant.parse("2026-11-12T04:59:59Z")); // 23:59:59 EST
        assertThat(positions().get("asOfDate").asText()).isEqualTo("2026-11-11");
        clock.set(Instant.parse("2026-11-12T05:00:00Z")); // 00:00 EST
        assertThat(positions().get("asOfDate").asText()).isEqualTo("2026-11-12");
    }

    @Test
    @DisplayName("DTE 在夏令时切换日：3-08（23 小时）与 11-01（25 小时）的美东午夜")
    void dteAcrossDstDays() {
        clock.set(Instant.parse("2026-03-01T15:00:00Z"));
        deposit("1000.00", "2026-02-01T10:00");
        add("BTO", 1, "A", "2026-03-09", "1", "CALL", "1.00", "2026-02-02T10:00", 100, null);

        clock.set(Instant.parse("2026-03-09T03:59:00Z")); // 23:59 EDT on 03-08
        JsonNode a = positions();
        assertThat(a.get("asOfDate").asText()).isEqualTo("2026-03-08");
        assertThat(position(a, "A 2026-03-09 1 C").get("dte").asInt()).isEqualTo(1);
        clock.set(Instant.parse("2026-03-09T04:00:00Z")); // 00:00 EDT on 03-09
        JsonNode b = positions();
        assertThat(b.get("asOfDate").asText()).isEqualTo("2026-03-09");
        assertThat(row(position(b, "A 2026-03-09 1 C"))).endsWith("0 EXPIRES_TODAY 0 1");

        clock.set(Instant.parse("2026-11-02T04:59:00Z")); // 23:59 EST on 11-01
        JsonNode c = positions();
        assertThat(c.get("asOfDate").asText()).isEqualTo("2026-11-01");
        assertThat(position(c, "A 2026-03-09 1 C").get("dte").asInt()).isEqualTo(-237);
        clock.set(Instant.parse("2026-11-01T04:30:00Z")); // 00:30 EDT on 11-01
        assertThat(positions().get("asOfDate").asText()).isEqualTo("2026-11-01");
        clock.set(Instant.parse("2026-11-01T03:59:00Z")); // 23:59 EDT on 10-31
        assertThat(positions().get("asOfDate").asText()).isEqualTo("2026-10-31");
    }

    // ------------------------------------------------------------------ AC 6 (Q34)

    @Test
    @DisplayName("AC6 已过期未处理：TSLA −5 已过期 5 天、冻结不释放；SPY 今天到期 0；合计 25,000.00")
    void ac6_expiredAndToday() {
        deposit("50000.00", "2026-10-26T10:00");
        add("STO", 1, "TSLA", "2026-11-06", "250", "PUT", "300.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "SPY", "2026-11-11", "600", "CALL", "50.00", "2026-11-02T10:00", 100, null);
        JsonNode body = positions();
        assertThat(order(body)).containsExactly("TSLA 2026-11-06 250 P", "SPY 2026-11-11 600 C");
        assertThat(row(position(body, "TSLA 2026-11-06 250 P")))
                .isEqualTo("SHORT 1 100 300.00 300.00 25000.00 2026-11-06 -5 EXPIRED 5 1");
        assertThat(row(position(body, "SPY 2026-11-11 600 C")))
                .isEqualTo("LONG 1 100 -50.00 -50.00 0.00 2026-11-11 0 EXPIRES_TODAY 0 1");
        assertThat(body.get("positionCount").asInt()).isEqualTo(2);
        assertThat(body.get("frozenTotal").asText()).isEqualTo("25000.00");
        assertThat(summary().get("frozenCash").asText()).isEqualTo("25000.00");

        // the next NY day SPY is expired by 1
        clock.set(Instant.parse("2026-11-12T05:00:00Z"));
        assertThat(row(position(positions(), "SPY 2026-11-11 600 C"))).endsWith("-1 EXPIRED 1 1");
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("AC7 新开仓后：AAPL 200 P 3 张 +1,049.35 平均 349.78 冻结 60,000 批次 3；NVDA 排在 F 后 XYZ 前")
    void ac7_newTradesReflected() {
        baseline();
        long newId = add("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "340.00", "2026-11-05T10:00", 100, null);
        JsonNode body = positions();
        JsonNode p = position(body, "AAPL 2026-12-18 200 P");
        assertThat(row(p)).isEqualTo("SHORT 3 100 1049.35 349.78 60000.00 2026-12-18 37 ACTIVE 0 3");
        assertThat(p.get("lots").get(2).get("tradeId").asLong()).isEqualTo(newId);
        assertThat(body.get("positionCount").asInt()).isEqualTo(5);
        assertThat(body.get("frozenTotal").asText()).isEqualTo("64500.00");

        add("BTO", 1, "NVDA", "2026-12-18", "150", "CALL", "400.00", "2026-11-05T10:30", 100, null);
        JsonNode after = positions();
        assertThat(order(after)).containsExactly("AAPL 2026-12-18 200 P", "AAPL 2026-12-18 210 C",
                "F 2026-12-18 12.5 P", "NVDA 2026-12-18 150 C", "XYZ 2026-12-18 200 P", "MSFT 2027-01-15 450 C");
        assertThat(row(position(after, "NVDA 2026-12-18 150 C")))
                .isEqualTo("LONG 1 100 -400.00 -400.00 0.00 2026-12-18 37 ACTIVE 0 1");
        assertThat(after.get("positionCount").asInt()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ AC 8, 9, 10

    @Test
    @DisplayName("AC8 持仓的 symbol/expirationDate/strike/optionType 原样作为交易列表参数 → T4、T1")
    void ac8_linkParametersWorkWithTradeList() {
        baseline();
        JsonNode p = position(positions(), "AAPL 2026-12-18 200 P");
        Resp r = get("/api/option-trades?symbol=" + p.get("symbol").asText() + "&expirationDate="
                + p.get("expirationDate").asText() + "&strike=" + p.get("strike").asText() + "&optionType="
                + p.get("optionType").asText());
        assertThat(r.status()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        r.body().get("items").forEach(i -> ids.add(i.get("id").asLong()));
        assertThat(ids).containsExactly(t.get(3), t.get(0));
        // also for a decimal strike
        JsonNode f = position(positions(), "F 2026-12-18 12.5 P");
        Resp rf = get("/api/option-trades?symbol=F&expirationDate=2026-12-18&strike=" + f.get("strike").asText()
                + "&optionType=PUT");
        assertThat(rf.body().get("totalItems").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC9 空状态：只有入金 → 0 个持仓、冻结 0.00、positions []")
    void ac9_empty() {
        JsonNode none = positions();
        assertThat(none.get("asOfDate").asText()).isEqualTo("2026-11-11");
        assertThat(none.get("positionCount").asInt()).isZero();
        assertThat(none.get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(none.get("positions")).isEmpty();
        deposit("100.00", "2026-10-26T10:00");
        assertThat(positions().get("positionCount").asInt()).isZero();
    }

    @Test
    @DisplayName("AC10 只读：多次查询后现金汇总、流水、交易不变；POST/DELETE → 405")
    void ac10_readOnly() {
        baseline();
        JsonNode ledgerBefore = ledger(1, 100);
        JsonNode first = positions();
        for (int i = 0; i < 3; i++) {
            assertThat(positions()).isEqualTo(first);
        }
        assertErrorShape(postRaw(POS, "{}", MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", POS);
        assertErrorShape(exchange(HttpMethod.DELETE, POS, null, MediaType.APPLICATION_JSON), 405,
                "METHOD_NOT_ALLOWED", POS);
        assertSummary("99379.75", "44500.00", "54879.75");
        assertThat(ledger(1, 100)).isEqualTo(ledgerBefore);
        assertThat(tradeCount()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ FIFO & sorting boundaries

    @Test
    @DisplayName("FIFO：补录较早的开仓排第一；同一时间先录入的在前")
    void fifoOrder() {
        deposit("100000.00", "2026-10-26T10:00");
        long a = add("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "1.00", "2026-11-04T10:00", 100, "a");
        long b = add("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "2.00", "2026-11-02T10:00", 100, "b-backfill");
        long c = add("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "3.00", "2026-11-04T10:00", 100, "c-same-time");
        long d = add("STO", 1, "AAPL", "2026-12-18", "200.000", "PUT", "4.00", "2026-11-03T10:00", 100, "d");
        JsonNode p = position(positions(), "AAPL 2026-12-18 200 P");
        List<Long> ids = new ArrayList<>();
        p.get("lots").forEach(l -> ids.add(l.get("tradeId").asLong()));
        assertThat(ids).containsExactly(b, d, a, c);
        assertThat(row(p)).isEqualTo("SHORT 4 100 10.00 2.50 80000.00 2026-12-18 37 ACTIVE 0 4");
    }

    @Test
    @DisplayName("排序：到期日 → 标的（字符编码：数字、点在字母前）→ 行权价数值 → CALL 在 PUT 前")
    void sorting() {
        deposit("100000.00", "2026-10-26T10:00");
        add("BTO", 1, "BRKB", "2026-12-18", "1", "CALL", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "BRK.B", "2026-12-18", "1", "CALL", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "B2", "2026-12-18", "1", "CALL", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "Z", "2026-12-18", "100", "PUT", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "Z", "2026-12-18", "9.5", "PUT", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "Z", "2026-12-18", "10", "PUT", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "Z", "2026-12-18", "10", "CALL", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "ZZ", "2026-11-20", "1", "CALL", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "AA", "2027-01-15", "1", "CALL", "1.00", "2026-11-02T10:00", 100, null);
        add("BTO", 1, "OLD", "2026-11-06", "1", "PUT", "1.00", "2026-11-02T10:00", 100, null);
        assertThat(order(positions())).containsExactly(
                "OLD 2026-11-06 1 P", "ZZ 2026-11-20 1 C",
                "B2 2026-12-18 1 C", "BRK.B 2026-12-18 1 C", "BRKB 2026-12-18 1 C",
                "Z 2026-12-18 9.5 P", "Z 2026-12-18 10 C", "Z 2026-12-18 10 P", "Z 2026-12-18 100 P",
                "AA 2027-01-15 1 C");
    }

    @Test
    @DisplayName("多头 put 冻结 0；multiplier 10 的空头 put 冻结按实际 multiplier")
    void longPutAndMultiplier() {
        deposit("100000.00", "2026-10-26T10:00");
        add("BTO", 1, "Q", "2026-12-18", "50", "PUT", "10.00", "2026-11-02T10:00", 100, null);
        add("STO", 2, "R", "2026-12-18", "50", "PUT", "10.00", "2026-11-02T10:00", 10, null);
        JsonNode body = positions();
        assertThat(row(position(body, "Q 2026-12-18 50 P"))).isEqualTo("LONG 1 100 -10.00 -10.00 0.00 2026-12-18 37 ACTIVE 0 1");
        assertThat(row(position(body, "R 2026-12-18 50 P"))).isEqualTo("SHORT 2 10 10.00 5.00 1000.00 2026-12-18 37 ACTIVE 0 1");
        assertThat(body.get("frozenTotal").asText()).isEqualTo("1000.00");
        assertThat(summary().get("frozenCash").asText()).isEqualTo("1000.00");
    }
}
