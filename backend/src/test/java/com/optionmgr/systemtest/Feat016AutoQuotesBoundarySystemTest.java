package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-016: boundaries, decisions Q143–Q149 and the override / test-support contract.
 * See {@link Feat016QuoteSystemTestBase} for the scenario and the fakes.
 */
@DisplayName("FEAT-016 自动行情（边界与校验）")
class Feat016AutoQuotesBoundarySystemTest extends Feat016QuoteSystemTestBase {

    // ============================================================================================== Q149 index symbols

    @Test
    @DisplayName("Q149 指数标的（SPX、SPXW）不请求、不计入跟踪；期权与标的价都无报价、无失败标签；可手动覆盖并一直保留")
    void indexSymbolsAreNotFetched() {
        scenario();
        deposit("5000.00", "2026-11-16T11:00");
        open("BTO", "SPX", "2026-12-18", "7000", "CALL", "500.00", "2026-11-20T11:00");
        open("BTO", "SPXW", "2026-12-18", "7100", "CALL", "400.00", "2026-11-20T11:01");
        sources().cboe("QRTV", qrtvOk()).cboe("IXQZ", ixqzOk())
                .cboe("SPX", ok(cboeBody("SPX", "6800", "2026-11-23 09:45:00",
                        List.of(option("SPX261218C07000000", "bid", "20", "ask", "21")))))
                .cboe("SPXW", ok(cboeBody("SPXW", "6800", "2026-11-23 09:45:00",
                        List.of(option("SPXW261218C07100000", "bid", "10", "ask", "11")))))
                .finnhub("SPX", finnhubOk("6800", "2026-11-23T09:59:00"))
                .finnhub("SPXW", finnhubOk("6800", "2026-11-23T09:59:00")).apply();

        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(requests()).containsExactlyInAnyOrder("CBOE QRTV", "CBOE IXQZ");
        assertThat(results(t)).extracting(s -> s.split(" ")[0]).containsExactly("IXQZ", "QRTV");
        assertThat(t.get("status").get("trackedSymbolCount").asInt()).isEqualTo(2);

        for (String c : List.of("SPX 2026-12-18 7000 C", "SPXW 2026-12-18 7100 C")) {
            JsonNode q = optionQuote("1", c);
            assertThat(shown(q)).as(c).isEqualTo("null/null null null NEVER_QUOTED");
            assertThat(q.get("fetchFailed").asBoolean()).isFalse();
            assertThat(q.get("staleLabel").isNull()).isTrue();
            JsonNode u = underlyingQuote("1", c);
            assertThat(u.get("price").isNull()).isTrue();
            assertThat(u.get("fetchFailed").asBoolean()).isFalse();
            assertThat(u.get("staleLabel").isNull()).isTrue();
        }

        setNow("2026-11-23T10:05");
        overrideMark("SPX", "2026-12-18", "7000", "CALL", "25.50");
        overrideUnderlying("SPX", "6810.25");
        tickAt("2026-11-23T10:15", "SCHEDULED");
        tickAt("2026-11-23T10:30", "SCHEDULED");
        JsonNode q = optionQuote("1", "SPX 2026-12-18 7000 C");
        assertThat(q.get("mark").asText()).isEqualTo("25.50");
        assertThat(q.get("manual").asBoolean()).isTrue();
        assertThat(q.get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(underlyingQuote("1", "SPX 2026-12-18 7000 C").get("price").asText()).isEqualTo("6810.25");
        assertThat(requests()).noneMatch(r -> r.contains("SPX"));

        assertThat(deleteMarkOverride("?symbol=SPX&expirationDate=2026-12-18&strike=7000&optionType=CALL").status())
                .isEqualTo(204);
        assertThat(deleteUnderlyingOverride("SPX").status()).isEqualTo(204);
        assertThat(optionQuote("1", "SPX 2026-12-18 7000 C").get("mark").isNull()).isTrue();
        assertThat(underlyingQuote("1", "SPX 2026-12-18 7000 C").get("price").isNull()).isTrue();
    }

    @Test
    @DisplayName("Q149/Q146 只有指数持仓：跟踪标的 0，从未拉取也不显示顶部提示；tick 照常运行（空跟踪 = 成功 run）")
    void onlyIndexPositionsNoWarning() {
        setNow("2026-11-23T09:50");
        deposit("5000.00", "2026-11-16T11:00");
        open("BTO", "SPX", "2026-12-18", "7000", "CALL", "500.00", "2026-11-20T11:00");
        JsonNode s = status();
        assertThat(s.get("trackedSymbolCount").asInt()).isZero();
        assertThat(s.get("stale").asBoolean()).isFalse();

        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(t.get("results")).isEmpty();
        assertThat(t.get("status").get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(requests()).isEmpty();
    }

    // ============================================================================================== OCC matching

    @Test
    @DisplayName("四要素歧义（QRTV… 与 B1QRTV… 同时匹配）：从未报价 → 无报价（不算失败，failureDetail 注明 ambiguous）；曾有报价 → 数据源已无该合约，手动 mark 保留")
    void ambiguousMatch() {
        scenario();
        String b1 = "B1QRTV261218P00050000";
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 09:45:00",
                        List.of(p1Option(), option(b1, "bid", "0.40", "ask", "0.50")))))
                .cboe("IXQZ", ixqzOk()).apply();
        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(results(t)).contains("QRTV SUCCESS SUCCESS null CBOE");
        JsonNode qrtv = t.get("results").get(1);
        assertThat(qrtv.get("failureDetail").asText()).contains("ambiguous").contains(OCC_P1).contains(b1);
        JsonNode p1 = optionQuote("1", P1);
        assertThat(shown(p1)).isEqualTo("null/null null null NEVER_QUOTED");
        assertThat(p1.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p1.get("staleLabel").isNull()).isTrue();
        assertThat(underlyingQuote("1", P1).get("price").asText()).as("price still updated").isEqualTo("52.40");

        // quoted once, then ambiguous → treated as not in the chain
        scenarioSources();
        tickAt("2026-11-23T10:15", "SCHEDULED");
        assertThat(optionQuote("1", P1).get("contractStatus").asText()).isEqualTo("QUOTED");
        setNow("2026-11-23T10:20");
        overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 10:15:00",
                        List.of(p1Option("1.50", "1.60"), option(b1, "bid", "0.40", "ask", "0.50")))))
                .cboe("IXQZ", ixqzOk()).apply();
        tickAt("2026-11-23T10:30", "SCHEDULED");
        p1 = optionQuote("1", P1);
        assertThat(p1.get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(p1.get("contractStatusLabel").asText()).isEqualTo("数据源已无该合约（最后报价：11-23 09:45）");
        assertThat(p1.get("automaticMark").asText()).isEqualTo("1.25");
        assertThat(p1.get("mark").asText()).as("Q148: not in the chain → manual kept").isEqualTo("1.40");
        assertThat(p1.get("manual").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("OCC 匹配：root 以标的结尾（B1QRTV）可匹配；调整合约 root（QRTV1）不干扰；行权价 47.5 = 00047500")
    void occRootMatching() {
        scenario();
        open("STO", "QRTV", "2026-12-18", "47.5", "PUT", "100.00", "2026-11-20T10:20");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 09:45:00", List.of(
                        option("B1QRTV261218P00050000", "bid", "1.20", "ask", "1.30"),
                        option("QRTV1261218P00047500", "bid", "9.00", "ask", "9.50"),
                        option(OCC_P4, "bid", "0.80", "ask", "0.90")))))
                .cboe("IXQZ", ixqzOk()).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(shown(optionQuote("1", P1))).isEqualTo("1.20/1.30 1.25 2026-11-23T09:45:00 QUOTED");
        assertThat(shown(optionQuote("1", P4))).isEqualTo("0.80/0.90 0.85 2026-11-23T09:45:00 QUOTED");
        assertThat(storedContract("QRTV", "2026-12-18", "47.5", "PUT").get("occCode").asText()).isEqualTo(OCC_P4);
    }

    // ============================================================================================== mark format

    @Test
    @DisplayName("Q139 / 显示格式：中间价不四舍五入（1.255、0.035），至少 2 位小数（1.50）；ask 为 0 → theo；bid/ask/theo 都没有 → 无报价")
    void markFormat() {
        scenario();
        open("STO", "QRTV", "2026-12-18", "47.5", "PUT", "100.00", "2026-11-20T10:20");
        accountId = accountB;
        open("BTO", "IXQZ", "2026-12-18", "65", "PUT", "100.00", "2026-11-20T10:30");
        accountId = "1";
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.4", "2026-11-23 09:45:00", List.of(
                        option(OCC_P1, "bid", "1.20", "ask", "1.31"),
                        option(OCC_P4, "bid", "0.03", "ask", "0.04")))))
                .cboe("IXQZ", ok(cboeBody("IXQZ", "61", "2026-11-23 09:45:00", List.of(
                        option(OCC_P2, "bid", "1", "ask", "2"),
                        option("IXQZ261218P00065000", "bid", "0.50", "ask", "0", "theo", "0.6"))))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.255");
        assertThat(optionQuote("1", P1).get("ask").asText()).isEqualTo("1.31");
        assertThat(optionQuote("1", P4).get("mark").asText()).isEqualTo("0.035");
        JsonNode p2 = optionQuote(accountB, P2);
        assertThat(p2.get("mark").asText()).isEqualTo("1.50");
        assertThat(p2.get("bid").asText()).isEqualTo("1.00");
        assertThat(p2.get("ask").asText()).isEqualTo("2.00");
        JsonNode put = optionQuote(accountB, "IXQZ 2026-12-18 65 P");
        assertThat(put.get("mark").asText()).isEqualTo("0.60");
        assertThat(put.get("markBasis").asText()).isEqualTo("THEO");
        assertThat(put.get("ask").asText()).isEqualTo("0.00");
        assertThat(underlyingQuote("1", P1).get("price").asText()).isEqualTo("52.40");
        assertThat(stockQuote(accountB, "IXQZ").get("price").asText()).isEqualTo("61.00");
    }

    // ============================================================================================== CBOE / Finnhub failures

    @Test
    @DisplayName("CBOE 无 current_price：链仍成功（SUCCESS），标的价用 Finnhub（来源 FINNHUB）")
    void cboeWithoutPriceUsesFinnhub() {
        scenario();
        sources().cboe("QRTV", ok(cboeBody("QRTV", null, "2026-11-23 09:45:00", List.of(p1Option()))))
                .cboe("IXQZ", ixqzOk()).finnhub("QRTV", finnhubOk("52.33", "2026-11-23T09:59:00")).apply();
        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(results(t)).contains("QRTV SUCCESS SUCCESS SUCCESS FINNHUB");
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.25");
        JsonNode u = underlyingQuote("1", P1);
        assertThat(u.get("price").asText()).isEqualTo("52.33");
        assertThat(u.get("source").asText()).isEqualTo("FINNHUB");
        assertThat(u.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:59:00");
    }

    @Test
    @DisplayName("响应格式变化（非 JSON、缺 timestamp、缺 data.options）→ INVALID_RESPONSE，只算该标的失败；Finnhub c=0 → INVALID_RESPONSE；另一个标的照常更新")
    void invalidResponses() {
        scenario();
        ObjectNode noTimestamp = cboeBody("IXQZ", "61.10", "2026-11-23 09:45:00", List.of(p2Option()));
        noTimestamp.remove("timestamp");
        ObjectNode noOptions = cboeBody("IXQZ", "61.10", "2026-11-23 09:45:00", List.of(p2Option()));
        ((ObjectNode) noOptions.get("data")).remove("options");
        ObjectNode badTimestamp = cboeBody("IXQZ", "61.10", "23/11/2026 09:45", List.of(p2Option()));
        ObjectNode zero = json.createObjectNode();
        zero.put("c", 0).put("pc", 0).put("t", 0);

        String[] slots = {"10:00", "10:15", "10:30", "10:45"};
        List<ObjectNode> bad = List.of(rawBody("<html>maintenance</html>"), ok(noTimestamp), ok(noOptions),
                ok(badTimestamp));
        for (int i = 0; i < bad.size(); i++) {
            sources().cboe("QRTV", qrtvOk()).cboe("IXQZ", bad.get(i)).finnhub("IXQZ", ok(zero)).apply();
            JsonNode t = tickAt("2026-11-23T" + slots[i], "SCHEDULED");
            assertThat(results(t)).as("case %d", i).containsExactly(
                    "IXQZ FAILED INVALID_RESPONSE INVALID_RESPONSE null", "QRTV SUCCESS SUCCESS null CBOE");
        }
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.25");
        JsonNode p2 = optionQuote(accountB, P2);
        assertThat(p2.get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(p2.get("staleLabel").asText()).isEqualTo(NEVER_SUCCEEDED_ROW);
        assertThat(stockQuote(accountB, "IXQZ").get("price").isNull()).isTrue();
    }

    // ============================================================================================== Q148 / overrides

    @Test
    @DisplayName("Q148 手动 mark：链中自动 mark 为空（bid 0、无 theo）时保留；之后链中有可用 mark 才被替换")
    void manualMarkKeptWhenAutomaticNull() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        setNow("2026-11-23T10:05");
        overrideMark("IXQZ", "2026-12-18", "60", "CALL", "2.50");

        sources().cboe("QRTV", qrtvOk()).cboe("IXQZ", ok(cboeBody("IXQZ", "61.10", "2026-11-23 10:00:00",
                List.of(option(OCC_P2, "bid", "0", "ask", "0.05"))))).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");
        JsonNode p2 = optionQuote(accountB, P2);
        assertThat(p2.get("mark").asText()).isEqualTo("2.50");
        assertThat(p2.get("automaticMark").isNull()).isTrue();
        assertThat(p2.get("manual").asBoolean()).isTrue();
        assertThat(p2.get("bid").asText()).isEqualTo("0.00");

        scenarioSources();
        tickAt("2026-11-23T10:30", "SCHEDULED");
        p2 = optionQuote(accountB, P2);
        assertThat(p2.get("mark").asText()).isEqualTo("2.20");
        assertThat(p2.get("manual").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Q148 / 规则 8：CBOE 失败但 Finnhub 成功 → 手动标的价被 Finnhub 值替换，手动 mark 保留（链失败）")
    void underlyingOverrideReplacedByFinnhub() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        setNow("2026-11-23T10:05");
        overrideUnderlying("QRTV", "52.00");
        overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");

        sources().cboe("QRTV", httpError(503)).finnhub("QRTV", finnhubOk("52.10", "2026-11-23T10:14:00"))
                .cboe("IXQZ", ixqzOk()).apply();
        JsonNode t = tickAt("2026-11-23T10:15", "SCHEDULED");
        assertThat(results(t)).contains("QRTV FALLBACK HTTP_ERROR SUCCESS FINNHUB");
        JsonNode u = underlyingQuote("1", P1);
        assertThat(u.get("price").asText()).isEqualTo("52.10");
        assertThat(u.get("source").asText()).isEqualTo("FINNHUB");
        assertThat(u.get("manual").asBoolean()).isFalse();
        assertThat(u.get("fetchFailed").asBoolean()).isFalse();
        JsonNode p1 = optionQuote("1", P1);
        assertThat(p1.get("mark").asText()).isEqualTo("1.40");
        assertThat(p1.get("manual").asBoolean()).isTrue();
        assertThat(p1.get("fetchFailed").asBoolean()).isTrue();
        assertThat(p1.get("staleLabel").asText()).isEqualTo("行情未更新（上次：11-23 10:00）");
    }

    @Test
    @DisplayName("手动覆盖：从未报价的合约也可设；清除幂等（无覆盖也 204）；strike 50.0 与 50 相同；同一标的再次设置替换")
    void overrideSetClearReplace() {
        scenario();
        setNow("2026-11-23T10:05");
        JsonNode o = overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");
        assertThat(o.get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(o.get("bid").isNull()).isTrue();
        assertThat(o.get("automaticMark").isNull()).isTrue();
        assertThat(optionQuote(accountB, P3).get("mark").asText()).isEqualTo("1.40");

        setNow("2026-11-23T10:07");
        o = overrideMark("QRTV", "2026-12-18", "50.0", "PUT", "1.45");
        assertThat(o.get("mark").asText()).isEqualTo("1.45");
        assertThat(o.get("manualEnteredAtLocal").asText()).isEqualTo("2026-11-23T10:07:00");

        overrideUnderlying("IXQZ", "61.5");
        assertThat(stockQuote(accountB, "IXQZ").get("price").asText()).isEqualTo("61.50");
        assertThat(stockQuote(accountB, "IXQZ").get("manual").asBoolean()).isTrue();

        assertThat(deleteMarkOverride("?symbol=QRTV&expirationDate=2026-12-18&strike=50.0&optionType=PUT").status())
                .isEqualTo(204);
        assertThat(optionQuote("1", P1).get("mark").isNull()).isTrue();
        assertThat(optionQuote("1", P1).get("manual").asBoolean()).isFalse();
        assertThat(deleteMarkOverride("?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT").status())
                .isEqualTo(204);
        assertThat(deleteUnderlyingOverride("IXQZ").status()).isEqualTo(204);
        assertThat(deleteUnderlyingOverride("IXQZ").status()).isEqualTo(204);
        assertThat(deleteUnderlyingOverride("ZZQX").status()).as("no check whether held").isEqualTo(204);
        assertThat(stockQuote(accountB, "IXQZ").get("price").isNull()).isTrue();
    }

    @Test
    @DisplayName("手动覆盖校验：缺价格 / ≤0 / 负数 / 超 4 位小数 / 过大 → VALIDATION_ERROR；非数字、错日期、错类型、缺 query → BAD_REQUEST；未持有 → 422 QUOTE_TARGET_NOT_HELD")
    void overrideValidation() {
        scenario();
        setNow("2026-11-23T10:05");
        String up = OVERRIDE_UNDERLYINGS + "QRTV";

        Object[][] priceCases = {
                {"{}", "请输入价格"},
                {"{\"price\":null}", "请输入价格"},
                {"{\"price\":\"0\"}", "价格必须大于 0"},
                {"{\"price\":\"-1.00\"}", "价格必须大于 0"},
                {"{\"price\":\"1.23456\"}", "价格最多 4 位小数"},
                {"{\"price\":\"1000000\"}", "价格过大"},
        };
        for (Object[] c : priceCases) {
            Resp r = putUnderlyingOverride("QRTV", (String) c[0]);
            assertErrorShape(r, 400, "VALIDATION_ERROR", up);
            assertThat(details(r)).as("%s", c[0]).containsExactly("price=" + c[1]);
        }
        assertThat(putUnderlyingOverride("QRTV", "{\"price\":\"1.2345\"}").status()).isEqualTo(200);
        assertThat(putUnderlyingOverride("QRTV", "{\"price\":\"999999.99\"}").status()).isEqualTo(200);
        assertErrorShape(putUnderlyingOverride("QRTV", "{\"price\":\"abc\"}"), 400, "BAD_REQUEST", up);

        Resp notHeld = putUnderlyingOverride("ZZQX", "{\"price\":\"10.00\"}");
        assertErrorShape(notHeld, 422, "QUOTE_TARGET_NOT_HELD", OVERRIDE_UNDERLYINGS + "ZZQX");
        assertThat(notHeld.body().get("message").asText()).isEqualTo("当前没有 ZZQX 的持仓，不能设置手动价格");
        assertThat(notHeld.body().get("details")).isEmpty();

        // option overrides
        Object[][] markCases = {
                {optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "0"), "mark=价格必须大于 0"},
                {optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "-0.5"), "mark=价格必须大于 0"},
                {optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "0.00001"), "mark=价格最多 4 位小数"},
                {optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "1000000.00"), "mark=价格过大"},
                {optionOverrideBody(" ", "2026-12-18", "50", "PUT", "1.40"), "symbol=请填写标的"},
                {optionOverrideBody("QRTV", "2026-12-18", "0", "PUT", "1.40"), "strike=行权价必须大于 0"},
        };
        for (Object[] c : markCases) {
            Resp r = putOptionOverride((String) c[0]);
            assertErrorShape(r, 400, "VALIDATION_ERROR", OVERRIDE_OPTIONS);
            assertThat(details(r)).as("%s", c[0]).contains((String) c[1]);
        }
        ObjectNode missingMark = (ObjectNode) readTree(optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "1"));
        missingMark.remove("mark");
        Resp r = putOptionOverride(missingMark.toString());
        assertErrorShape(r, 400, "VALIDATION_ERROR", OVERRIDE_OPTIONS);
        assertThat(details(r)).containsExactly("mark=请输入价格");

        for (String bad : List.of(optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "x1"),
                optionOverrideBody("QRTV", "2026/12/18", "50", "PUT", "1.40"),
                optionOverrideBody("QRTV", "2026-12-18", "50", "STRADDLE", "1.40"))) {
            assertErrorShape(putOptionOverride(bad), 400, "BAD_REQUEST", OVERRIDE_OPTIONS);
        }

        Resp notHeldOpt = putOptionOverride(optionOverrideBody("QRTV", "2026-12-18", "55", "PUT", "1.40"));
        assertErrorShape(notHeldOpt, 422, "QUOTE_TARGET_NOT_HELD", OVERRIDE_OPTIONS);
        assertThat(notHeldOpt.body().get("message").asText())
                .isEqualTo("当前没有 QRTV 2026-12-18 55 P 的持仓，不能设置手动价格");

        Resp missingQuery = deleteMarkOverride("?symbol=QRTV&expirationDate=2026-12-18&strike=50");
        assertErrorShape(missingQuery, 400, "BAD_REQUEST", OVERRIDE_OPTIONS);

        // after both QRTV 50P positions are closed, the contract is no longer held
        setNow("2026-11-23T10:10");
        close("BTC", "QRTV", "2026-12-18", "50", "PUT", "130.00", "2026-11-23T10:06");
        accountId = accountB;
        close("BTC", "QRTV", "2026-12-18", "50", "PUT", "130.00", "2026-11-23T10:07");
        accountId = "1";
        assertErrorShape(putOptionOverride(optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "1.40")), 422,
                "QUOTE_TARGET_NOT_HELD", OVERRIDE_OPTIONS);
        assertErrorShape(putUnderlyingOverride("QRTV", "{\"price\":\"52.00\"}"), 422, "QUOTE_TARGET_NOT_HELD", up);
    }

    JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ============================================================================================== expiry / schedule

    static final String EXP_C = "QRTV 2026-11-20 45 P";
    static final String EXP_OCC = "QRTV261120P00045000";
    static final String EXP_NEVER = "QRTV 2026-11-20 40 P";

    /** Account A: STO QRTV 2026-11-20 45P and 40P (opened 11-16); clock left at 2026-11-16 09:50. */
    void expiringPositions() {
        setNow("2026-11-16T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        open("STO", "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-16T09:40");
        open("STO", "QRTV", "2026-11-20", "40", "PUT", "30.00", "2026-11-16T09:41");
    }

    @Test
    @DisplayName("Q150 到期日当天仍跟踪并拉取（11-20 有报价）；次日起过期未处理的合约不再跟踪：只剩过期持仓的标的不发 CBOE / Finnhub 请求，行显示最后报价（MISSING_FROM_SOURCE）或无报价（NEVER_QUOTED），无失败标签、无顶部提示")
    void expiredContractsNotTracked() {
        expiringPositions();
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-20 09:45:00",
                        List.of(option(EXP_OCC, "bid", "0.01", "ask", "0.03")))))
                .finnhub("QRTV", finnhubOk("52.40", "2026-11-20T09:59:00")).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(requests()).containsExactly("CBOE QRTV");
        assertThat(shown(optionQuote("1", EXP_C))).isEqualTo("0.01/0.03 0.02 2026-11-20T09:45:00 QUOTED");
        assertThat(optionQuote("1", EXP_NEVER).get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        tickAt("2026-11-20T16:20", "CLOSE");

        // next trading day: the chain would still list the contract with new prices, but nothing is requested
        sources().cboe("QRTV", ok(cboeBody("QRTV", "53.00", "2026-11-23 09:45:00",
                        List.of(option(EXP_OCC, "bid", "0.50", "ask", "0.70")))))
                .finnhub("QRTV", finnhubOk("53.00", "2026-11-23T09:59:00")).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(requests()).as("only expired positions left → QRTV not requested").isEmpty();
        assertThat(t.get("results")).isEmpty();
        assertThat(t.get("status").get("trackedSymbolCount").asInt()).isZero();
        setNow("2026-11-23T10:00");
        assertThat(refresh().get("results")).as("refresh does not request it either").isEmpty();
        assertThat(requests()).isEmpty();

        JsonNode q = optionQuote("1", EXP_C);
        assertThat(shown(q)).isEqualTo("0.01/0.03 0.02 2026-11-20T09:45:00 MISSING_FROM_SOURCE");
        assertThat(q.get("contractStatusLabel").asText()).isEqualTo("已到期（最后报价：11-20 09:45）");
        assertThat(q.get("fetchFailed").asBoolean()).isFalse();
        assertThat(q.get("staleLabel").isNull()).isTrue();
        JsonNode never = optionQuote("1", EXP_NEVER);
        assertThat(shown(never)).isEqualTo("null/null null null NEVER_QUOTED");
        assertThat(never.get("fetchFailed").asBoolean()).isFalse();
        assertThat(never.get("staleLabel").isNull()).isTrue();
        JsonNode lastU = underlyingQuote("1", EXP_C);
        assertThat(lastU.get("price").asText()).as("Q151: last stored price kept").isEqualTo("52.40");
        assertThat(lastU.get("fetchFailed").asBoolean()).isFalse();
        assertThat(lastU.get("staleLabel").isNull()).isTrue();

        setNow("2026-11-23T11:00");
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).as("no tracked symbols → no warning").isFalse();

        // still overridable
        overrideMark("QRTV", "2026-11-20", "45", "PUT", "0.05");
        overrideUnderlying("QRTV", "52.80");
        tickAt("2026-11-23T11:15", "SCHEDULED");
        assertThat(optionQuote("1", EXP_C).get("mark").asText()).isEqualTo("0.05");
        assertThat(underlyingQuote("1", EXP_C).get("price").asText()).isEqualTo("52.80");
        assertThat(requests()).isEmpty();
    }

    @Test
    @DisplayName("Q150 同一标的还有未到期持仓：标的照常请求，但过期合约即使仍在链中也不更新、不变 QUOTED")
    void expiredContractWithActiveSibling() {
        expiringPositions();
        open("STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-16T09:42");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-20 09:45:00",
                List.of(option(EXP_OCC, "bid", "0.01", "ask", "0.03"), p1Option())))).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");

        sources().cboe("QRTV", ok(cboeBody("QRTV", "53.00", "2026-11-23 09:45:00",
                List.of(option(EXP_OCC, "bid", "0.50", "ask", "0.70"),
                        option("QRTV261120P00040000", "bid", "0.10", "ask", "0.20"), p1Option("1.30", "1.40"))))).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(requests()).containsExactly("CBOE QRTV");
        assertThat(results(t)).containsExactly("QRTV SUCCESS SUCCESS null CBOE");
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.35");
        assertThat(underlyingQuote("1", EXP_C).get("price").asText()).isEqualTo("53.00");

        JsonNode q = optionQuote("1", EXP_C);
        assertThat(shown(q)).isEqualTo("0.01/0.03 0.02 2026-11-20T09:45:00 MISSING_FROM_SOURCE");
        assertThat(q.get("contractStatusLabel").asText()).isEqualTo("已到期（最后报价：11-20 09:45）");
        assertThat(q.get("fetchFailed").asBoolean()).isFalse();
        assertThat(q.get("staleLabel").isNull()).isTrue();
        assertThat(shown(optionQuote("1", EXP_NEVER))).isEqualTo("null/null null null NEVER_QUOTED");
        assertNum(storedContract("QRTV", "2026-11-20", "45", "PUT"), "bid", "0.01");
    }

    @Test
    @DisplayName("Q151 到期前已从链中消失（数据源已无该合约）→ 过期后标签改为 已到期（最后报价：…），仍为 MISSING_FROM_SOURCE")
    void missingThenExpiredLabel() {
        expiringPositions();
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-19 09:45:00",
                List.of(option(EXP_OCC, "bid", "0.01", "ask", "0.03"))))).apply();
        tickAt("2026-11-19T10:00", "SCHEDULED");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-20 09:45:00", List.of()))).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        JsonNode q = optionQuote("1", EXP_C);
        assertThat(q.get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(q.get("contractStatusLabel").asText()).isEqualTo("数据源已无该合约（最后报价：11-19 09:45）");

        setNow("2026-11-23T10:00");
        q = optionQuote("1", EXP_C);
        assertThat(q.get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(q.get("contractStatusLabel").asText()).isEqualTo("已到期（最后报价：11-19 09:45）");
        assertThat(q.get("mark").asText()).isEqualTo("0.02");
        assertThat(q.get("fetchFailed").asBoolean()).isFalse();
        assertThat(q.get("staleLabel").isNull()).isTrue();
    }

    static void assertNum(JsonNode node, String field, String expected) {
        assertThat(new java.math.BigDecimal(node.get(field).asText())).as(field).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("定时时刻：09:30 / 16:00 SCHEDULED，09:29 / 16:05 / 10:07 不是时刻，10:00:30 截到分钟算 10:00；周六不拉；11-27 提前收盘日 16:20 仍 CLOSE")
    void scheduleSlots() {
        scenario();
        scenarioSources();
        tickSkipped("2026-11-23T09:29", "NOT_A_SLOT");
        tickAt("2026-11-23T09:30", "SCHEDULED");
        tickAt("2026-11-23T10:00:30", "SCHEDULED");
        tickSkipped("2026-11-23T10:07", "NOT_A_SLOT");
        tickAt("2026-11-23T15:45", "SCHEDULED");
        tickAt("2026-11-23T16:00", "SCHEDULED");
        tickSkipped("2026-11-23T16:05", "NOT_A_SLOT");
        tickSkipped("2026-11-23T16:15", "NOT_A_SLOT");
        tickAt("2026-11-27T16:20", "CLOSE");
        tickSkipped("2026-11-28T10:00", "NON_TRADING_DAY");
        tickSkipped("2026-11-29T16:20", "NON_TRADING_DAY");
    }

    @Test
    @DisplayName("16:20 收盘快照：只含本次成功更新的标的与合约；同一天再次 CLOSE 覆盖；立即刷新不产生快照")
    void closeSnapshotRules() {
        scenario();
        scenarioSources();
        setNow("2026-11-23T16:10");
        assertThat(refresh().get("performed").asBoolean()).isTrue();
        assertThat(stored().get("closeSnapshots")).isEmpty();

        sources().cboe("QRTV", qrtvOk()).apply(); // IXQZ fails at the close (Finnhub unreachable as well)
        tickAt("2026-11-23T16:20", "CLOSE");
        JsonNode snap = stored().get("closeSnapshots").get(0);
        assertThat(snap.get("underlyings")).extracting(u -> u.get("symbol").asText()).containsExactly("QRTV");
        assertThat(snap.get("contracts")).extracting(u -> u.get("symbol").asText()).containsExactly("QRTV");

        scenarioSources();
        tickAt("2026-11-23T16:20", "CLOSE");
        JsonNode snaps = stored().get("closeSnapshots");
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).get("underlyings")).extracting(u -> u.get("symbol").asText())
                .containsExactlyInAnyOrder("QRTV", "IXQZ");
    }

    // ============================================================================================== top warning window

    @Test
    @DisplayName("顶部提示时段：16:00（含）提示、16:01 不提示；次日 09:30–10:00 按 09:30 起算不提示，10:01 提示 上次：2026-11-23 10:00 美东")
    void staleWindow() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        sources().apply();
        setNow("2026-11-23T16:00");
        assertThat(status().get("stale").asBoolean()).isTrue();
        setNow("2026-11-23T16:01");
        assertThat(status().get("stale").asBoolean()).isFalse();
        setNow("2026-11-24T09:29");
        assertThat(status().get("stale").asBoolean()).isFalse();
        setNow("2026-11-24T09:31");
        assertThat(status().get("stale").asBoolean()).isFalse();
        setNow("2026-11-24T10:00");
        assertThat(status().get("stale").asBoolean()).isFalse();
        setNow("2026-11-24T10:01");
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isTrue();
        assertThat(s.get("warning").asText()).isEqualTo("行情未更新（上次：2026-11-23 10:00 美东）");
    }

    // ============================================================================================== refresh

    @Test
    @DisplayName("立即刷新：非交易日（周六）也可；定时拉取不受冷却影响也不重置冷却；没有跟踪标的时 performed、results 为空；reset 清空冷却")
    void refreshRules() {
        setNow("2026-11-28T12:00");
        JsonNode r = refresh();
        assertThat(r.get("performed").asBoolean()).isTrue();
        assertThat(r.get("results")).isEmpty();
        assertThat(r.get("status").get("stale").asBoolean()).isFalse();
        assertThat(r.get("status").get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-28T12:00:00");

        reset();
        scenario();
        scenarioSources();
        setNow("2026-11-28T12:00");
        r = refresh();
        assertThat(r.get("performed").asBoolean()).as("reset cleared the cooldown").isTrue();
        assertThat(results(r)).hasSize(2);

        setNow("2026-11-30T10:14:30");
        assertThat(refresh().get("performed").asBoolean()).isTrue();
        JsonNode t = tickAt("2026-11-30T10:15", "SCHEDULED");
        assertThat(t.get("status").get("lastRefreshAtLocal").asText()).isEqualTo("2026-11-30T10:14:30");
        assertThat(t.get("status").get("refreshAllowed").asBoolean()).isFalse();
        setNow("2026-11-30T10:15:29");
        assertThat(refresh().get("reason").asText()).isEqualTo("COOLDOWN");
        setNow("2026-11-30T10:15:30");
        assertThat(refresh().get("performed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("/api/market-data/** 不要求 X-Account-Id；带了不存在的账户也忽略")
    void noAccountHeaderNeeded() {
        accountId = null;
        assertThat(get(STATUS).status()).isEqualTo(200);
        accountId = "999";
        assertThat(get(STATUS).status()).isEqualTo(200);
        assertThat(post(REFRESH, null).status()).isEqualTo(200);
        accountId = "1";
    }

    @Test
    @DisplayName("reset 清空行情、请求记录与假数据源配置")
    void resetClearsMarketData() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(stored().get("contracts")).isNotEmpty();
        reset();
        assertThat(requests()).isEmpty();
        JsonNode s = stored();
        assertThat(s.get("underlyings")).isEmpty();
        assertThat(s.get("contracts")).isEmpty();
        assertThat(s.get("closeSnapshots")).isEmpty();
        JsonNode st = status();
        assertThat(st.get("lastSuccessAt").isNull()).isTrue();
        assertThat(st.get("lastAttemptAt").isNull()).isTrue();
        assertThat(st.get("lastRefreshAt").isNull()).isTrue();

        scenario();
        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(results(t)).as("sources back to all unreachable").containsExactly(
                "IXQZ FAILED NETWORK_ERROR NETWORK_ERROR null", "QRTV FAILED NETWORK_ERROR NETWORK_ERROR null");
    }
}
