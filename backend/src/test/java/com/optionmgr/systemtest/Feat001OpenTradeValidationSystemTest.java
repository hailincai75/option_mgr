package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FEAT-001 acceptance 12 (field validation) and 19 (upper limits), check order fields → Q7 → Q23 → cash (Q16 superseded by FEAT-038),
 * Q7/Q23 across later time points, 0DTE / NY-date boundaries, error contract, GET errors and concurrency.
 */
class Feat001OpenTradeValidationSystemTest extends OptionTradeSystemTestBase {

    static final String DEP = "2026-11-02T10:00";
    static final String AT = "2026-11-10T10:30";

    /** A valid BTO with enough cash for everything in this class. */
    ObjectNode validBto() {
        return aapl("BTO", "210", "CALL", "1.00", AT);
    }

    void fund() {
        deposit("100000.00", DEP);
    }

    // ------------------------------------------------------------------ AC 12

    @ParameterizedTest(name = "AC12 {0} = {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "contracts|0|张数必须是正整数",
            "contracts|-1|张数必须是正整数",
            "contracts|1.5|张数必须是正整数",
            "netAmount|\"0\"|净额必须大于 0",
            "netAmount|\"-10.00\"|净额必须大于 0",
            "netAmount|\"349.355\"|净额最多 2 位小数",
            "strike|\"0\"|行权价必须大于 0",
            "strike|\"-5\"|行权价必须大于 0",
            "strike|\"200.1234\"|行权价最多 3 位小数",
            "multiplier|0|multiplier 必须是正整数",
            "multiplier|10.5|multiplier 必须是正整数",
            "symbol|\"\"|标的不能为空",
            "symbol|\"   \"|标的不能为空",
            "symbol|\"AA PL\"|标的只能包含字母、数字和点",
            "symbol|\"$AAPL\"|标的只能包含字母、数字和点",
            "symbol|\"ABCDEFGHIJK\"|标的最多 10 个字符",
            "tradedAtLocal|\"2026-11-12T10:01\"|成交时间不能晚于当前时间",
            "expirationDate|\"2026-11-09\"|到期日不能早于成交日（2026-11-10）",
            "action|\"BTC\"|本功能只支持开仓（BTO / STO），平仓请使用平仓功能",
            "action|\"STC\"|本功能只支持开仓（BTO / STO），平仓请使用平仓功能",
    })
    void ac12_fieldValidation(String field, String rawJsonValue, String message) throws Exception {
        fund();
        ObjectNode b = validBto();
        b.set(field, json.readTree(rawJsonValue));
        validationError(b, field, message);
    }

    @Test
    @DisplayName("AC12 备注 501 字符被拒，500 字符允许")
    void ac12_noteLength() {
        fund();
        ObjectNode b = validBto();
        b.put("note", "x".repeat(501));
        validationError(b, "note", "备注最多 500 个字符");
        b.put("note", "冲".repeat(500));
        assertThat(okTrade(b).get("note").asText()).hasSize(500);
    }

    @ParameterizedTest(name = "AC12 缺少 {0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "symbol|标的不能为空",
            "expirationDate|到期日不能为空",
            "strike|行权价不能为空",
            "optionType|请选择 Call 或 Put",
            "action|请选择方向",
            "contracts|张数不能为空",
            "netAmount|净额不能为空",
            "tradedAtLocal|成交时间不能为空",
    })
    void ac12_requiredFields(String field, String message) {
        fund();
        ObjectNode b = validBto();
        b.remove(field);
        validationError(b, field, message);
        ObjectNode n = validBto();
        n.putNull(field);
        validationError(n, field, message);
    }

    @Test
    @DisplayName("multiplier 省略或 null → 100")
    void multiplierDefaults() {
        fund();
        ObjectNode b = validBto();
        b.remove("multiplier");
        assertThat(okTrade(b).get("multiplier").asInt()).isEqualTo(100);
        ObjectNode n = validBto();
        n.putNull("multiplier");
        assertThat(okTrade(n).get("multiplier").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("AC12 0DTE：成交 2026-12-18 10:00，到期 2026-12-18 允许")
    void ac12_zeroDte() {
        clock.set(Instant.parse("2026-12-18T16:00:00Z"));
        fund();
        JsonNode t = okTrade(aapl("BTO", "210", "CALL", "1.00", "2026-12-18T10:00"));
        assertThat(t.get("expirationDate").asText()).isEqualTo("2026-12-18");
    }

    @Test
    @DisplayName("成交日取美东日期（FEAT-012 Q83 收紧）：到期日 16:00:00 允许；NY 12-18 23:59（UTC 已是 12-19）、"
            + "NY 11-09 21:00（UTC 11-10）按美东日期 = 到期日判断，晚于 16:00 被拒（16:00 提示，不是成交日提示）")
    void tradeDateIsNewYorkDate() {
        clock.set(Instant.parse("2026-12-20T00:00:00Z"));
        fund();
        // Before FEAT-012 (Q36) any time on the expiration date was allowed; Q83 caps it at 16:00:00 New York.
        validationError(aapl("BTO", "210", "CALL", "1.00", "2026-12-18T23:59"),
                "tradedAtLocal", "成交时间不能晚于到期日（2026-12-18）美东 16:00");
        validationError(aapl("BTO", "210", "CALL", "1.00", "2026-12-18T16:00:01"),
                "tradedAtLocal", "成交时间不能晚于到期日（2026-12-18）美东 16:00");
        JsonNode atCutoff = okTrade(aapl("BTO", "210", "CALL", "1.00", "2026-12-18T16:00:00"));
        assertThat(atCutoff.get("tradedAt").asText()).isEqualTo("2026-12-18T21:00:00Z");
        ObjectNode early = trade("BTO", 1, "F", "2026-11-09", "10", "CALL", "1.00", "2026-11-09T21:00", 100);
        validationError(early, "tradedAtLocal", "成交时间不能晚于到期日（2026-11-09）美东 16:00");
        ObjectNode earlyCutoff = trade("BTO", 1, "F", "2026-11-09", "10", "CALL", "1.00", "2026-11-09T16:00", 100);
        assertThat(okTrade(earlyCutoff).get("tradedAt").asText()).isEqualTo("2026-11-09T21:00:00Z");
        // and the day after is rejected with the NY trade date in the message
        ObjectNode tooLate = trade("BTO", 1, "G", "2026-11-09", "10", "CALL", "1.00", "2026-11-10T00:30", 100);
        validationError(tooLate, "expirationDate", "到期日不能早于成交日（2026-11-10）");
    }

    @Test
    @DisplayName("补录已过期合约的开仓（2026-10-01 开仓、到期 2026-10-16）允许；周末到期日被拒（FEAT-009 Q126，原为不检查）")
    void expiredContractAndWeekendExpiry() {
        deposit("100000.00", "2026-09-01T10:00");
        okTrade(trade("BTO", 1, "AAPL", "2026-10-16", "210", "CALL", "1.00", "2026-10-01T10:00", 100));
        assertThat(rejectedTrade(trade("BTO", 1, "AAPL", "2026-10-17", "210", "CALL", "1.00", "2026-10-01T10:00", 100),
                "EXPIRATION_NOT_TRADING_DAY")).isEqualTo("到期日 2026-10-17 不是美股交易日（星期六）");
        assertThat(rejectedTrade(trade("BTO", 1, "AAPL", "2026-12-19", "210", "CALL", "1.00", "2026-11-07T10:00", 100),
                "EXPIRATION_NOT_TRADING_DAY")).isEqualTo("到期日 2026-12-19 不是美股交易日（星期六）");
        assertSummary("99999.00", "0.00", "99999.00");
    }

    @Test
    @DisplayName("字段错误：时间相关两项在其它字段全部通过后才检查；两项可同时出现")
    void timeChecksComeAfterOtherFields() {
        fund();
        ObjectNode b = validBto();
        b.put("contracts", 0);
        b.put("tradedAtLocal", "2026-11-13T10:00");
        b.put("expirationDate", "2026-11-01");
        validationError(b, "contracts", "张数必须是正整数");

        ObjectNode both = validBto();
        both.put("tradedAtLocal", "2026-11-13T10:00");
        both.put("expirationDate", "2026-11-12");
        validationError(both, "tradedAtLocal", "成交时间不能晚于当前时间",
                "expirationDate", "到期日不能早于成交日（2026-11-13）");
    }

    @Test
    @DisplayName("同一字段多条 message：strike -5.1234 → 必须大于 0 + 最多 3 位小数；多字段 details 按 field 排序")
    void multipleMessagesAndSorting() {
        fund();
        ObjectNode b = validBto();
        b.put("strike", "-5.1234");
        b.put("symbol", "$X");
        b.put("contracts", 0);
        validationError(b, "strike", "行权价必须大于 0", "strike", "行权价最多 3 位小数",
                "symbol", "标的只能包含字母、数字和点", "contracts", "张数必须是正整数");
        Resp r = postTrade(b);
        assertThat(detailFields(r)).isSorted();
    }

    @ParameterizedTest(name = "无法解析 {0}={1} → BAD_REQUEST")
    @CsvSource(delimiter = '|', value = {
            "optionType|\"P\"",
            "optionType|\"put\"",
            "action|\"SELL\"",
            "strike|\"abc\"",
            "netAmount|\"abc\"",
            "contracts|\"abc\"",
            "multiplier|\"abc\"",
            "expirationDate|\"2026-02-30\"",
            "expirationDate|\"2026/12/18\"",
            "tradedAtLocal|\"2026-02-30T10:00\"",
            "tradedAtLocal|\"2026-11-10T10:30Z\"",
    })
    void unparseable(String field, String rawJsonValue) throws Exception {
        fund();
        ObjectNode b = validBto();
        b.set(field, json.readTree(rawJsonValue));
        badRequest(b.toString(), field);
    }

    @Test
    @DisplayName("JSON 语法错误 → BAD_REQUEST details []")
    void malformedJson() {
        Resp r = postRaw(TRADES, "{\"symbol\":", org.springframework.http.MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "BAD_REQUEST", TRADES);
        assertThat(r.body().get("details")).isEmpty();
    }

    // ------------------------------------------------------------------ AC 19 (Q29)

    @ParameterizedTest(name = "AC19 超上限 {0}={1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "strike|\"10000000\"|行权价过大",
            "strike|\"10000000.000\"|行权价过大",
            "contracts|100001|张数过大",
            "multiplier|10001|multiplier 过大",
            "netAmount|\"10000000000000.00\"|净额过大",
    })
    void ac19_overLimits(String field, String rawJsonValue, String message) throws Exception {
        fund();
        ObjectNode b = validBto();
        b.set(field, json.readTree(rawJsonValue));
        validationError(b, field, message);
        assertSummary("100000.00", "0.00", "100000.00");
    }

    @Test
    @DisplayName("AC19 等于上限允许：行权价 9999999.999；100,000 张 × multiplier 10,000 → 99,998.00")
    void ac19_atLimits() {
        fund();
        JsonNode a = okTrade(aapl("BTO", "9999999.999", "CALL", "1.00", AT));
        assertThat(a.get("strike").asText()).isEqualTo("9999999.999");
        assertThat(a.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 9999999.999 C");
        JsonNode b = okTrade(trade("BTO", 100000, "MSFT", "2026-12-18", "300", "CALL", "1.00", AT, 10000));
        assertThat(b.get("contracts").asInt()).isEqualTo(100000);
        assertThat(b.get("multiplier").asInt()).isEqualTo(10000);
        assertSummary("99998.00", "0.00", "99998.00");
    }

    @Test
    @DisplayName("Q29 上限组合：STO put 9999999.999 × 10000 × 100000 担保不会溢出，按现金不足拒绝")
    void hugeCollateralDoesNotOverflow() {
        fund();
        String msg = rejectedTrade(trade("STO", 100000, "BIG", "2026-12-18", "9999999.999", "PUT", "1.00", AT, 10000),
                "CASH_INSUFFICIENT");
        assertThat(msg).isEqualTo("可用现金不足以担保卖出 put：需要 9,999,999,999,000,000.00，可用 100,000.00");
    }

    @Test
    @DisplayName("净额等于上限 9999999999999.99 的 STO put 允许")
    void netAmountAtLimit() {
        fund();
        JsonNode t = okTrade(trade("STO", 1, "F", "2026-12-18", "10", "PUT", "9999999999999.99", AT, 100));
        assertThat(t.get("cashChange").asText()).isEqualTo("9999999999999.99");
        assertSummary("10000000099999.99", "1000.00", "10000000098999.99");
    }

    // ------------------------------------------------------------------ check order

    @Test
    @DisplayName("检查顺序（FEAT-038 后为 字段 → Q7 → Q23 → 现金）：字段先于 Q7；Q7 先于现金；Q23 先于现金；STO call 不做现金/担保检查")
    void checkOrder() {
        deposit("30000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", AT));
        // invalid field + conflicting direction -> 400
        ObjectNode bad = aapl("BTO", "200", "PUT", "0", "2026-11-10T11:00");
        validationError(bad, "netAmount", "净额必须大于 0");
        // conflict + not enough cash -> Q7
        assertThat(rejectedTrade(aapl("BTO", "200", "PUT", "999999.00", "2026-11-10T11:00"),
                "POSITION_DIRECTION_CONFLICT")).contains("空头持仓");

        emptyLedger();
        // FEAT-038 (Q16 superseded): an STO call with zero cash is accepted - no collateral, nothing frozen
        // (FEAT-039: it needs 100 shares, a 转入 has no cash effect)
        transferInShares("AAPL", 100, "2026-11-10");
        assertThat(okTrade(aapl("STO", "250", "CALL", "1.00", AT)).get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("1.00", "0.00", "1.00");
        emptyLedger();
        // multiplier mismatch with zero cash for the new one -> Q23 not cash
        deposit("100.00", DEP);
        okTrade(trade("BTO", 1, "XYZ", "2026-12-18", "50", "CALL", "10.00", AT, 10));
        rejectedTrade(trade("BTO", 1, "XYZ", "2026-12-18", "50", "CALL", "500.00", "2026-11-10T11:00", 100),
                "MULTIPLIER_MISMATCH");
    }

    // ------------------------------------------------------------------ Q7 / Q23 boundaries

    @Test
    @DisplayName("Q7 同一时刻：已有 STO 10:30，新 BTO 10:30 排在其后 → 成交时为空头")
    void sameInstantOrdering() {
        deposit("30000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", AT));
        assertThat(rejectedTrade(aapl("BTO", "200", "PUT", "300.00", AT), "POSITION_DIRECTION_CONFLICT"))
                .startsWith("该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（1 张）");
    }

    @Test
    @DisplayName("Q27 多笔之后的交易：报告第一笔会冲突的已有交易；持仓张数为累计值")
    void q27_reportsFirstLaterConflict() {
        deposit("100000.00", DEP);
        okTrade(aapl("BTO", "200", "PUT", "100.00", "2026-11-03T10:00"));
        okTrade(aapl("BTO", "200", "PUT", "100.00", "2026-11-04T10:00"));
        // STO before both longs: first later trade (11-03 BTO) would face a short position
        assertThat(rejectedTrade(aapl("STO", "200", "PUT", "1.00", "2026-11-02T11:00"), "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-03 10:00 的 BTO 将与空头持仓（1 张）冲突；一个合约任一时刻只能纯多或纯空");
        // STO after both longs: conflict at its own point, position 2
        assertThat(rejectedTrade(aapl("STO", "200", "PUT", "1.00", "2026-11-05T10:00"), "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时为多头持仓（2 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）");
        // same direction backfill is fine
        okTrade(aapl("BTO", "200", "PUT", "100.00", "2026-11-02T11:00"));
        assertThat(tradeCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Q27 只看同一合约：其它合约的之后交易不受影响")
    void q27_otherContractsIgnored() {
        deposit("100000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", "2026-11-11T10:00"));
        okTrade(aapl("BTO", "205", "PUT", "300.00", "2026-11-10T10:00"));
        okTrade(trade("BTO", 1, "AAPL", "2027-01-15", "200", "PUT", "300.00", "2026-11-10T10:00", 100));
        okTrade(aapl("BTO", "200", "CALL", "300.00", "2026-11-10T10:00"));
        assertThat(tradeCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("行权价按数值比较：200 与 200.000 是同一合约")
    void strikeComparedNumerically() {
        deposit("50000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", AT));
        assertThat(rejectedTrade(aapl("BTO", "200.000", "PUT", "1.00", "2026-11-10T11:00"),
                "POSITION_DIRECTION_CONFLICT")).contains("AAPL 2026-12-18 200 P");
        JsonNode t = okTrade(aapl("STO", "200.0", "PUT", "1.00", "2026-11-10T11:00")
                .put("netAmount", "1.00"));
        assertThat(t.get("strike").asText()).isEqualTo("200");
    }

    @Test
    @DisplayName("Q23 多头同样检查 multiplier；不同合约 multiplier 可不同")
    void q23_longSideAndOtherContracts() {
        deposit("100000.00", DEP);
        okTrade(trade("BTO", 1, "XYZ", "2026-12-18", "50", "CALL", "10.00", AT, 10));
        assertThat(rejectedTrade(trade("BTO", 1, "XYZ", "2026-12-18", "50", "CALL", "10.00", "2026-11-10T11:00", 100),
                "MULTIPLIER_MISMATCH"))
                .isEqualTo("该合约（XYZ 2026-12-18 50 C）已有未平仓持仓，multiplier 为 10，新开仓的 multiplier 必须相同");
        okTrade(trade("BTO", 1, "XYZ", "2026-12-18", "55", "CALL", "10.00", "2026-11-10T11:00", 100));
        okTrade(trade("BTO", 1, "XYZ", "2026-12-18", "50", "CALL", "10.00", "2026-11-10T11:00", 10));
    }

    @Test
    @DisplayName("Q18 边界：可用恰好等于担保 → 允许，可用变为权利金")
    void q18_exactCollateral() {
        deposit("2500.00", DEP);
        okTrade(trade("STO", 2, "F", "2026-12-18", "12.5", "PUT", "48.70", AT, 100));
        assertSummary("2548.70", "2500.00", "48.70");
        // premium of the previous STO counts now (it is before the new one), but not the new premium
        assertThat(rejectedTrade(trade("STO", 1, "G", "2026-12-18", "0.5", "PUT", "10.00", "2026-11-10T11:00", 100),
                "CASH_INSUFFICIENT")).isEqualTo("可用现金不足以担保卖出 put：需要 50.00，可用 48.70");
    }

    // ------------------------------------------------------------------ note, GET errors

    @Test
    @DisplayName("备注：空白存 null；流水 remark = 备注")
    void noteHandling() {
        fund();
        ObjectNode b = validBto();
        b.put("note", "   ");
        JsonNode t = okTrade(b);
        assertThat(t.get("note").isNull()).isTrue();
        assertThat(ledgerRowFor("OPTION_OPEN", t.get("id").asLong()).get("remark").isNull()).isTrue();
    }

    @Test
    @DisplayName("GET /api/option-trades/{id}：不存在 404，非整数 400 BAD_REQUEST field id")
    void getErrors() {
        Resp missing = get(TRADES + "/99");
        assertErrorShape(missing, 404, "NOT_FOUND", TRADES + "/99");
        assertThat(missing.body().get("message").asText()).isEqualTo("Option trade 99 not found");
        Resp abc = get(TRADES + "/abc");
        assertErrorShape(abc, 400, "BAD_REQUEST", TRADES + "/abc");
        assertThat(detailFields(abc)).containsExactly("id");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("并发：可用 20,000，5 个并发 STO put 各需担保 20,000 → 恰好 1 笔成功")
    void concurrentStoPuts() throws Exception {
        deposit("20000.00", DEP);
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postTrade(aapl("STO", "200", "PUT", "1.00", AT));
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Resp> f : futures) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).containsOnly(201, 422);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertSummary("20001.00", "20000.00", "1.00");
        assertThat(tradeCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(2);
    }
}
