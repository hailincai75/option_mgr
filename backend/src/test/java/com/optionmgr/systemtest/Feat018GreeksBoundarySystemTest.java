package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-018 单合约 Greeks: boundaries of the contract (status priority, rounding / zero / sign,
 * missing or malformed fields, fetch failures, MISSING_FROM_SOURCE, expiration day / weekend, index options,
 * ambiguous matches, manual marks replaced by a pull, FIFO close, multiplier field, FEAT-043 adjustment response).
 * See {@link Feat018GreeksSystemTestBase}.
 */
@DisplayName("FEAT-018 单合约 Greeks（边界）")
class Feat018GreeksBoundarySystemTest extends Feat018GreeksSystemTestBase {

    // ============================================================================================== status priority

    @Test
    @DisplayName("Q171 判定顺序：已过期且从未报价 → EXPIRED_PENDING（优先于 NO_QUOTE）；已过期 + 手动 mark → EXPIRED_PENDING，无标注")
    void expiredPendingBeatsNoQuoteAndManual() {
        setNow("2026-11-17T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        openN("STO", 1, "QRTV", "2026-11-20", "40", "PUT", "30.00", "2026-11-17T09:41");
        sources().cboe("QRTV", qrtv018("2026-11-20 09:45:00",
                greekOption(OCC_C_EXPIRED, "0.04", "0.06", "0.4100", "-0.0100", "0.0040", "-0.0080", "0.0010")))
                .apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        // on the expiration day itself the quoted one is OK
        assertThat(greeks("1", C_EXPIRED).get("status").asText()).isEqualTo("OK");
        assertThat(greeks("1", "QRTV 2026-11-20 40 P").get("status").asText()).isEqualTo("NO_QUOTE");

        setNow("2026-11-23T10:00");
        JsonNode never = optRow("1", "QRTV 2026-11-20 40 P");
        assertThat(never.get("optionQuote").get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(shownGreeks(greeksOf(never))).isEqualTo(EXPIRED_G);
        assertThat(greeksOf(never).get("quoteTime").isNull()).isTrue();

        overrideMark("QRTV", "2026-11-20", "45", "PUT", "0.10");
        JsonNode manual = optRow("1", C_EXPIRED);
        assertThat(manual.get("optionQuote").get("manual").asBoolean()).isTrue();
        assertThat(shownGreeks(greeksOf(manual))).as("expired + manual: no values, no note").isEqualTo(EXPIRED_G);
    }

    @Test
    @DisplayName("到期日当天：链中有 → OK；16:20 收盘后链中消失（MISSING_FROM_SOURCE）→ 仍 OK 用最后值；次日 → 已到期，待处理")
    void expirationDayThenMissingThenNextDay() {
        setNow("2026-11-20T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 2, "QRTV", "2026-11-23", "52", "PUT", "300.00", "2026-11-20T09:40");
        sources().cboe("QRTV", qrtv018("2026-11-23 09:45:00",
                greekOption(OCC_C_P7, "0.55", "0.65", "0.3800", "-0.4500", "0.2100", "-0.1500", "0.0100"))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        String expected = okG("38.00", "90.00", "-0.4500", "-42.00", "0.2100", "30.00", "-0.1500", "-2.00", "0.0100");
        assertThat(shownGreeks(greeks("1", C_P7))).isEqualTo(expected);

        sources().cboe("QRTV", qrtv018("2026-11-23 16:15:00")).apply();
        tickAt("2026-11-23T16:20", "CLOSE");
        setNow("2026-11-23T16:30");
        JsonNode row = optRow("1", C_P7);
        assertThat(row.get("optionQuote").get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        JsonNode g = greeksOf(row);
        assertThat(shownGreeks(g)).as("Q143 last saved Greeks").isEqualTo(expected);
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T09:45:00");

        setNow("2026-11-24T09:00");
        assertThat(shownGreeks(greeks("1", C_P7))).isEqualTo(EXPIRED_G);
    }

    @Test
    @DisplayName("周末：不拉取，用库里最后的 Greeks；周五到期的合约周六变为已到期，待处理")
    void weekendUsesLastGreeks() {
        setNow("2026-11-17T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-17T09:45");
        sources().cboe("QRTV", qrtv018("2026-11-20 15:45:00",
                greekOption(OCC_C_EXPIRED, "0.01", "0.03", "0.5000", "-0.0100", "0.0040", "-0.0080", "0.0010"),
                p1Greeks())).apply();
        tickAt("2026-11-20T16:00", "SCHEDULED");
        assertThat(greeks("1", C_EXPIRED).get("status").asText()).as("Friday = expiration day").isEqualTo("OK");

        tickSkipped("2026-11-21T11:00", "NON_TRADING_DAY");
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(P1_G);
        assertThat(shownGreeks(greeks("1", C_EXPIRED))).isEqualTo(EXPIRED_G);
        assertThat(optionPositions("1").get("asOfDate").asText()).isEqualTo("2026-11-21");
    }

    // ============================================================================================== no quote

    @Test
    @DisplayName("Q149 指数期权 SPX：不拉取 → 无报价；手动 mark 后仍 无报价（无标注），而未实现盈亏 OK")
    void indexOptionNoQuoteEvenWithManualMark() {
        scenario018();
        openN("BTO", 1, "SPX", "2026-12-18", "7000", "CALL", "500.00", "2026-11-20T11:00");
        sources018();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        String spx = "SPX 2026-12-18 7000 C";
        assertThat(shownGreeks(greeks("1", spx))).isEqualTo(NO_QUOTE_G);

        setNow("2026-11-23T10:05");
        overrideMark("SPX", "2026-12-18", "7000", "CALL", "5.10");
        JsonNode row = optRow("1", spx);
        assertThat(row.get("unrealizedStatus").asText()).isEqualTo("OK");
        assertThat(shownGreeks(greeksOf(row))).isEqualTo(NO_QUOTE_G);
    }

    @Test
    @DisplayName("匹配有歧义（QRTV… 与 B1QRTV… 同一合约）→ 从未报价 → 无报价")
    void ambiguousMatchIsNoQuote() {
        scenario018();
        openN("BTO", 1, "QRTV", "2026-12-18", "60", "CALL", "20.00", "2026-11-20T11:00");
        sources018(greekOption(OCC_C_P6, "0.10", "0.15", "0.4000", "0.1000", "0.0100", "-0.0100", "0.0100"),
                greekOption("B1" + OCC_C_P6, "0.11", "0.16", "0.4100", "0.1100", "0.0110", "-0.0110", "0.0110"));
        tickAt("2026-11-23T10:00", "SCHEDULED");
        JsonNode row = optRow("1", C_P6);
        assertThat(row.get("optionQuote").get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(shownGreeks(greeksOf(row))).isEqualTo(NO_QUOTE_G);
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(P1_G);
    }

    @Test
    @DisplayName("从未成功拉取（数据源一直失败）→ 无报价；第一次成功后才有值")
    void neverSucceededThenSuccess() {
        scenario018();
        sources().cboe("QRTV", httpError(503)).cboe("IXQZ", unreachable()).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(NO_QUOTE_G);
        assertThat(shownGreeks(greeks("1", C_P3))).isEqualTo(NO_QUOTE_G);

        sources018();
        tickAt("2026-11-23T11:00", "SCHEDULED");
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(P1_G);
        assertThat(shownGreeks(greeks("1", C_P3))).isEqualTo(P3_G);
    }

    @Test
    @DisplayName("与 FEAT-017 的区别：bid 0 且无 theo（mark null）→ 未实现 NO_QUOTE，Greeks 仍 OK；mark 来自 theo → Greeks 照常")
    void bidZeroNoTheoStillShowsGreeks() {
        scenarioPulled018();
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00",
                        greekOption(OCC_C_P1, "0", "0.05", "0.3512", "-0.3021", "0.0512", "-0.0456", "0.0711"),
                        option(OCC_C_P2, "bid", "0", "ask", "1.90", "theo", "1.86", "iv", "0.3320", "delta", "0.2850",
                                "gamma", "0.0480", "theta", "-0.0390", "vega", "0.0650")))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");

        JsonNode p1 = optRow("1", C_P1);
        assertThat(p1.get("optionQuote").get("mark").isNull()).isTrue();
        assertThat(p1.get("unrealizedStatus").asText()).isEqualTo("NO_QUOTE");
        assertThat(shownGreeks(greeksOf(p1))).isEqualTo(P1_G);

        JsonNode p2 = optRow("1", C_P2);
        assertThat(p2.get("optionQuote").get("markBasis").asText()).isEqualTo("THEO");
        assertThat(shownGreeks(greeksOf(p2))).isEqualTo(P2_G);
    }

    @Test
    @DisplayName("未过期合约从链中消失（MISSING_FROM_SOURCE）→ 保留最后的 Greeks（Q143）")
    void missingFromSourceKeepsLastGreeks() {
        scenarioPulled018();
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00", p2Greeks()))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");
        JsonNode p1 = optRow("1", C_P1);
        assertThat(p1.get("optionQuote").get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        JsonNode g = greeksOf(p1);
        assertThat(shownGreeks(g)).isEqualTo(P1_G);
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T09:45:00");
    }

    @Test
    @DisplayName("立即刷新（POST /api/market-data/refresh）拉到的新值随持仓重新查询更新")
    void manualRefreshUpdatesGreeks() {
        scenarioPulled018();
        sources().cboe("QRTV", qrtv018("2026-11-23 10:05:00",
                        greekOption(OCC_C_P1, "1.20", "1.30", "0.3600", "-0.3300", "0.0530", "-0.0470", "0.0720"),
                        p2Greeks()))
                .cboe("IXQZ", ixqz018("2026-11-23 10:05:00")).apply();
        setNow("2026-11-23T10:07");
        JsonNode r = refresh();
        assertThat(r.get("performed").asBoolean()).as("refresh performed: %s", r).isTrue();
        JsonNode g = greeks("1", C_P1);
        assertThat(shownGreeks(g))
                .isEqualTo(okG("36.00", "33.00", "-0.3300", "-5.30", "0.0530", "4.70", "-0.0470", "-7.20", "0.0720"));
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T10:05:00");
    }

    // ============================================================================================== fields

    @Test
    @DisplayName("Q172 单字段缺失：iv 缺失 → ivPercent null，其它照常；gamma 显式 JSON null → null")
    void ivMissingAndExplicitNull() {
        scenarioPulled018();
        ObjectNode p1 = greekOption(OCC_C_P1, "1.20", "1.30", null, "-0.3021", "0.0512", "-0.0456", "0.0711");
        ObjectNode p2 = p2Greeks();
        p2.putNull("gamma");
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00", p1, p2))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");

        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(okG("null", "30.21", "-0.3021", "-5.12", "0.0512",
                "4.56", "-0.0456", "-7.11", "0.0711"));
        assertThat(shownGreeks(greeks("1", C_P2))).isEqualTo(okG("33.20", "57.00", "0.2850", "null", "null",
                "-7.80", "-0.0390", "13.00", "0.0650"));
        assertGreeksMatchStored("1");
    }

    @Test
    @DisplayName("Q172 单字段格式错误（vega 为文字 \"N/A\"）→ 该字段 null，其它字段与报价照常（FEAT-016：格式错误按缺失字段处理）")
    void malformedFieldIsMissing() {
        scenarioPulled018();
        ObjectNode p2 = p2Greeks();
        p2.put("vega", "N/A");
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00", p1Greeks(), p2))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");

        JsonNode row = optRow("1", C_P2);
        assertThat(row.get("optionQuote").get("contractStatus").asText()).isEqualTo("QUOTED");
        assertThat(text(row.get("optionQuote").get("quoteTimeLocal"))).isEqualTo("2026-11-23T10:00:00");
        assertThat(shownGreeks(greeksOf(row))).isEqualTo(okG("33.20", "57.00", "0.2850", "9.60", "0.0480",
                "-7.80", "-0.0390", "null", "null"));
    }

    @Test
    @DisplayName("Q166 取整与零：Delta 1 → 1.0000 / 200.00；Gamma 0 → 0.0000 / 0.00；HALF_UP 远离 0（-0.00005 → -0.0001）；-0.004 → 0.00（无负号）；IV 0.123456 → 12.35")
    void roundingZeroAndSign() {
        scenario018();
        // D1 BTO 2 QRTV 2026-12-18 30C; D2 STO 1 QRTV 2026-12-18 40P
        openN("BTO", 2, "QRTV", "2026-12-18", "30", "CALL", "4400.00", "2026-11-20T11:00");
        openN("STO", 1, "QRTV", "2026-12-18", "40", "PUT", "10.00", "2026-11-20T11:01");
        sources018(
                greekOption("QRTV261218C00030000", "22.30", "22.50", null, "1", "0", "-0.00005", "0.00004"),
                greekOption("QRTV261218P00040000", "0.05", "0.10", "0.123456", "-0.00005", "0.00004", "-0.012345",
                        "0.012355"));
        tickAt("2026-11-23T10:00", "SCHEDULED");

        // D1 LONG ×200: theta -0.00005×200 = -0.01; vega 0.00004×200 = 0.008 → 0.01
        assertThat(shownGreeks(greeks("1", "QRTV 2026-12-18 30 C"))).isEqualTo(okG("null", "200.00", "1.0000",
                "0.00", "0.0000", "-0.01", "-0.0001", "0.01", "0.0000"));
        // D2 SHORT ×−100: delta 0.005 → 0.01; gamma -0.004 → 0.00; theta 1.2345 → 1.23; vega -1.2355 → -1.24
        assertThat(shownGreeks(greeks("1", "QRTV 2026-12-18 40 P"))).isEqualTo(okG("12.35", "0.01", "-0.0001",
                "0.00", "0.0000", "1.23", "-0.0123", "-1.24", "0.0124"));
        assertGreeksMatchStored("1");
    }

    @Test
    @DisplayName("持仓值用 multiplier 字段值（不写死 100）：BTO 3 张 multiplier 10，delta 0.12345 → 每张 0.1235、持仓 3.70")
    void usesMultiplierField() {
        scenario018();
        ObjectNode body = trade("BTO", 3, "QRTV", "2026-12-18", "70", "CALL", "6.00", "2026-11-20T11:00");
        body.put("multiplier", 10);
        Resp r = post("/api/option-trades", body.toString());
        assertThat(r.status()).as("open multiplier 10 -> %s", r.body()).isEqualTo(201);
        sources018(greekOption("QRTV261218C00070000", "0.15", "0.25", "0.5000", "0.12345", "0.0200", "-0.0300",
                "0.0400"));
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode row = optRow("1", "QRTV 2026-12-18 70 C");
        assertThat(row.get("multiplier").asInt()).isEqualTo(10);
        assertThat(shownGreeks(greeksOf(row))).isEqualTo(okG("50.00", "3.70", "0.1235", "0.60", "0.0200", "-0.90",
                "-0.0300", "1.20", "0.0400"));
    }

    // ============================================================================================== positions change

    @Test
    @DisplayName("同一合约多次开仓 + BTC 整笔平掉最早一笔（FIFO）→ 按剩余张数重算；全部平仓后行消失")
    void fifoCloseRecomputes() {
        setNow("2026-11-23T09:50");
        deposit("30000.00", "2026-11-16T10:00");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-19T10:00");
        openN("STO", 2, "QRTV", "2026-12-18", "50", "PUT", "250.00", "2026-11-19T10:05");
        sources018();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(okG("35.12", "90.63", "-0.3021", "-15.36", "0.0512",
                "13.68", "-0.0456", "-21.33", "0.0711"));

        setNow("2026-11-23T10:05");
        closeN("BTC", 1, "QRTV", "2026-12-18", "50", "PUT", "125.00", "2026-11-23T10:01");
        assertThat(optRow("1", C_P1).get("contracts").asInt()).isEqualTo(2);
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(okG("35.12", "60.42", "-0.3021", "-10.24", "0.0512",
                "9.12", "-0.0456", "-14.22", "0.0711"));

        closeN("BTC", 2, "QRTV", "2026-12-18", "50", "PUT", "250.00", "2026-11-23T10:02");
        assertThat(hasOptRow("1", C_P1)).isFalse();
    }

    @Test
    @DisplayName("Q140 手动 mark 被下一次成功拉取替换 → 标注随之消失，Greeks 为新报价的值")
    void manualReplacedByNextPullRemovesNote() {
        scenarioPulled018();
        setNow("2026-11-23T10:05");
        overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");
        assertThat(text(greeks("1", C_P1).get("manualMarkNote"))).isEqualTo(manualNote("11-23 09:45"));

        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00", p1Greeks(), p2Greeks()))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");
        // Q148: P1 is in the successful chain with a non-null automatic mark (1.20 / 1.30 → 1.25) → manual replaced
        JsonNode row = optRow("1", C_P1);
        assertThat(row.get("optionQuote").get("manual").asBoolean()).as("manual mark replaced by the pull").isFalse();
        assertThat(row.get("optionQuote").get("mark").asText()).isEqualTo("1.25");
        JsonNode g = greeksOf(row);
        assertThat(g.get("manualMarkNote").isNull()).as("note gone").isTrue();
        assertThat(shownGreeks(g)).isEqualTo(P1_G);
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T10:00:00");
    }

    @Test
    @DisplayName("Q157 只看当前账户：账户 A 看不到账户 B 的 P5；同一合约两个账户数值各自按张数算")
    void perAccount() {
        scenarioPulled018();
        List<String> a = new java.util.ArrayList<>();
        optionPositions("1").get("positions").forEach(p -> a.add(p.get("contractLabel").asText() + " "
                + p.get("contracts").asInt()));
        assertThat(a).containsExactlyInAnyOrder(C_P1 + " 1", C_P2 + " 2", C_P3 + " 1", C_P4 + " 1");
        assertThat(text(greeks("1", C_P1).get("deltaPosition"))).isEqualTo("30.21");
        assertThat(text(greeks(accountB, C_P5).get("deltaPosition"))).isEqualTo("90.63");
    }

    // ============================================================================================== FEAT-043

    static final String ADJUST = "/api/option-trades/expiration-adjustments";
    static final String CAL_FETCH = "/api/test-support/trading-calendar/fetch";

    void fetchCalendar(String nyLocal, List<Feat043AdjustExpirationSystemTest.H> rows) {
        setNow(nyLocal);
        ObjectNode body = json.createObjectNode();
        ArrayNode data = body.putArray("data");
        for (Feat043AdjustExpirationSystemTest.H h : rows) {
            ObjectNode o = data.addObject();
            o.put("eventName", h.name());
            o.put("atDate", h.date());
            o.put("tradingHour", h.hours());
            o.put("postMarket", "");
        }
        body.put("exchange", "US");
        body.put("timezone", "America/New_York");
        ObjectNode req = json.createObjectNode();
        req.put("httpStatus", 200);
        req.set("body", body);
        Resp r = noAccount(() -> exchange(HttpMethod.POST, CAL_FETCH, req.toString()));
        assertThat(r.status()).as("POST %s -> %s", CAL_FETCH, r.body()).isEqualTo(200);
        assertThat(r.body().get("lastAttemptResult").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("FEAT-043 调整到期日响应的 position 也带 greeks（结构同持仓行，与随后 GET 一致）；新合约被跟踪拉到后显示其 Greeks")
    void adjustExpirationResponseCarriesGreeks() {
        fetchCalendar("2026-11-30T08:00", Feat043AdjustExpirationSystemTest.scenarioData());
        setNow("2026-11-30T09:50");
        deposit("10000.00", "2026-11-16T10:00");
        openN("STO", 1, "QRTV", "2026-12-04", "50", "PUT", "120.00", "2026-11-30T09:40");
        sources().cboe("QRTV", qrtv018("2026-11-30 09:45:00",
                greekOption("QRTV261204P00050000", "0.50", "0.60", "0.3000", "-0.2500", "0.0800", "-0.0600", "0.0300"),
                greekOption("QRTV261203P00050000", "0.40", "0.50", "0.3100", "-0.2200", "0.0900", "-0.0700",
                        "0.0250"))).apply();
        tickAt("2026-11-30T10:00", "SCHEDULED");
        assertThat(shownGreeks(greeks("1", "QRTV 2026-12-04 50 P"))).isEqualTo(okG("30.00", "25.00", "-0.2500",
                "-8.00", "0.0800", "6.00", "-0.0600", "-3.00", "0.0300"));

        fetchCalendar("2026-12-01T08:00", Feat043AdjustExpirationSystemTest.withDec4());
        setNow("2026-12-01T10:00");
        ObjectNode req = json.createObjectNode();
        req.put("symbol", "QRTV");
        req.put("expirationDate", "2026-12-04");
        req.put("strike", "50");
        req.put("optionType", "PUT");
        req.put("newExpirationDate", "2026-12-03");
        req.putNull("note");
        Resp r = post(ADJUST, req.toString());
        assertThat(r.status()).as("POST %s -> %s", ADJUST, r.body()).isEqualTo(200);
        JsonNode pos = r.body().get("position");
        assertThat(pos.get("contractLabel").asText()).isEqualTo("QRTV 2026-12-03 50 P");
        JsonNode g = greeksOf(pos);
        String expectedStatus = pos.get("optionQuote").get("contractStatus").asText().equals("NEVER_QUOTED")
                ? "NO_QUOTE" : "OK";
        assertThat(g.get("status").asText()).isEqualTo(expectedStatus);
        // same structure and values as the positions row
        assertThat(greeks("1", "QRTV 2026-12-03 50 P")).isEqualTo(g);

        sources().cboe("QRTV", qrtv018("2026-12-01 10:00:00",
                greekOption("QRTV261203P00050000", "0.40", "0.50", "0.3100", "-0.2200", "0.0900", "-0.0700",
                        "0.0250"))).apply();
        tickAt("2026-12-01T11:00", "SCHEDULED");
        assertThat(shownGreeks(greeks("1", "QRTV 2026-12-03 50 P"))).isEqualTo(okG("31.00", "22.00", "-0.2200",
                "-9.00", "0.0900", "7.00", "-0.0700", "-2.50", "0.0250"));
    }
}
