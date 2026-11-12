package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** FEAT-008 dashboard and P&L summary end-to-end over HTTP. "Now" = 2026-11-12 10:00 New York. */
class Feat008PnlSummarySystemTest extends OptionTradeSystemTestBase {

    static final String DASH = "/api/dashboard";
    static final String SUM = "/api/pnl-summary";

    final Map<String, Long> ids = new LinkedHashMap<>();

    @Override
    Instant now() {
        return Instant.parse("2026-11-12T15:00:00Z");
    }

    // ------------------------------------------------------------------ helpers

    void open(String label, String action, int contracts, String symbol, String exp, String strike, String type,
              String net, String at, int multiplier) {
        ids.put(label, okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, multiplier)).get("id").asLong());
    }

    void close(String label, String action, int contracts, String symbol, String exp, String strike, String type,
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
        Resp r = postRaw("/api/option-trades/closes", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("close %s", r.body()).isEqualTo(201);
        ids.put(label, r.body().get("id").asLong());
    }

    void octoberTrades() {
        open("O1", "BTO", 1, "TSLA", "2026-12-18", "300", "CALL", "500.00", "2026-10-27T10:00", 100);
        open("O2", "STO", 1, "AAPL", "2026-11-20", "180", "PUT", "150.00", "2026-10-28T10:00", 100);
        close("O3", "STC", 1, "TSLA", "2026-12-18", "300", "CALL", "620.00", "2026-10-30T10:00");
        close("O4", "BTC", 1, "AAPL", "2026-11-20", "180", "PUT", "200.00", "2026-10-31T23:30");
    }

    void scenarioQ() {
        deposit("100000.00", "2026-10-26T10:00");
        octoberTrades();
        open("T1", "STO", 2, "AAPL", "2026-12-18", "200", "PUT", "698.70", "2026-11-02T10:30", 100);
        open("T2", "BTO", 1, "MSFT", "2027-01-15", "450", "CALL", "900.00", "2026-11-02T14:00", 100);
        open("T3", "STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-02T15:00", 10);
        open("T4", "BTO", 2, "AAPL", "2026-12-18", "210", "CALL", "1000.00", "2026-11-03T09:45", 100);
        open("T5", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-03T11:00", 100);
        close("C1", "BTC", 3, "AAPL", "2026-12-18", "200", "PUT", "400.00", "2026-11-05T10:00");
        close("C2", "BTC", 1, "XYZ", "2026-12-18", "200", "PUT", "50.00", "2026-11-06T10:00");
        close("C3", "STC", 1, "MSFT", "2027-01-15", "450", "CALL", "1250.00", "2026-11-06T23:30");
        close("C4", "STC", 2, "AAPL", "2026-12-18", "210", "CALL", "640.00", "2026-11-07T10:00");
        assertSummary("100703.05", "0.00", "100703.05");
    }

    JsonNode dash() {
        Resp r = get(DASH);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode sum(String query) {
        Resp r = get(SUM + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("GET %s -> %s", query, r.body()).isEqualTo(200);
        JsonNode b = r.body();
        // consistency: groups add up to the total, and the total equals the closed-trades list for the same range
        BigDecimal symSum = BigDecimal.ZERO;
        int symCount = 0;
        for (JsonNode s : b.get("bySymbol")) {
            symSum = symSum.add(new BigDecimal(s.get("realizedPnl").asText()));
            symCount += s.get("closedCount").asInt();
        }
        BigDecimal monSum = BigDecimal.ZERO;
        int monCount = 0;
        for (JsonNode m : b.get("byMonth")) {
            monSum = monSum.add(new BigDecimal(m.get("realizedPnl").asText()));
            monCount += m.get("closedCount").asInt();
        }
        BigDecimal total = new BigDecimal(b.get("realizedPnlTotal").asText());
        assertThat(symSum).isEqualByComparingTo(total);
        assertThat(monSum).isEqualByComparingTo(total);
        assertThat(symCount).isEqualTo(b.get("closedCount").asInt());
        assertThat(monCount).isEqualTo(b.get("closedCount").asInt());
        JsonNode closed = get("/api/closed-trades" + (query.isEmpty() ? "" : "?" + query)).body();
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo(b.get("realizedPnlTotal").asText());
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(b.get("closedCount").asInt());
        return b;
    }

    List<String> rows(JsonNode arr, String key) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.get(key).asText() + " " + x.get("closedCount").asInt() + " " + x.get("realizedPnl").asText()));
        return out;
    }

    String pnl(JsonNode o) {
        return o.get("total").asText() + " (" + o.get("closedCount").asInt() + ")";
    }

    void assertDashboardConsistent(JsonNode d) {
        JsonNode cash = summary();
        assertThat(d.get("cash").get("totalCash").asText()).isEqualTo(cash.get("totalCash").asText());
        assertThat(d.get("cash").get("frozenCash").asText()).isEqualTo(cash.get("frozenCash").asText());
        assertThat(d.get("cash").get("availableCash").asText()).isEqualTo(cash.get("availableCash").asText());
        JsonNode pos = get("/api/option-positions").body();
        assertThat(d.get("positions").get("positionCount").asInt()).isEqualTo(pos.get("positionCount").asInt());
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo(pos.get("frozenTotal").asText());
        JsonNode closed = get("/api/closed-trades").body();
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo(closed.get("filteredRealizedPnlTotal").asText());
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(closed.get("filteredCount").asInt());
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC1 首页概览：截至 11-12、现金 101,043.05/44,000/57,043.05、总计 +703.05（6）、本月 +633.05（4）、持仓 3、7 天内 1、已过期 1")
    void ac1_dashboard() {
        scenarioQ();
        open("A", "STO", 1, "TSLA", "2026-11-06", "250", "PUT", "300.00", "2026-11-02T09:00", 100);
        open("B", "BTO", 1, "SPY", "2026-11-13", "600", "CALL", "80.00", "2026-11-09T10:00", 100);
        open("C", "STO", 1, "AAPL", "2026-11-20", "190", "PUT", "120.00", "2026-11-09T10:00", 100);
        JsonNode d = dash();
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-12");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(List.of(d.get("cash").get("totalCash").asText(), d.get("cash").get("frozenCash").asText(),
                d.get("cash").get("availableCash").asText())).containsExactly("101043.05", "44000.00", "57043.05");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("703.05 (6)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("633.05 (4)");
        JsonNode p = d.get("positions");
        assertThat(p.get("positionCount").asInt()).isEqualTo(3);
        assertThat(p.get("frozenTotal").asText()).isEqualTo("44000.00");
        assertThat(p.get("expiringWithin7DaysCount").asInt()).isEqualTo(1);
        assertThat(p.get("expiredUnprocessedCount").asInt()).isEqualTo(1);
        assertDashboardConsistent(d);
        // the month link range gives the same numbers
        JsonNode month = get("/api/closed-trades?startDate=2026-11-01&endDate=2026-11-12").body();
        assertThat(month.get("filteredRealizedPnlTotal").asText()).isEqualTo("633.05");
    }

    @Test
    @DisplayName("7 天窗口边界：DTE 0、7 计入，8 不计；DTE −1 为已过期")
    void sevenDayWindowEdges() {
        deposit("100000.00", "2026-10-26T10:00");
        open("d0", "BTO", 1, "A", "2026-11-12", "1", "CALL", "1.00", "2026-11-02T10:00", 100);
        open("d7", "BTO", 1, "B", "2026-11-19", "1", "CALL", "1.00", "2026-11-02T10:00", 100);
        open("d8", "BTO", 1, "C", "2026-11-20", "1", "CALL", "1.00", "2026-11-02T10:00", 100);
        open("dm1", "BTO", 1, "D", "2026-11-11", "1", "CALL", "1.00", "2026-11-02T10:00", 100);
        JsonNode p = dash().get("positions");
        assertThat(p.get("positionCount").asInt()).isEqualTo(4);
        assertThat(p.get("expiringWithin7DaysCount").asInt()).isEqualTo(2);
        assertThat(p.get("expiredUnprocessedCount").asInt()).isEqualTo(1);
        // NY midnight: at 23:59 NY on 11-12 the counts are the same; at 00:00 NY 11-13 "d0" becomes expired
        clock.set(Instant.parse("2026-11-13T04:59:00Z"));
        assertThat(dash().get("positions").get("expiredUnprocessedCount").asInt()).isEqualTo(1);
        clock.set(Instant.parse("2026-11-13T05:00:00Z"));
        JsonNode next = dash().get("positions");
        assertThat(next.get("expiredUnprocessedCount").asInt()).isEqualTo(2);
        assertThat(next.get("expiringWithin7DaysCount").asInt()).isEqualTo(2); // d7 (6) and d8 (7)
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("AC2 本月边界：美东 11-30 23:30 仍是 11 月；12-01 00:30 是 12 月 0.00（0）；总计不变")
    void ac2_monthBoundary() {
        scenarioQ();
        clock.set(Instant.parse("2026-12-01T04:30:00Z"));
        JsonNode nov = dash();
        assertThat(nov.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(nov.get("asOfDate").asText()).isEqualTo("2026-11-30");
        assertThat(pnl(nov.get("currentMonthRealizedPnl"))).isEqualTo("633.05 (4)");
        clock.set(Instant.parse("2026-12-01T05:30:00Z"));
        JsonNode dec = dash();
        assertThat(dec.get("currentMonth").asText()).isEqualTo("2026-12");
        assertThat(dec.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(dec.get("currentMonthRealizedPnl").get("closedCount").asInt()).isZero();
        assertThat(pnl(dec.get("realizedPnl"))).isEqualTo("703.05 (6)");
    }

    @Test
    @DisplayName("AC2 美东 10-31 23:45（只有 O1–O4）：本月 2026-10 +70.00（2），O4 的 UTC 已是 11-01 仍算 10 月；00:00 EDT 切换到 11 月")
    void ac2_octoberWithLateClose() {
        clock.set(Instant.parse("2026-11-01T03:45:00Z")); // 23:45 EDT on 10-31
        deposit("100000.00", "2026-10-26T10:00");
        octoberTrades();
        JsonNode d = dash();
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-10");
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-10-31");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("70.00 (2)");
        clock.set(Instant.parse("2026-11-01T04:00:00Z")); // 00:00 EDT on 11-01
        JsonNode n = dash();
        assertThat(n.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(pnl(n.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
    }

    // ------------------------------------------------------------------ AC 3, 4

    @Test
    @DisplayName("AC3 全部时间：6 笔 +703.05；按标的 AAPL/MSFT/TSLA/XYZ；按月 11 月、10 月；= 总现金 − 入金")
    void ac3_allTime() {
        scenarioQ();
        JsonNode s = sum("");
        assertThat(s.get("startDate").isNull()).isTrue();
        assertThat(s.get("endDate").isNull()).isTrue();
        assertThat(s.get("closedCount").asInt()).isEqualTo(6);
        assertThat(s.get("realizedPnlTotal").asText()).isEqualTo("703.05");
        assertThat(rows(s.get("bySymbol"), "symbol")).containsExactly(
                "AAPL 3 248.70", "MSFT 1 350.00", "TSLA 1 120.00", "XYZ 1 -15.65");
        assertThat(rows(s.get("byMonth"), "month")).containsExactly("2026-11 4 633.05", "2026-10 2 70.00");
        BigDecimal gain = new BigDecimal(summary().get("totalCash").asText()).subtract(new BigDecimal("100000.00"));
        assertThat(new BigDecimal(s.get("realizedPnlTotal").asText())).isEqualByComparingTo(gain);
    }

    @Test
    @DisplayName("AC4 日期范围：11-01..11-06 → 3 +993.05；10-31 → O4 −50.00；09-01..11-30 只两个月；从 11-07 → −360.00；颠倒 / 未来 / 格式错")
    void ac4_ranges() {
        scenarioQ();
        JsonNode a = sum("startDate=2026-11-01&endDate=2026-11-06");
        assertThat(a.get("startDate").asText()).isEqualTo("2026-11-01");
        assertThat(a.get("endDate").asText()).isEqualTo("2026-11-06");
        assertThat(a.get("closedCount").asInt()).isEqualTo(3);
        assertThat(a.get("realizedPnlTotal").asText()).isEqualTo("993.05");
        assertThat(rows(a.get("bySymbol"), "symbol")).containsExactly("AAPL 1 658.70", "MSFT 1 350.00", "XYZ 1 -15.65");
        assertThat(rows(a.get("byMonth"), "month")).containsExactly("2026-11 3 993.05");

        JsonNode b = sum("startDate=2026-10-31&endDate=2026-10-31");
        assertThat(rows(b.get("bySymbol"), "symbol")).containsExactly("AAPL 1 -50.00");
        assertThat(rows(b.get("byMonth"), "month")).containsExactly("2026-10 1 -50.00");

        JsonNode c = sum("startDate=2026-09-01&endDate=2026-11-30");
        assertThat(rows(c.get("byMonth"), "month")).containsExactly("2026-11 4 633.05", "2026-10 2 70.00");

        JsonNode d = sum("startDate=2026-11-07");
        assertThat(d.get("closedCount").asInt()).isEqualTo(1);
        assertThat(d.get("realizedPnlTotal").asText()).isEqualTo("-360.00");
        assertThat(d.get("endDate").isNull()).isTrue();

        assertThat(sum("endDate=2026-10-30").get("realizedPnlTotal").asText()).isEqualTo("120.00");

        JsonNode e = sum("startDate=2027-01-01");
        assertThat(e.get("closedCount").asInt()).isZero();
        assertThat(e.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(e.get("bySymbol")).isEmpty();
        assertThat(e.get("byMonth")).isEmpty();

        Resp rev = get(SUM + "?startDate=2026-11-07&endDate=2026-11-06");
        assertErrorShape(rev, 400, "VALIDATION_ERROR", SUM);
        assertThat(rev.body().get("details").get(0).get("field").asText()).isEqualTo("endDate");
        assertThat(rev.body().get("details").get(0).get("message").asText()).isEqualTo("开始日期不能晚于结束日期");
        Resp bad = get(SUM + "?startDate=2026-02-30");
        assertErrorShape(bad, 400, "BAD_REQUEST", SUM);
        assertThat(bad.body().get("details").get(0).get("message").asText()).isEqualTo("日期格式不正确（YYYY-MM-DD）");
        assertErrorShape(get(SUM + "?endDate=11-06-2026"), 400, "BAD_REQUEST", SUM);
    }

    @Test
    @DisplayName("月份归属在夏令时切换日：3-08 / 3-31 23:30 EDT（UTC 4-01）归 3 月；11-30 23:30 EST（UTC 12-01）归 11 月")
    void monthAttributionAtNyMidnightAndDst() {
        clock.set(Instant.parse("2026-12-05T15:00:00Z"));
        deposit("100000.00", "2026-01-02T10:00");
        open("a", "BTO", 1, "A", "2026-12-18", "1", "CALL", "1.00", "2026-03-01T10:00", 100);
        open("b", "BTO", 1, "B", "2026-12-18", "1", "CALL", "1.00", "2026-03-01T10:00", 100);
        open("c", "BTO", 1, "C", "2026-12-18", "1", "CALL", "1.00", "2026-03-01T10:00", 100);
        close("ca", "STC", 1, "A", "2026-12-18", "1", "CALL", "2.00", "2026-03-31T23:30");
        close("cb", "STC", 1, "B", "2026-12-18", "1", "CALL", "3.00", "2026-04-01T00:00");
        close("cc", "STC", 1, "C", "2026-12-18", "1", "CALL", "5.00", "2026-11-30T23:30");
        JsonNode s = sum("");
        assertThat(rows(s.get("byMonth"), "month")).containsExactly("2026-11 1 4.00", "2026-04 1 2.00", "2026-03 1 1.00");
        assertThat(rows(sum("startDate=2026-03-31&endDate=2026-03-31").get("byMonth"), "month"))
                .containsExactly("2026-03 1 1.00");
        assertThat(pnl(dash().get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("AC5 跳转参数：AAPL + 11-01..11-06 → 只有 C1 +658.70；10 月 → O4 O3 +70.00；交集 10-30..10-31 → +70.00")
    void ac5_linkParameters() {
        scenarioQ();
        JsonNode aapl = get("/api/closed-trades?symbol=AAPL&startDate=2026-11-01&endDate=2026-11-06").body();
        assertThat(aapl.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(aapl.get("items").get(0).get("id").asLong()).isEqualTo(ids.get("C1"));
        assertThat(aapl.get("filteredRealizedPnlTotal").asText()).isEqualTo("658.70");
        JsonNode oct = get("/api/closed-trades?startDate=2026-10-01&endDate=2026-10-31").body();
        List<Long> octIds = new ArrayList<>();
        oct.get("items").forEach(i -> octIds.add(i.get("id").asLong()));
        assertThat(octIds).containsExactly(ids.get("O4"), ids.get("O3"));
        assertThat(oct.get("filteredRealizedPnlTotal").asText()).isEqualTo("70.00");
        assertThat(get("/api/closed-trades?startDate=2026-10-30&endDate=2026-10-31").body()
                .get("filteredRealizedPnlTotal").asText()).isEqualTo("70.00");
    }

    // ------------------------------------------------------------------ AC 6

    @Test
    @DisplayName("AC6 修改 C4 净额 → 总计 +763.05、本月 +693.05、AAPL +308.70；删除 O4 → +813.05（5）；入金不计入盈亏")
    void ac6_changesReflected() {
        scenarioQ();
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 2);
        edit.put("netAmount", "700.00");
        edit.put("tradedAtLocal", "2026-11-07T10:00");
        assertThat(exchange(HttpMethod.PUT, TRADES + "/" + ids.get("C4"), edit.toString(), MediaType.APPLICATION_JSON)
                .status()).isEqualTo(200);
        JsonNode d = dash();
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("763.05 (6)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("693.05 (4)");
        JsonNode s = sum("");
        assertThat(rows(s.get("bySymbol"), "symbol").get(0)).isEqualTo("AAPL 3 308.70");
        assertThat(rows(s.get("byMonth"), "month").get(0)).isEqualTo("2026-11 4 693.05");
        assertDashboardConsistent(d);

        assertThat(exchange(HttpMethod.DELETE, TRADES + "/" + ids.get("O4"), null, MediaType.APPLICATION_JSON)
                .status()).isEqualTo(204);
        JsonNode d2 = dash();
        assertThat(pnl(d2.get("realizedPnl"))).isEqualTo("813.05 (5)");
        JsonNode s2 = sum("");
        assertThat(rows(s2.get("bySymbol"), "symbol").get(0)).isEqualTo("AAPL 2 358.70");
        assertThat(rows(s2.get("byMonth"), "month")).containsExactly("2026-11 4 693.05", "2026-10 1 120.00");
        // O2 is an open position again
        assertThat(d2.get("positions").get("positionCount").asInt()).isEqualTo(1);
        assertDashboardConsistent(d2);

        String totalBefore = d2.get("cash").get("totalCash").asText();
        deposit("1000.00", "2026-11-10T10:00");
        withdraw("10.00", "2026-11-10T11:00");
        JsonNode d3 = dash();
        assertThat(new BigDecimal(d3.get("cash").get("totalCash").asText()))
                .isEqualByComparingTo(new BigDecimal(totalBefore).add(new BigDecimal("990.00")));
        assertThat(pnl(d3.get("realizedPnl"))).isEqualTo("813.05 (5)");
        assertThat(sum("").get("realizedPnlTotal").asText()).isEqualTo("813.05");
    }

    // ------------------------------------------------------------------ AC 7, read-only

    @Test
    @DisplayName("AC7 空：只有入金和未平仓的开仓 → 0.00（0）；汇总空；无任何数据时持仓 0")
    void ac7_empty() {
        JsonNode none = dash();
        assertThat(pnl(none.get("realizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(pnl(none.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
        JsonNode p = none.get("positions");
        assertThat(List.of(p.get("positionCount").asInt(), p.get("expiringWithin7DaysCount").asInt(),
                p.get("expiredUnprocessedCount").asInt())).containsExactly(0, 0, 0);
        assertThat(p.get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(none.get("cash").get("totalCash").asText()).isEqualTo("0.00");

        deposit("30000.00", "2026-10-26T10:00");
        open("x", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        JsonNode d = dash();
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(d.get("positions").get("positionCount").asInt()).isEqualTo(1);
        JsonNode s = sum("");
        assertThat(s.get("closedCount").asInt()).isZero();
        assertThat(s.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(s.get("bySymbol")).isEmpty();
        assertThat(s.get("byMonth")).isEmpty();
    }

    @Test
    @DisplayName("只读：查询前后现金、流水、交易不变；POST → 405")
    void readOnly() {
        scenarioQ();
        State before = state();
        JsonNode ledger = ledger(1, 100);
        dash();
        sum("");
        sum("startDate=2026-11-01");
        assertThat(state()).isEqualTo(before);
        assertThat(ledger(1, 100)).isEqualTo(ledger);
        assertErrorShape(postRaw(DASH, "{}", MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", DASH);
        assertErrorShape(postRaw(SUM, "{}", MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", SUM);
    }
}
