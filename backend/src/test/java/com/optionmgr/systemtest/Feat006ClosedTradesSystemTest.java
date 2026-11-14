package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** FEAT-006 closed trades / realized P&L end-to-end over HTTP. "Now" = 2026-11-11 10:00 New York. */
class Feat006ClosedTradesSystemTest extends OptionTradeSystemTestBase {

    static final String CLOSED = "/api/closed-trades";

    final Map<Long, String> labels = new LinkedHashMap<>();
    final Map<String, Long> ids = new LinkedHashMap<>();

    @Override
    Instant now() {
        return Instant.parse("2026-11-11T15:00:00Z");
    }

    // ------------------------------------------------------------------ helpers

    long open(String label, String action, int contracts, String symbol, String exp, String strike, String type,
              String net, String at, int multiplier) {
        long id = okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, multiplier)).get("id").asLong();
        if (label != null) {
            labels.put(id, label);
            ids.put(label, id);
        }
        return id;
    }

    long close(String label, String action, int contracts, String symbol, String exp, String strike, String type,
               String net, String at, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        if (note != null) {
            n.put("note", note);
        }
        Resp r = postRaw("/api/option-trades/closes", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("close %s", r.body()).isEqualTo(201);
        long id = r.body().get("id").asLong();
        if (label != null) {
            labels.put(id, label);
            ids.put(label, id);
        }
        return id;
    }

    void scenarioP() {
        deposit("100000.00", "2026-10-26T10:00");
        open("T1", "STO", 2, "AAPL", "2026-12-18", "200", "PUT", "698.70", "2026-11-02T10:30", 100);
        open("T2", "BTO", 1, "MSFT", "2027-01-15", "450", "CALL", "900.00", "2026-11-02T14:00", 100);
        open("T3", "STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-02T15:00", 10);
        open("T4", "BTO", 2, "AAPL", "2026-12-18", "210", "CALL", "1000.00", "2026-11-03T09:45", 100);
        open("T5", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-03T11:00", 100);
        close("C1", "BTC", 3, "AAPL", "2026-12-18", "200", "PUT", "400.00", "2026-11-05T10:00", "全部平掉");
        close("C2", "BTC", 1, "XYZ", "2026-12-18", "200", "PUT", "50.00", "2026-11-06T10:00", null);
        close("C3", "STC", 1, "MSFT", "2027-01-15", "450", "CALL", "1250.00", "2026-11-06T23:30", "盘后");
        close("C4", "STC", 2, "AAPL", "2026-12-18", "210", "CALL", "640.00", "2026-11-07T10:00", null);
        assertSummary("100633.05", "0.00", "100633.05");
        assertThat(get("/api/option-positions").body().get("positionCount").asInt()).isZero();
    }

    JsonNode closed(String query) {
        Resp r = get(CLOSED + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("GET closed %s -> %s", query, r.body()).isEqualTo(200);
        JsonNode b = r.body();
        assertThat(b.get("filteredCount").asLong()).isEqualTo(b.get("totalItems").asLong());
        return b;
    }

    /** Labels top to bottom, plus checks count and P&L total. */
    List<String> labelsOf(String query, int count, String total) {
        JsonNode b = closed(query);
        assertThat(b.get("filteredCount").asInt()).as("count %s", query).isEqualTo(count);
        assertThat(b.get("filteredRealizedPnlTotal").asText()).as("total %s", query).isEqualTo(total);
        List<String> out = new ArrayList<>();
        b.get("items").forEach(i -> out.add(labels.getOrDefault(i.get("id").asLong(), "#" + i.get("id").asLong())));
        return out;
    }

    /** "contracts multiplier closedDirection openingNet/per closeNet/per pnl". */
    String row(JsonNode i) {
        return String.join(" ", i.get("action").asText(), i.get("closedDirection").asText(),
                i.get("contracts").asText(), i.get("multiplier").asText(),
                i.get("openingNetTotal").asText() + "/" + i.get("openingNetPerContract").asText(),
                i.get("closeNetAmount").asText() + "/" + i.get("closeNetPerContract").asText(),
                i.get("realizedPnl").asText());
    }

    List<String> lots(JsonNode i) {
        List<String> out = new ArrayList<>();
        i.get("coveredLots").forEach(l -> out.add(labels.getOrDefault(l.get("tradeId").asLong(), "#") + " "
                + l.get("action").asText() + " " + l.get("contracts").asText() + " " + l.get("netAmount").asText()
                + " " + l.get("tradedAtLocal").asText()));
        return out;
    }

    BigDecimal sumOfAllPages(String query) {
        BigDecimal sum = BigDecimal.ZERO;
        JsonNode all = closed(query + (query.isEmpty() ? "" : "&") + "size=100");
        for (JsonNode i : all.get("items")) {
            sum = sum.add(new BigDecimal(i.get("realizedPnl").asText()));
        }
        return sum;
    }

    // ------------------------------------------------------------------ AC 1, 2

    @Test
    @DisplayName("AC1 4 笔平仓、合计 +633.05（= 总现金 − 入金 = 各条之和），顺序与各字段")
    void ac1_listAndFields() {
        scenarioP();
        JsonNode b = closed("");
        assertThat(labelsOf("", 4, "633.05")).containsExactly("C4", "C3", "C2", "C1");
        assertThat(b.get("page").asInt()).isEqualTo(1);
        assertThat(b.get("size").asInt()).isEqualTo(20);
        assertThat(b.get("totalPages").asInt()).isEqualTo(1);
        BigDecimal cashGain = new BigDecimal(summary().get("totalCash").asText()).subtract(new BigDecimal("100000.00"));
        assertThat(new BigDecimal(b.get("filteredRealizedPnlTotal").asText())).isEqualByComparingTo(cashGain);
        assertThat(sumOfAllPages("")).isEqualByComparingTo(cashGain);

        List<String> rows = new ArrayList<>();
        b.get("items").forEach(i -> rows.add(row(i)));
        assertThat(rows).containsExactly(
                "STC LONG 2 100 -1000.00/-500.00 640.00/320.00 -360.00",
                "STC LONG 1 100 -900.00/-900.00 1250.00/1250.00 350.00",
                "BTC SHORT 1 10 34.35/34.35 -50.00/-50.00 -15.65",
                "BTC SHORT 3 100 1058.70/352.90 -400.00/-133.33 658.70");
        JsonNode c3 = b.get("items").get(1);
        assertThat(c3.get("tradedAt").asText()).isEqualTo("2026-11-07T04:30:00Z");
        assertThat(c3.get("tradedAtLocal").asText()).isEqualTo("2026-11-06T23:30:00");
        assertThat(c3.get("note").asText()).isEqualTo("盘后");
        assertThat(c3.get("contractLabel").asText()).isEqualTo("MSFT 2027-01-15 450 C");
        JsonNode c1 = b.get("items").get(3);
        assertThat(c1.get("note").asText()).isEqualTo("全部平掉");
        assertThat(c1.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(c1.get("strike").asText()).isEqualTo("200");
        assertThat(c1.get("optionType").asText()).isEqualTo("PUT");
        assertThat(b.get("items").get(0).get("note").isNull()).isTrue();
        for (JsonNode i : b.get("items")) {
            JsonNode t = getTrade(i.get("id").asLong());
            assertThat(i.get("realizedPnl")).isEqualTo(t.get("realizedPnl"));
            assertThat(i.get("coveredLots")).isEqualTo(t.get("coveredLots"));
            assertThat(i.get("closeNetAmount")).isEqualTo(t.get("cashChange"));
            List<String> keys = new ArrayList<>();
            i.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).doesNotContain("price", "fee", "holdingDays");
        }
    }

    @Test
    @DisplayName("AC2 被平批次：C1 → T1(2) + T5(1)；C4 → T4 BTO 2 −1,000.00")
    void ac2_coveredLots() {
        scenarioP();
        JsonNode items = closed("").get("items");
        assertThat(lots(items.get(3))).containsExactly(
                "T1 STO 2 698.70 2026-11-02T10:30:00", "T5 STO 1 360.00 2026-11-03T11:00:00");
        assertThat(lots(items.get(0))).containsExactly("T4 BTO 2 -1000.00 2026-11-03T09:45:00");
        assertThat(lots(items.get(1))).containsExactly("T2 BTO 1 -900.00 2026-11-02T14:00:00");
        assertThat(lots(items.get(2))).containsExactly("T3 STO 1 34.35 2026-11-02T15:00:00");
    }

    // ------------------------------------------------------------------ AC 3, 4

    @Test
    @DisplayName("AC3 筛选与合计：标的、合约四项、类型、方向、无结果")
    void ac3_filters() {
        scenarioP();
        assertThat(labelsOf("symbol=aapl", 2, "298.70")).containsExactly("C4", "C1");
        assertThat(labelsOf("symbol=AAPL&expirationDate=2026-12-18&strike=200.00&optionType=PUT", 1, "658.70"))
                .containsExactly("C1");
        assertThat(labelsOf("optionType=CALL", 2, "-10.00")).containsExactly("C4", "C3");
        assertThat(labelsOf("action=BTC", 2, "643.05")).containsExactly("C2", "C1");
        assertThat(labelsOf("action=STC", 2, "-10.00")).containsExactly("C4", "C3");
        assertThat(labelsOf("action=BTC,STC", 4, "633.05")).hasSize(4);
        assertThat(labelsOf("action=BTC&action=STC", 4, "633.05")).hasSize(4);
        JsonNode none = closed("symbol=TSLA");
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("filteredCount").asInt()).isZero();
        assertThat(none.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(none.get("totalPages").asInt()).isZero();
        assertThat(labelsOf("expirationDate=2026-12-18&optionType=PUT", 2, "643.05")).containsExactly("C2", "C1");
        assertThat(labelsOf("symbol=AAPL&action=STC", 1, "-360.00")).containsExactly("C4");
    }

    @ParameterizedTest(name = "方向 {0} → BAD_REQUEST 方向只能是 BTC、STC、EXP、ASG、EXR（FEAT-012 加 EXP，FEAT-013 加 ASG，FEAT-014 加 EXR）")
    @ValueSource(strings = {"BTO", "STO", "BTC,STO", "btc", "XYZ"})
    void openingActionsRejected(String action) {
        Resp r = get(CLOSED + "?action=" + action);
        assertErrorShape(r, 400, "BAD_REQUEST", CLOSED);
        assertThat(r.body().get("details").get(0).get("field").asText()).isEqualTo("action");
        assertThat(r.body().get("details").get(0).get("message").asText()).isEqualTo("方向只能是 BTC、STC、EXP、ASG、EXR");
    }

    @Test
    @DisplayName("AC3 其它参数错误与 FEAT-002 相同")
    void otherErrorsSameAsTradeList() {
        Resp sym = get(CLOSED + "?symbol=$AAPL");
        assertErrorShape(sym, 400, "VALIDATION_ERROR", CLOSED);
        assertThat(sym.body().get("details").get(0).get("message").asText()).isEqualTo("标的只能包含字母、数字和点");
        assertErrorShape(get(CLOSED + "?strike=abc"), 400, "BAD_REQUEST", CLOSED);
        assertErrorShape(get(CLOSED + "?strike=0"), 400, "VALIDATION_ERROR", CLOSED);
        assertErrorShape(get(CLOSED + "?expirationDate=2026-02-30"), 400, "BAD_REQUEST", CLOSED);
        assertErrorShape(get(CLOSED + "?page=0"), 400, "VALIDATION_ERROR", CLOSED);
        assertErrorShape(get(CLOSED + "?size=101"), 400, "VALIDATION_ERROR", CLOSED);
        Resp both = get(CLOSED + "?strike=abc&symbol=$X");
        assertErrorShape(both, 400, "BAD_REQUEST", CLOSED);
        assertThat(detailFields(both)).containsExactly("strike");
    }

    @Test
    @DisplayName("AC4 平仓日期（美东）：11-06 → C3 C2（含 23:30 深夜）；11-07 → C4；只到 11-05 → C1；颠倒 → 错误；开仓日期不参与")
    void ac4_closeDates() {
        scenarioP();
        assertThat(labelsOf("startDate=2026-11-06&endDate=2026-11-06", 2, "334.35")).containsExactly("C3", "C2");
        assertThat(labelsOf("startDate=2026-11-07&endDate=2026-11-07", 1, "-360.00")).containsExactly("C4");
        assertThat(labelsOf("endDate=2026-11-05", 1, "658.70")).containsExactly("C1");
        assertThat(labelsOf("startDate=2026-11-02&endDate=2026-11-04", 0, "0.00")).isEmpty();
        assertThat(labelsOf("startDate=2026-11-06", 3, "-25.65")).containsExactly("C4", "C3", "C2");
        Resp rev = get(CLOSED + "?startDate=2026-11-07&endDate=2026-11-06");
        assertErrorShape(rev, 400, "VALIDATION_ERROR", CLOSED);
        assertThat(rev.body().get("details").get(0).get("field").asText()).isEqualTo("endDate");
        assertThat(rev.body().get("details").get(0).get("message").asText()).isEqualTo("开始日期不能晚于结束日期");
    }

    @Test
    @DisplayName("美东日期边界：23:59 / 00:00；夏令时结束日 11-01 23:30（UTC 11-02）；同一时间后录入的在上")
    void nyBoundaries() {
        deposit("100000.00", "2026-10-01T10:00");
        open(null, "BTO", 1, "A", "2026-12-18", "1", "CALL", "1.00", "2026-10-02T10:00", 100);
        open(null, "BTO", 1, "B", "2026-12-18", "1", "CALL", "1.00", "2026-10-02T10:00", 100);
        open(null, "BTO", 1, "C", "2026-12-18", "1", "CALL", "1.00", "2026-10-02T10:00", 100);
        open(null, "BTO", 1, "D", "2026-12-18", "1", "CALL", "1.00", "2026-10-02T10:00", 100);
        close("A", "STC", 1, "A", "2026-12-18", "1", "CALL", "2.00", "2026-11-02T23:59", null);
        close("B", "STC", 1, "B", "2026-12-18", "1", "CALL", "3.00", "2026-11-03T00:00", null);
        close("C", "STC", 1, "C", "2026-12-18", "1", "CALL", "4.00", "2026-11-01T23:30", null);
        close("D", "STC", 1, "D", "2026-12-18", "1", "CALL", "5.00", "2026-11-01T23:30", null);
        assertThat(labelsOf("startDate=2026-11-02&endDate=2026-11-02", 1, "1.00")).containsExactly("A");
        assertThat(labelsOf("startDate=2026-11-03&endDate=2026-11-03", 1, "2.00")).containsExactly("B");
        // same instant: D entered after C -> D on top
        assertThat(labelsOf("startDate=2026-11-01&endDate=2026-11-01", 2, "7.00")).containsExactly("D", "C");
    }

    // ------------------------------------------------------------------ AC 5 (re-matching)

    @Test
    @DisplayName("AC5 补录 Y → X +260.00 / Y +229.35；改 L2 净额 → X +265.00；删 Y → X +249.35")
    void ac5_rematching() {
        deposit("80000.00", "2026-10-26T10:00");
        open("L1", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        open("L2", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-04T11:00", 100);
        close("X", "BTC", 1, "AAPL", "2026-12-18", "200", "PUT", "100.00", "2026-11-06T10:00", null);
        assertThat(labelsOf("", 1, "249.35")).containsExactly("X");
        assertThat(lots(closed("").get("items").get(0)).get(0)).startsWith("L1 ");

        close("Y", "BTC", 1, "AAPL", "2026-12-18", "200", "PUT", "120.00", "2026-11-05T10:00", null);
        assertThat(labelsOf("", 2, "489.35")).containsExactly("X", "Y");
        JsonNode items = closed("").get("items");
        assertThat(items.get(0).get("realizedPnl").asText()).isEqualTo("260.00");
        assertThat(lots(items.get(0)).get(0)).startsWith("L2 ");
        assertThat(items.get(1).get("realizedPnl").asText()).isEqualTo("229.35");
        assertThat(lots(items.get(1)).get(0)).startsWith("L1 ");

        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "365.00");
        edit.put("tradedAtLocal", "2026-11-04T11:00");
        edit.put("multiplier", 100);
        Resp put = exchange(HttpMethod.PUT, TRADES + "/" + ids.get("L2"), edit.toString(), MediaType.APPLICATION_JSON);
        assertThat(put.status()).isEqualTo(200);
        assertThat(labelsOf("", 2, "494.35")).containsExactly("X", "Y");
        assertThat(closed("").get("items").get(0).get("realizedPnl").asText()).isEqualTo("265.00");

        Resp del = exchange(HttpMethod.DELETE, TRADES + "/" + ids.get("Y"), null, MediaType.APPLICATION_JSON);
        assertThat(del.status()).isEqualTo(204);
        assertThat(labelsOf("", 1, "249.35")).containsExactly("X");
        assertThat(lots(closed("").get("items").get(0)).get(0)).startsWith("L1 ");
    }

    // ------------------------------------------------------------------ AC 6

    @Test
    @DisplayName("AC6 25 笔分两页：合计 +50.00 在每页都相同；第 4 页空但合计不变；平仓开始 10-20 → 6 笔 +12.00")
    void ac6_pagination() {
        deposit("10000.00", "2026-09-30T10:00");
        LocalDate d = LocalDate.of(2026, 10, 1);
        for (int i = 0; i < 25; i++) {
            open(null, "BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "10.00", d.plusDays(i) + "T09:30", 100);
            close(null, "STC", 1, "AAPL", "2026-12-18", "210", "CALL", "12.00", d.plusDays(i) + "T15:00", null);
        }
        JsonNode p1 = closed("page=1");
        assertThat(p1.get("filteredCount").asInt()).isEqualTo(25);
        assertThat(p1.get("filteredRealizedPnlTotal").asText()).isEqualTo("50.00");
        assertThat(p1.get("totalPages").asInt()).isEqualTo(2);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("items").get(0).get("tradedAtLocal").asText()).isEqualTo("2026-10-25T15:00:00");
        assertThat(p1.get("items").get(0).get("realizedPnl").asText()).isEqualTo("2.00");
        JsonNode p2 = closed("page=2");
        assertThat(p2.get("items")).hasSize(5);
        assertThat(p2.get("items").get(4).get("tradedAtLocal").asText()).isEqualTo("2026-10-01T15:00:00");
        assertThat(p2.get("filteredRealizedPnlTotal").asText()).isEqualTo("50.00");
        assertThat(p2.get("filteredCount").asInt()).isEqualTo(25);
        JsonNode p4 = closed("page=4");
        assertThat(p4.get("items")).isEmpty();
        assertThat(p4.get("filteredRealizedPnlTotal").asText()).isEqualTo("50.00");
        assertThat(sumOfAllPages("")).isEqualByComparingTo("50.00");
        JsonNode f = closed("startDate=2026-10-20");
        assertThat(f.get("filteredCount").asInt()).isEqualTo(6);
        assertThat(f.get("filteredRealizedPnlTotal").asText()).isEqualTo("12.00");
        assertThat(closed("size=7&page=4").get("items")).hasSize(4);
    }

    // ------------------------------------------------------------------ AC 7, 8, 9

    @Test
    @DisplayName("AC7 合约四项作为交易列表参数 → C1 T5 T1；审计按平仓 id 筛选可用")
    void ac7_links() {
        scenarioP();
        JsonNode c1 = closed("action=BTC&symbol=AAPL").get("items").get(0);
        String q = "symbol=" + c1.get("symbol").asText() + "&expirationDate=" + c1.get("expirationDate").asText()
                + "&strike=" + c1.get("strike").asText() + "&optionType=" + c1.get("optionType").asText();
        List<String> list = new ArrayList<>();
        get(TRADES + "?" + q).body().get("items").forEach(i -> list.add(labels.get(i.get("id").asLong())));
        assertThat(list).containsExactly("C1", "T5", "T1");
        Resp audit = get("/api/audit-log?objectType=OPTION_TRADE&objectId=" + c1.get("id").asLong());
        assertThat(audit.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC8 只有开仓 → 0 笔、合计 0.00")
    void ac8_empty() {
        deposit("30000.00", "2026-10-26T10:00");
        open(null, "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        JsonNode b = closed("");
        assertThat(b.get("items")).isEmpty();
        assertThat(b.get("filteredCount").asInt()).isZero();
        assertThat(b.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(b.get("totalPages").asInt()).isZero();
    }

    @Test
    @DisplayName("AC9 只读：各种查询后现金、流水、交易、持仓都不变；POST → 405")
    void ac9_readOnly() {
        scenarioP();
        State before = state();
        JsonNode ledger = ledger(1, 100);
        for (String q : List.of("", "symbol=aapl", "action=BTC", "page=2&size=1", "startDate=2026-11-06")) {
            closed(q);
        }
        assertThat(state()).isEqualTo(before);
        assertThat(ledger(1, 100)).isEqualTo(ledger);
        assertThat(get(TRADES).body().get("totalItems").asInt()).isEqualTo(9);
        assertErrorShape(postRaw(CLOSED, "{}", MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", CLOSED);
    }

    @Test
    @DisplayName("每张平均 HALF_UP：−33.33 / +33.34；−0.13 / +0.18；盈亏不受舍入影响")
    void perContractRounding() {
        deposit("1000.00", "2026-10-26T10:00");
        open(null, "BTO", 3, "A", "2026-12-18", "1", "CALL", "100.00", "2026-11-02T10:00", 100);
        open(null, "BTO", 2, "B", "2026-12-18", "1", "CALL", "0.25", "2026-11-02T10:00", 100);
        close("A", "STC", 3, "A", "2026-12-18", "1", "CALL", "100.01", "2026-11-03T10:00", null);
        close("B", "STC", 2, "B", "2026-12-18", "1", "CALL", "0.35", "2026-11-04T10:00", null);
        JsonNode items = closed("").get("items");
        assertThat(row(items.get(1))).isEqualTo("STC LONG 3 100 -100.00/-33.33 100.01/33.34 0.01");
        assertThat(row(items.get(0))).isEqualTo("STC LONG 2 100 -0.25/-0.13 0.35/0.18 0.10");
        assertThat(closed("").get("filteredRealizedPnlTotal").asText()).isEqualTo("0.11");
    }
}
