package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FEAT-002 trade list & filters end-to-end over HTTP. "Now" = 2026-11-12T15:00:00Z (base class), i.e. after the
 * baseline's latest trade (2026-11-04 11:00 NY). Baseline T1–T6 is entered in order, ids are mapped back to labels.
 */
class Feat002TradeListSystemTest extends OptionTradeSystemTestBase {

    static final String LIST = "/api/option-trades";

    /** trade id -> "T1".."T6" for the baseline. */
    final Map<Long, String> labels = new LinkedHashMap<>();

    void baseline() {
        deposit("100000.00", "2026-10-26T10:00");
        add("T1", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100, "第一笔 CSP");
        add("T2", "BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "512.65", "2026-11-03T10:30", 100, null);
        add("T3", "STO", 2, "F", "2026-12-18", "12.5", "PUT", "48.70", "2026-11-02T15:59", 100, null);
        add("T4", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-04T11:00", 100, "加仓");
        add("T5", "BTO", 1, "MSFT", "2027-01-15", "450", "CALL", "900.00", "2026-11-03T23:30", 100, null);
        add("T6", "STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-10-30T10:00", 10, null);
        assertSummary("99379.75", "44500.00", "54879.75");
        assertThat(ledgerCount()).isEqualTo(7);
    }

    JsonNode add(String label, String action, int contracts, String symbol, String exp, String strike, String type,
                 String net, String at, int multiplier, String note) {
        ObjectNode b = trade(action, contracts, symbol, exp, strike, type, net, at, multiplier);
        if (note != null) {
            b.put("note", note);
        }
        JsonNode t = okTrade(b);
        if (label != null) {
            labels.put(t.get("id").asLong(), label);
        }
        return t;
    }

    JsonNode list(String query) {
        Resp r = get(LIST + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("GET %s -> %s", query, r.body()).isEqualTo(200);
        return r.body();
    }

    /** Labels of the returned page, top to bottom; also checks totalItems. */
    List<String> labelsOf(String query, int expectedTotal) {
        JsonNode p = list(query);
        assertThat(p.get("totalItems").asLong()).as("totalItems for %s", query).isEqualTo(expectedTotal);
        List<String> out = new ArrayList<>();
        p.get("items").forEach(i -> out.add(labels.getOrDefault(i.get("id").asLong(), "#" + i.get("id").asLong())));
        return out;
    }

    Resp listError(String query, int status, String code) {
        Resp r = get(LIST + "?" + query);
        assertErrorShape(r, status, code, LIST);
        return r;
    }

    List<String> details(Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("details").forEach(d -> out.add(d.get("field").asText() + "=" + d.get("message").asText()));
        return out;
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC1 默认排序 T4 T5 T2 T1 T3 T6，分页字段，各项与按 id 查询完全一致，没有价格/手续费字段")
    void ac1_defaultOrderAndItems() {
        baseline();
        JsonNode p = list("");
        assertThat(p.get("page").asInt()).isEqualTo(1);
        assertThat(p.get("size").asInt()).isEqualTo(20);
        assertThat(p.get("totalItems").asLong()).isEqualTo(6);
        assertThat(p.get("totalPages").asInt()).isEqualTo(1);
        assertThat(labelsOf("", 6)).containsExactly("T4", "T5", "T2", "T1", "T3", "T6");

        for (JsonNode item : p.get("items")) {
            assertThat(item).isEqualTo(getTrade(item.get("id").asLong()));
            Set<String> keys = new HashSet<>();
            item.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).doesNotContain("price", "fee", "fees", "commission");
        }
        JsonNode t4 = p.get("items").get(0);
        assertThat(t4.get("tradedAtLocal").asText()).isEqualTo("2026-11-04T11:00:00");
        assertThat(t4.get("cashChange").asText()).isEqualTo("360.00");
        assertThat(t4.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(t4.get("note").asText()).isEqualTo("加仓");
        JsonNode t5 = p.get("items").get(1);
        assertThat(t5.get("tradedAt").asText()).isEqualTo("2026-11-04T04:30:00Z");
        assertThat(t5.get("tradedAtLocal").asText()).isEqualTo("2026-11-03T23:30:00");
        assertThat(t5.get("contractLabel").asText()).isEqualTo("MSFT 2027-01-15 450 C");
        assertThat(t5.get("cashChange").asText()).isEqualTo("-900.00");
        assertThat(t5.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(t5.get("note").isNull()).isTrue();
        JsonNode t3 = p.get("items").get(4);
        assertThat(t3.get("strike").asText()).isEqualTo("12.5");
        assertThat(t3.get("contracts").asInt()).isEqualTo(2);
        assertThat(t3.get("frozenChange").asText()).isEqualTo("2500.00");
        JsonNode t6 = p.get("items").get(5);
        assertThat(t6.get("multiplier").asInt()).isEqualTo(10);
        assertThat(t6.get("frozenChange").asText()).isEqualTo("2000.00");
        assertThat(t6.get("contractLabel").asText()).isEqualTo("XYZ 2026-12-18 200 P");
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("AC2 标的精确匹配并规范化：aapl / ' AAPL ' → T4 T2 T1；AAP → 0；空白 = 不筛选")
    void ac2_symbol() {
        baseline();
        assertThat(labelsOf("symbol=aapl", 3)).containsExactly("T4", "T2", "T1");
        assertThat(labelsOf("symbol= AAPL ", 3)).containsExactly("T4", "T2", "T1");
        JsonNode none = list("symbol=AAP");
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("totalItems").asLong()).isZero();
        assertThat(none.get("totalPages").asInt()).isZero();
        assertThat(labelsOf("symbol=", 6)).hasSize(6);
        assertThat(labelsOf("symbol=   ", 6)).hasSize(6);
    }

    @ParameterizedTest(name = "AC2 标的 {0} → VALIDATION_ERROR {1}")
    @CsvSource(delimiter = '|', value = {
            "$AAPL|标的只能包含字母、数字和点",
            "AA PL|标的只能包含字母、数字和点",
            "ABCDEFGHIJK|标的最多 10 个字符",
    })
    void ac2_symbolInvalid(String symbol, String message) {
        baseline();
        Resp r = listError("symbol=" + symbol, 400, "VALIDATION_ERROR");
        assertThat(details(r)).containsExactly("symbol=" + message);
    }

    // ------------------------------------------------------------------ AC 3

    @Test
    @DisplayName("AC3 合约筛选：四项 → T4 T1；行权价 200.00/200.000 等价；只到期日 / 只 CALL / 只 12.5")
    void ac3_contract() {
        baseline();
        String c = "symbol=AAPL&expirationDate=2026-12-18&optionType=PUT&strike=";
        assertThat(labelsOf(c + "200", 2)).containsExactly("T4", "T1");
        assertThat(labelsOf(c + "200.00", 2)).containsExactly("T4", "T1");
        assertThat(labelsOf(c + "200.000", 2)).containsExactly("T4", "T1");
        assertThat(labelsOf("expirationDate=2026-12-18", 5)).containsExactly("T4", "T2", "T1", "T3", "T6");
        assertThat(labelsOf("optionType=CALL", 2)).containsExactly("T5", "T2");
        assertThat(labelsOf("optionType=PUT", 4)).containsExactly("T4", "T1", "T3", "T6");
        assertThat(labelsOf("strike=12.5", 1)).containsExactly("T3");
        assertThat(labelsOf("strike=12.50", 1)).containsExactly("T3");
        assertThat(labelsOf("strike=200", 3)).containsExactly("T4", "T1", "T6");
        assertThat(labelsOf("symbol=XYZ&expirationDate=2026-12-18&strike=200&optionType=PUT", 1)).containsExactly("T6");
        assertThat(labelsOf("expirationDate=2027-01-15", 1)).containsExactly("T5");
    }

    @ParameterizedTest(name = "AC3 行权价 {0} → {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "0|VALIDATION_ERROR|行权价必须大于 0",
            "-5|VALIDATION_ERROR|行权价必须大于 0",
            "200.1234|VALIDATION_ERROR|行权价最多 3 位小数",
            "10000000|VALIDATION_ERROR|行权价过大",
            "abc|BAD_REQUEST|行权价必须是数字",
    })
    void ac3_strikeInvalid(String strike, String code, String message) {
        baseline();
        Resp r = listError("strike=" + strike, 400, code);
        assertThat(details(r)).containsExactly("strike=" + message);
    }

    @ParameterizedTest(name = "无法解析 {0} → BAD_REQUEST")
    @CsvSource(delimiter = '|', value = {
            "optionType=put|optionType|类型只能是 CALL 或 PUT",
            "optionType=P|optionType|类型只能是 CALL 或 PUT",
            "action=bto|action|方向只能是 BTO、STO、BTC、STC、EXP",
            "action=XYZ|action|方向只能是 BTO、STO、BTC、STC、EXP",
            "expirationDate=2026-02-30|expirationDate|日期格式不正确（YYYY-MM-DD）",
            "expirationDate=2026/12/18|expirationDate|日期格式不正确（YYYY-MM-DD）",
            "startDate=2026-13-01|startDate|日期格式不正确（YYYY-MM-DD）",
            "endDate=11-03-2026|endDate|日期格式不正确（YYYY-MM-DD）",
    })
    void unparseable(String query, String field, String message) {
        baseline();
        Resp r = listError(query, 400, "BAD_REQUEST");
        assertThat(details(r)).containsExactly(field + "=" + message);
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("AC4 方向多选：STO / BTO / 重复参数 / 逗号 / 混用 / BTC、STC 为空不报错")
    void ac4_actions() {
        baseline();
        assertThat(labelsOf("action=STO", 4)).containsExactly("T4", "T1", "T3", "T6");
        assertThat(labelsOf("action=BTO", 2)).containsExactly("T5", "T2");
        List<String> all = List.of("T4", "T5", "T2", "T1", "T3", "T6");
        assertThat(labelsOf("action=BTO&action=STO", 6)).containsExactlyElementsOf(all);
        assertThat(labelsOf("action=BTO,STO", 6)).containsExactlyElementsOf(all);
        assertThat(labelsOf("action=BTO,&action=STO,BTC", 6)).containsExactlyElementsOf(all);
        assertThat(labelsOf("action=BTC", 0)).isEmpty();
        assertThat(labelsOf("action=STC", 0)).isEmpty();
        assertThat(labelsOf("action=BTC,STC", 0)).isEmpty();
        assertThat(labelsOf("action=", 6)).hasSize(6);
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("AC5 日期范围（美东日期、两端包含、可只填一端、可含未来）")
    void ac5_dateRange() {
        baseline();
        assertThat(labelsOf("startDate=2026-11-03&endDate=2026-11-03", 3)).containsExactly("T5", "T2", "T1");
        assertThat(labelsOf("startDate=2026-11-04&endDate=2026-11-04", 1)).containsExactly("T4");
        assertThat(labelsOf("startDate=2026-11-03", 4)).containsExactly("T4", "T5", "T2", "T1");
        assertThat(labelsOf("endDate=2026-11-02", 2)).containsExactly("T3", "T6");
        assertThat(labelsOf("startDate=2026-11-06&endDate=2099-12-31", 0)).isEmpty();
        Resp reversed = listError("startDate=2026-11-04&endDate=2026-11-03", 400, "VALIDATION_ERROR");
        assertThat(details(reversed)).containsExactly("endDate=开始日期不能晚于结束日期");
    }

    @Test
    @DisplayName("AC5 夏令时：美东 2026-07-15 21:00（UTC 07-16 01:00）属于 07-15")
    void ac5_dstDate() {
        deposit("1000.00", "2026-07-01T10:00");
        add("D", "BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "100.00", "2026-07-15T21:00", 100, null);
        assertThat(labelsOf("startDate=2026-07-15&endDate=2026-07-15", 1)).containsExactly("D");
        assertThat(labelsOf("startDate=2026-07-16&endDate=2026-07-16", 0)).isEmpty();
    }

    @Test
    @DisplayName("美东日期边界：午夜两侧、夏令时开始/结束当天（23/25 小时）")
    void nyDateBoundariesAndDstChangeDays() {
        deposit("100000.00", "2026-01-02T10:00");
        add("M0", "BTO", 1, "A", "2026-12-18", "1", "CALL", "1.00", "2026-11-02T23:59:59", 100, null);
        add("M1", "BTO", 1, "A", "2026-12-18", "1", "CALL", "1.00", "2026-11-03T00:00", 100, null);
        add("S1", "BTO", 1, "B", "2026-12-18", "1", "CALL", "1.00", "2026-03-08T00:30", 100, null);   // EST 05:30Z
        add("S2", "BTO", 1, "B", "2026-12-18", "1", "CALL", "1.00", "2026-03-08T23:30", 100, null);   // EDT, 03-09 03:30Z
        add("S3", "BTO", 1, "B", "2026-12-18", "1", "CALL", "1.00", "2026-03-09T00:10", 100, null);   // EDT, 03-09 04:10Z
        add("F1", "BTO", 1, "C", "2026-12-18", "1", "CALL", "1.00", "2026-11-01T00:30", 100, null);   // EDT, 04:30Z
        add("F2", "BTO", 1, "C", "2026-12-18", "1", "CALL", "1.00", "2026-11-01T01:30", 100, null);   // ambiguous -> EDT 05:30Z
        add("F3", "BTO", 1, "C", "2026-12-18", "1", "CALL", "1.00", "2026-11-01T23:30", 100, null);   // EST, 11-02 04:30Z
        add("F4", "BTO", 1, "C", "2026-12-18", "1", "CALL", "1.00", "2026-10-31T23:59", 100, null);   // EDT, 11-01 03:59Z

        assertThat(labelsOf("startDate=2026-11-02&endDate=2026-11-02", 1)).containsExactly("M0");
        assertThat(labelsOf("startDate=2026-11-03&endDate=2026-11-03", 1)).containsExactly("M1");
        assertThat(labelsOf("startDate=2026-03-08&endDate=2026-03-08", 2)).containsExactly("S2", "S1");
        assertThat(labelsOf("startDate=2026-03-09&endDate=2026-03-09", 1)).containsExactly("S3");
        assertThat(labelsOf("startDate=2026-11-01&endDate=2026-11-01", 3)).containsExactly("F3", "F2", "F1");
        assertThat(labelsOf("startDate=2026-10-31&endDate=2026-10-31", 1)).containsExactly("F4");
    }

    // ------------------------------------------------------------------ AC 6

    @Test
    @DisplayName("AC6 组合筛选：AAPL + STO + 11-03..11-04 → T4 T1；结束改 11-03 → T1；清除 → 6 条")
    void ac6_combined() {
        baseline();
        assertThat(labelsOf("symbol=AAPL&action=STO&startDate=2026-11-03&endDate=2026-11-04", 2))
                .containsExactly("T4", "T1");
        assertThat(labelsOf("symbol=AAPL&action=STO&startDate=2026-11-03&endDate=2026-11-03", 1))
                .containsExactly("T1");
        assertThat(labelsOf("symbol=&expirationDate=&strike=&optionType=&action=&startDate=&endDate=", 6)).hasSize(6);
        assertThat(labelsOf("symbol=AAPL&expirationDate=2026-12-18&strike=200&optionType=PUT&action=BTO", 0)).isEmpty();
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("AC7 分页 45 笔：3 页 20/20/5、无重复遗漏；第 4 页空；size 50；startDate 10-01 → 15；参数校验")
    void ac7_pagination() {
        deposit("100000.00", "2026-08-01T10:00");
        LocalDate d = LocalDate.of(2026, 9, 1);
        for (int i = 0; i < 45; i++) {
            add(null, "BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "1.00", d.plusDays(i) + "T10:00", 100, null);
        }
        JsonNode p1 = list("page=1&size=20");
        assertThat(p1.get("totalItems").asLong()).isEqualTo(45);
        assertThat(p1.get("totalPages").asInt()).isEqualTo(3);
        assertThat(p1.get("items").get(0).get("tradedAtLocal").asText()).isEqualTo("2026-10-15T10:00:00");

        Set<Long> ids = new HashSet<>();
        String prev = null;
        int[] sizes = {20, 20, 5};
        for (int page = 1; page <= 3; page++) {
            JsonNode items = list("page=" + page).get("items");
            assertThat(items).hasSize(sizes[page - 1]);
            for (JsonNode it : items) {
                ids.add(it.get("id").asLong());
                String at = it.get("tradedAt").asText();
                if (prev != null) {
                    assertThat(at).isLessThan(prev);
                }
                prev = at;
            }
        }
        assertThat(ids).hasSize(45);
        assertThat(list("page=3").get("items").get(4).get("tradedAtLocal").asText()).isEqualTo("2026-09-01T10:00:00");

        JsonNode p4 = list("page=4&size=20");
        assertThat(p4.get("items")).isEmpty();
        assertThat(p4.get("totalItems").asLong()).isEqualTo(45);
        assertThat(p4.get("totalPages").asInt()).isEqualTo(3);

        JsonNode s50 = list("size=50");
        assertThat(s50.get("items")).hasSize(45);
        assertThat(s50.get("totalPages").asInt()).isEqualTo(1);
        assertThat(list("size=1&page=45").get("items").get(0).get("tradedAtLocal").asText())
                .isEqualTo("2026-09-01T10:00:00");
        assertThat(list("size=100").get("items")).hasSize(45);

        JsonNode oct = list("startDate=2026-10-01&page=1");
        assertThat(oct.get("totalItems").asLong()).isEqualTo(15);
        assertThat(oct.get("items")).hasSize(15);

        assertThat(details(listError("size=0", 400, "VALIDATION_ERROR"))).containsExactly("size=size 必须在 1 到 100 之间");
        assertThat(details(listError("size=101", 400, "VALIDATION_ERROR"))).containsExactly("size=size 必须在 1 到 100 之间");
        assertThat(details(listError("page=0", 400, "VALIDATION_ERROR"))).containsExactly("page=page 必须 ≥ 1");
        assertThat(detailFields(listError("page=abc", 400, "BAD_REQUEST"))).containsExactly("page");
        assertThat(detailFields(listError("size=1.5", 400, "BAD_REQUEST"))).containsExactly("size");
    }

    // ------------------------------------------------------------------ AC 8, 11

    @Test
    @DisplayName("AC8 没有交易（只有入金）：items [] totalItems 0 totalPages 0")
    void ac8_empty() {
        deposit("100.00", "2026-10-26T10:00");
        JsonNode p = list("");
        assertThat(p.get("items")).isEmpty();
        assertThat(p.get("page").asInt()).isEqualTo(1);
        assertThat(p.get("size").asInt()).isEqualTo(20);
        assertThat(p.get("totalItems").asLong()).isZero();
        assertThat(p.get("totalPages").asInt()).isZero();
    }

    @Test
    @DisplayName("AC11 只读：各种查询（含错误请求）之后现金汇总、流水、交易数都不变")
    void ac11_readOnly() {
        baseline();
        JsonNode ledgerBefore = ledger(1, 100);
        for (String q : List.of("", "symbol=aapl", "action=STO,BTO", "startDate=2026-11-03", "page=2&size=1",
                "strike=200", "page=99")) {
            list(q);
        }
        listError("strike=abc", 400, "BAD_REQUEST");
        listError("startDate=2026-11-04&endDate=2026-11-03", 400, "VALIDATION_ERROR");
        assertSummary("99379.75", "44500.00", "54879.75");
        assertThat(ledger(1, 100)).isEqualTo(ledgerBefore);
        assertThat(tradeCount()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ errors

    @Test
    @DisplayName("错误优先级：既有无法解析又有不满足规则时只报 BAD_REQUEST；同类问题一起返回、按 field 排序")
    void errorPrecedenceAndAggregation() {
        baseline();
        Resp mixed = listError("strike=abc&symbol=$X&startDate=2026-11-04&endDate=2026-11-03&page=0", 400, "BAD_REQUEST");
        assertThat(details(mixed)).containsExactly("strike=行权价必须是数字");

        Resp badMany = listError("optionType=x&action=BTO,NOPE&expirationDate=bad", 400, "BAD_REQUEST");
        assertThat(detailFields(badMany)).containsExactlyInAnyOrder("optionType", "action", "expirationDate");
        assertThat(detailFields(badMany)).isSorted();

        Resp valMany = listError("symbol=$X&strike=-1&startDate=2026-11-04&endDate=2026-11-03&size=500", 400,
                "VALIDATION_ERROR");
        assertThat(details(valMany)).containsExactlyInAnyOrder(
                "symbol=标的只能包含字母、数字和点", "strike=行权价必须大于 0",
                "endDate=开始日期不能晚于结束日期", "size=size 必须在 1 到 100 之间");
        assertThat(detailFields(valMany)).isSorted();
    }
}
