package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-052 Yahoo 行情, acceptance criteria 1–10 (AC 10's real-startup part is in
 * {@link Feat052YahooRealHttpSystemTest}). Scenario and fakes: {@link Feat052YahooQuotesSystemTestBase}.
 */
@DisplayName("FEAT-052 Yahoo 行情 + 后端 Greeks（验收标准，假 Yahoo / Finnhub / CBOE + 假时钟）")
class Feat052YahooQuotesAcceptanceSystemTest extends Feat052YahooQuotesSystemTestBase {

    static final String STALE_1000 = "行情未更新（上次：11-20 10:00）";

    // ============================================================================================== AC 1

    @Test
    @DisplayName("AC1 10:00 拉取成功：P1 标的价 50.00、3.90 / 4.10、Mark 4.00、11-20 10:00；P3 Mark 4.10；P2 Mark 0.65；IXQZ 现价 61.10；未实现盈亏按这些 mark 计算")
    void ac1FirstPull() {
        scenario052();
        scenarioYahoo().apply();
        JsonNode t = tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null YAHOO",
                "QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        for (JsonNode r : t.get("results")) {
            assertThat(r.get("failureDetail").isNull()).as("no failure detail: %s", r).isTrue();
        }

        JsonNode p1 = optionQuote("1", Y_P1);
        assertThat(shownY(p1)).isEqualTo("3.90/4.10 4.00 MID YAHOO 2026-11-20T10:00:00 QUOTED");
        assertThat(p1.get("automaticMark").asText()).isEqualTo("4.00");
        assertThat(p1.get("quoteTime").asText()).isEqualTo("2026-11-20T15:00:00Z");
        assertThat(p1.get("manual").asBoolean()).isFalse();
        assertThat(p1.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p1.get("staleLabel").isNull()).isTrue();
        assertThat(p1.get("contractStatusLabel").isNull()).isTrue();
        assertThat(p1.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-20T10:00:00");

        JsonNode u = underlyingQuote("1", Y_P1);
        assertThat(shownU(u)).isEqualTo("50.00 YAHOO 2026-11-20T10:00:00 false null");
        assertThat(u.get("automaticPrice").asText()).isEqualTo("50.00");
        assertThat(u.get("quoteTime").asText()).isEqualTo("2026-11-20T15:00:00Z");
        assertThat(u.get("manual").asBoolean()).isFalse();

        assertThat(shownY(optionQuote("1", Y_P2))).isEqualTo("0.60/0.70 0.65 MID YAHOO 2026-11-20T10:00:00 QUOTED");
        assertThat(shownY(optionQuote(accountB, Y_P3)))
                .isEqualTo("4.00/4.20 4.10 MID YAHOO 2026-11-20T10:00:00 QUOTED");
        assertThat(underlyingQuote(accountB, Y_P3)).isEqualTo(u);
        JsonNode stock = stockQuote(accountB, "IXQZ");
        assertThat(shownU(stock)).isEqualTo("61.10 YAHOO 2026-11-20T10:00:00 false null");

        // FEAT-017 unrealized P&L with these marks (independent arithmetic)
        assertThat(val(optRow("1", Y_P1))).as("STO 50P net 380.00, mark 4.00").isEqualTo(okVal("-400.00", "-20.00",
                "-5.26"));
        assertThat(val(optRow("1", Y_P2))).as("STO 45P net 70.00, mark 0.65").isEqualTo(okVal("-65.00", "5.00",
                "7.14"));
        assertThat(val(optRow(accountB, Y_P3))).as("BTO 2 50C net 800.00, mark 4.10").isEqualTo(okVal("820.00",
                "20.00", "2.50"));
        assertThat(val(stkRow(accountB, "IXQZ"))).as("100 IXQZ cost 6000.00 at 61.10").isEqualTo(okVal("6110.00",
                "110.00", "1.83"));
        assertSummaryMatchesRows("1");
        assertSummaryMatchesRows(accountB);

        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("optionSource").asText()).isEqualTo("YAHOO");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("0.00");
        assertThat(s.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-20T10:00:00");
        assertThat(s.get("trackedSymbolCount").asInt()).isEqualTo(2);

        // stored raw rows: source YAHOO, theo / rho null, last / iv / volume / OI as received
        JsonNode raw = storedContract("QRTV", FEB, "50", "PUT");
        assertThat(raw.get("source").asText()).isEqualTo("YAHOO");
        num(raw, "bid", "3.90");
        num(raw, "ask", "4.10");
        num(raw, "last", "3.95");
        num(raw, "iv", "0.3");
        assertThat(raw.get("volume").asLong()).isEqualTo(12);
        assertThat(raw.get("openInterest").asLong()).isEqualTo(340);
        assertThat(raw.get("theo").isNull()).as("Yahoo has no theo").isTrue();
        assertThat(raw.get("rho").isNull()).as("Rho not computed").isTrue();
        assertThat(raw.get("quoteTime").asText()).as("Q269 regularMarketTime").isEqualTo("2026-11-20T15:00:00Z");
        JsonNode ru = storedUnderlying("QRTV");
        assertThat(ru.get("source").asText()).isEqualTo("YAHOO");
        assertThat(ru.get("lastYahooResult").asText()).isEqualTo("SUCCESS");
        num(ru, "price", "50.00");
    }

    // ============================================================================================== AC 2

    @Test
    @DisplayName("AC2 拉取：假 Yahoo 只收到 1 次握手 + 3 个数据请求（IXQZ 标的价、QRTV 2026-12-18、QRTV 2027-02-19），不请求 CBOE / Finnhub，只存持有合约；P2 BTC 后不再请求 2026-12-18（会话复用，不再握手）")
    void ac2RequestsPerSymbolAndDate() {
        scenario052();
        scenarioYahoo().cboe("QRTV", qrtvOk()).cboe("IXQZ", ixqzOk())
                .finnhub("QRTV", finnhubAt("50.20", "2026-11-20T09:59")).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE", "YAHOO OPTIONS IXQZ -",
                "YAHOO OPTIONS QRTV 2026-12-18", "YAHOO OPTIONS QRTV 2027-02-19");

        List<String> occ = new ArrayList<>();
        stored().get("contracts").forEach(c -> occ.add(c.get("occCode").asText()));
        assertThat(occ).as("only the held contracts are stored (not 55C / 55P / 40P / IXQZ calls)")
                .containsExactlyInAnyOrder(OCC_Y_P1, OCC_Y_P2, OCC_Y_P3);

        setNow("2026-11-20T10:30");
        closeN("BTC", 1, "QRTV", DEC, "45", "PUT", "50.00", "2026-11-20T10:25");
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).as("session reused; 2026-12-18 no longer requested")
                .containsExactly("YAHOO OPTIONS IXQZ -", "YAHOO OPTIONS QRTV 2027-02-19");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null YAHOO",
                "QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
    }

    // ============================================================================================== AC 3

    @Test
    @DisplayName("AC3 账户 A 显示 Greeks：P1 IV 30.00%、Delta +47.01（−0.4701）、Gamma −5.30（0.0530）、Theta +1.63（−0.0163）、Vega −9.95（0.0995）；来源 YAHOO")
    void ac3GreeksAccountA() {
        scenarioPulled052();
        JsonNode g = greeks("1", Y_P1);
        assertThat(shownGreeks(g)).isEqualTo(Y_P1_G);
        assertThat(g.get("source").asText()).isEqualTo("YAHOO");
        assertThat(g.get("quoteTimeLocal").asText()).isEqualTo("2026-11-20T10:00:00");
        // P2 (σ 0.35, T 28.25 / 365) — contract table
        assertThat(shownGreeks(greeks("1", Y_P2))).isEqualTo(Y_P2_G);
        assertGreeksMatchStored("1");

        // stored raw values = independent Black-Scholes recomputation (S 50, K 50, σ 0.30, r 0, T 0.25)
        JsonNode raw = storedContract("QRTV", FEB, "50", "PUT");
        approx(raw, "delta", "-0.47010736");
        approx(raw, "gamma", "0.05304291");
        approx(raw, "theta", "-0.01634884");
        approx(raw, "vega", "0.09945546");
        JsonNode raw2 = storedContract("QRTV", DEC, "45", "PUT");
        approx(raw2, "delta", "-0.12908334");
        approx(raw2, "gamma", "0.04323875");
        approx(raw2, "theta", "-0.01813954");
        approx(raw2, "vega", "0.02928241");
    }

    // ============================================================================================== AC 4

    @Test
    @DisplayName("AC4 账户 B：P3 Delta +105.98（0.5299）、Gamma +10.61、Theta −3.27、Vega +19.89；按标的汇总 QRTV 期权 Delta +105.98，美元敞口按 50.00 = 5299.00")
    void ac4GreeksAccountBAndSummary() {
        scenarioPulled052();
        JsonNode g = greeks(accountB, Y_P3);
        assertThat(shownGreeks(g)).isEqualTo(Y_P3_G);
        assertThat(g.get("source").asText()).isEqualTo("YAHOO");
        approx(storedContract("QRTV", FEB, "50", "CALL"), "delta", "0.52989264");
        assertGreeksMatchStored(accountB);

        JsonNode s = greeksSummary(accountB);
        assertThat(row(group(s, "QRTV"))).isEqualTo(okRow("105.98", "0.00", "105.98", "50.00", "5299.00", "10.61",
                "-3.27", "19.89", "null", "null"));
        assertThat(text(group(s, "IXQZ").get("stockDelta"))).isEqualTo("100.00");
        assertThat(text(group(s, "IXQZ").get("underlyingPrice"))).isEqualTo("61.10");
    }

    // ============================================================================================== AC 5

    @Test
    @DisplayName("AC5 r 改为 5.00% 后拉取成功：P1 每张 Delta −0.4371、持仓值 +43.71；改 r 前已存的 Greeks 不重算，下一次有效报价才用新 r")
    void ac5RiskFreeRate() {
        scenarioPulled052();
        JsonNode s = settings("YAHOO", "5.00");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("5.00");
        assertThat(status().get("riskFreeRatePercent").asText()).isEqualTo("5.00");
        assertThat(shownGreeks(greeks("1", Y_P1))).as("stored Greeks are not recomputed").isEqualTo(Y_P1_G);

        // next VALID write: same quote time 10:00 (T = 0.25) so only r changes
        scenarioYahoo().apply();
        tickAt("2026-11-20T11:00", "SCHEDULED");
        JsonNode g = greeks("1", Y_P1);
        assertThat(text(g.get("deltaPerContract"))).isEqualTo("-0.4371");
        assertThat(text(g.get("deltaPosition"))).isEqualTo("43.71");
        approx(storedContract("QRTV", FEB, "50", "PUT"), "delta", "-0.43709707");
        approx(storedContract("QRTV", FEB, "50", "PUT"), "gamma", "0.05252971");
        approx(storedContract("QRTV", FEB, "50", "PUT"), "theta", "-0.01283104");
    }

    @Test
    @DisplayName("AC5 r = 5.00% 设定后首次拉取即按 5% 计算（相当于改配置重启）")
    void ac5RateBeforeFirstPull() {
        settings("YAHOO", "5.00");
        scenario052();
        scenarioYahoo().apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        JsonNode g = greeks("1", Y_P1);
        assertThat(text(g.get("deltaPerContract"))).isEqualTo("-0.4371");
        assertThat(text(g.get("deltaPosition"))).isEqualTo("43.71");
    }

    // ============================================================================================== AC 6

    /** 16:20 2027-02-19 chain: 50P / 50C bid 0 / ask 0 / last 3.95 / 4.10 / IV 0.00001, price 50.30 at 16:00; + P4. */
    ObjectNode febAfterHours(ObjectNode p4) {
        List<ObjectNode> cs = new ArrayList<>(List.of(yc(OCC_Y_P1, "0", "0", "3.95", "0.00001"),
                yc(OCC_Y_P3, "0", "0", "4.10", "0.00001")));
        if (p4 != null) {
            cs.add(p4);
        }
        return ok(qrtvFeb("50.30", "2026-11-20T16:00", cs.toArray(ObjectNode[]::new)));
    }

    ObjectNode decAfterHours() {
        return ok(qrtvDec("50.30", "2026-11-20T16:00", yc(OCC_Y_P2, "0", "0", "0.62", "0")));
    }

    @Test
    @DisplayName("AC6 16:20 收盘后 bid / ask 为 0、IV 0.00001、标的价 50.30：P1 仍 Mark 4.00、IV 30.00%、Delta +47.01、报价时间 11-20 10:00，无失败标签；标的价 50.30；收盘快照 P1 mark 4.00")
    void ac6AfterHoursZerosKeepLastValidQuote() {
        scenarioPulled052();
        ysources().handshake(crumbOk()).date("QRTV", FEB, febAfterHours(null)).date("QRTV", DEC, decAfterHours())
                .dflt("IXQZ", ok(ixqzBody("61.40", "2026-11-20T16:00"))).apply();
        JsonNode t = tickAt("2026-11-20T16:20", "CLOSE");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null YAHOO",
                "QRTV SUCCESS YAHOO SUCCESS null null YAHOO");

        for (String[] row : new String[][]{{"1", Y_P1, "3.90/4.10 4.00"}, {"1", Y_P2, "0.60/0.70 0.65"},
                {accountB, Y_P3, "4.00/4.20 4.10"}}) {
            JsonNode q = optionQuote(row[0], row[1]);
            assertThat(shownY(q)).as("%s kept (Q265)", row[1])
                    .isEqualTo(row[2] + " MID YAHOO 2026-11-20T10:00:00 QUOTED");
            assertThat(q.get("fetchFailed").asBoolean()).as("no failure label on %s", row[1]).isFalse();
            assertThat(q.get("staleLabel").isNull()).isTrue();
            assertThat(q.get("contractStatusLabel").isNull()).isTrue();
        }
        JsonNode g = greeks("1", Y_P1);
        assertThat(shownGreeks(g)).isEqualTo(Y_P1_G);
        assertThat(g.get("quoteTimeLocal").asText()).isEqualTo("2026-11-20T10:00:00");
        assertThat(shownGreeks(greeks(accountB, Y_P3))).isEqualTo(Y_P3_G);
        num(storedContract("QRTV", FEB, "50", "PUT"), "iv", "0.3");

        JsonNode u = underlyingQuote("1", Y_P1);
        assertThat(shownU(u)).as("underlying price is not classified, 50.30 updates")
                .isEqualTo("50.30 YAHOO 2026-11-20T16:00:00 false null");

        JsonNode snap = snapshot("2026-11-20");
        JsonNode sp1 = snapContract(snap, OCC_Y_P1, FEB, "50", "PUT");
        num(sp1, "bid", "3.90");
        num(sp1, "ask", "4.10");
        num(sp1, "iv", "0.3");
        assertThat(sp1.get("source").asText()).isEqualTo("YAHOO");
        assertThat(sp1.get("quoteTime").asText()).as("snapshot keeps the 10:00 quote → mark 4.00")
                .isEqualTo("2026-11-20T15:00:00Z");
        List<String> us = new ArrayList<>();
        snap.get("underlyings").forEach(x -> us.add(x.get("symbol").asText() + " "
                + d(x.get("price").asText()).stripTrailingZeros().toPlainString()));
        assertThat(us).containsExactlyInAnyOrder("QRTV 50.3", "IXQZ 61.4");
    }

    @Test
    @DisplayName("AC6（Q271）15:00 新开 P4 STO 45P、16:20 P4 bid 0 / ask 0 / last 1.10 / IV 0 → Mark 1.10（LAST），Greeks 各列 —（IV 0.00%）")
    void ac6NewContractLastPriceFallback() {
        scenarioPulled052();
        setNow("2026-11-20T15:00");
        openN("STO", 1, "QRTV", FEB, "45", "PUT", "105.00", "2026-11-20T15:00");
        ysources().handshake(crumbOk()).date("QRTV", FEB, febAfterHours(yc(OCC_Y_P4, "0", "0", "1.10", "0")))
                .date("QRTV", DEC, decAfterHours()).dflt("IXQZ", ok(ixqzBody("61.40", "2026-11-20T16:00"))).apply();
        tickAt("2026-11-20T16:20", "CLOSE");

        JsonNode p4 = optionQuote("1", Y_P4);
        assertThat(shownY(p4)).isEqualTo("0.00/0.00 1.10 LAST YAHOO 2026-11-20T16:00:00 QUOTED");
        assertThat(p4.get("automaticMark").asText()).isEqualTo("1.10");
        assertThat(p4.get("fetchFailed").asBoolean()).isFalse();
        JsonNode g = greeks("1", Y_P4);
        assertThat(shownGreeks(g)).isEqualTo(okG("0.00", "null", "null", "null", "null", "null", "null", "null",
                "null"));
        assertThat(g.get("source").asText()).isEqualTo("YAHOO");
        allGreeksNull(storedContract("QRTV", FEB, "45", "PUT"));
        assertThat(val(optRow("1", Y_P4))).as("LAST mark is used for P&L").isEqualTo(okVal("-110.00", "-5.00",
                "-4.76"));
        // P1 in the same run: kept
        assertThat(shownY(optionQuote("1", Y_P1))).isEqualTo("3.90/4.10 4.00 MID YAHOO 2026-11-20T10:00:00 QUOTED");
    }

    @Test
    @DisplayName("AC6（Q271）P4 last 也为 0（且从未有效报价）→ 无报价（NEVER_QUOTED、Greeks NO_QUOTE）")
    void ac6NewContractNoQuoteAtAll() {
        scenarioPulled052();
        setNow("2026-11-20T15:00");
        openN("STO", 1, "QRTV", FEB, "45", "PUT", "105.00", "2026-11-20T15:00");
        ysources().handshake(crumbOk()).date("QRTV", FEB, febAfterHours(yc(OCC_Y_P4, "0", "0", "0", "0")))
                .date("QRTV", DEC, decAfterHours()).dflt("IXQZ", ok(ixqzBody("61.40", "2026-11-20T16:00"))).apply();
        tickAt("2026-11-20T16:20", "CLOSE");

        JsonNode p4 = optionQuote("1", Y_P4);
        assertThat(shownY(p4)).isEqualTo("null/null null null null null NEVER_QUOTED");
        assertThat(p4.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p4.get("staleLabel").isNull()).isTrue();
        assertThat(shownGreeks(greeks("1", Y_P4))).isEqualTo(NO_QUOTE_G);
        assertThat(val(optRow("1", Y_P4))).isEqualTo(NO_QUOTE_VAL);
    }

    // ============================================================================================== AC 7

    @Test
    @DisplayName("AC7 11:00 QRTV 返回 403（重新握手一次后仍 403），Finnhub QRTV 50.20：标的价 50.20（FINNHUB），P1、P2、P3 保留 10:00 的值并标 行情未更新（上次：11-20 10:00）")
    void ac7Http403FallsBackToFinnhub() {
        scenarioPulled052();
        ysources().handshake(crumbOk()).date("QRTV", FEB, httpError(403)).date("QRTV", DEC, httpError(403))
                .dflt("QRTV", httpError(403)).dflt("IXQZ", ixqzOkY())
                .finnhub("QRTV", finnhubAt("50.20", "2026-11-20T10:59")).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null YAHOO",
                "QRTV FALLBACK YAHOO HTTP_ERROR null SUCCESS FINNHUB");
        String detail = result(t, "QRTV").get("failureDetail").asText();
        assertThat(detail).contains("HTTP 403").contains("2026-12-18")
                .doesNotContain(CRUMB).doesNotContain("crumb=").doesNotContain("http");
        assertThat(allRequests()).as("PUT sources dropped the session → handshake; 403 → one re-handshake + one resend")
                .containsExactly("YAHOO HANDSHAKE", "YAHOO OPTIONS IXQZ -", "YAHOO OPTIONS QRTV 2026-12-18",
                        "YAHOO HANDSHAKE", "YAHOO OPTIONS QRTV 2026-12-18", "FINNHUB QRTV");

        JsonNode u = underlyingQuote("1", Y_P1);
        assertThat(u.get("price").asText()).isEqualTo("50.20");
        assertThat(u.get("source").asText()).isEqualTo("FINNHUB");
        assertThat(u.get("fetchFailed").asBoolean()).isFalse();
        for (String[] row : new String[][]{{"1", Y_P1, "3.90/4.10 4.00"}, {"1", Y_P2, "0.60/0.70 0.65"},
                {accountB, Y_P3, "4.00/4.20 4.10"}}) {
            JsonNode q = optionQuote(row[0], row[1]);
            assertThat(shownY(q)).isEqualTo(row[2] + " MID YAHOO 2026-11-20T10:00:00 QUOTED");
            assertThat(q.get("fetchFailed").asBoolean()).isTrue();
            assertThat(q.get("staleLabel").asText()).isEqualTo(STALE_1000);
        }
        assertThat(shownGreeks(greeks("1", Y_P1))).as("Greeks kept with the quote").isEqualTo(Y_P1_G);
        assertThat(storedUnderlying("QRTV").get("lastYahooResult").asText()).isEqualTo("HTTP_ERROR");
    }

    @Test
    @DisplayName("AC7 11:00 起会话握手持续失败：所有标的 HANDSHAKE_FAILED、Finnhub 兜底股价；11:30 不提示，11:31（Q270 90 分钟）顶部 行情未更新（上次：2026-11-20 10:00 美东）")
    void ac7HandshakeFailsTopWarningAfter90Minutes() {
        scenarioPulled052();
        ysources().handshake(httpError(401)).date("QRTV", FEB, febOk()).date("QRTV", DEC, decOk())
                .dflt("IXQZ", ixqzOkY()).finnhub("QRTV", finnhubAt("50.20", "2026-11-20T10:59"))
                .finnhub("IXQZ", finnhubAt("61.00", "2026-11-20T10:59")).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ FALLBACK YAHOO HANDSHAKE_FAILED null SUCCESS FINNHUB",
                "QRTV FALLBACK YAHOO HANDSHAKE_FAILED null SUCCESS FINNHUB");
        assertThat(result(t, "QRTV").get("failureDetail").asText()).containsIgnoringCase("handshake");
        assertThat(allRequests()).as("no Yahoo data request after a failed handshake")
                .containsExactly("YAHOO HANDSHAKE", "FINNHUB IXQZ", "FINNHUB QRTV");
        assertThat(underlyingQuote("1", Y_P1).get("price").asText()).isEqualTo("50.20");
        assertThat(stockQuote(accountB, "IXQZ").get("price").asText()).isEqualTo("61.00");
        assertThat(optionQuote("1", Y_P1).get("staleLabel").asText()).isEqualTo(STALE_1000);

        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("lastSuccessAtLocal").asText()).as("a handshake-failed run is not a success")
                .isEqualTo("2026-11-20T10:00:00");
        assertThat(s.get("lastAttemptAtLocal").asText()).isEqualTo("2026-11-20T11:00:00");

        setNow("2026-11-20T11:30");
        assertThat(status().get("stale").asBoolean()).as("11:30 = exactly 90 minutes").isFalse();
        setNow("2026-11-20T11:31");
        s = status();
        assertThat(s.get("stale").asBoolean()).isTrue();
        assertThat(s.get("warning").asText()).isEqualTo("行情未更新（上次：2026-11-20 10:00 美东）");
        assertThat(s.get("optionSource").asText()).isEqualTo("YAHOO");
    }

    // ============================================================================================== AC 8

    @Test
    @DisplayName("AC8 11:00 拉取成功但 2026-12-18 响应中没有 P2：P2 Mark 0.65，标 数据源已无该合约（最后报价：11-20 10:00）")
    void ac8MissingFromChain() {
        scenarioPulled052();
        ysources().handshake(crumbOk()).date("QRTV", FEB, febOk())
                .date("QRTV", DEC, ok(qrtvDec("50.10", "2026-11-20T11:00",
                        yc("QRTV261218P00040000", "0.10", "0.15", "0.12", "0.4000"))))
                .dflt("IXQZ", ixqzOkY()).apply();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(yres(t)).contains("QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        JsonNode p2 = optionQuote("1", Y_P2);
        assertThat(shownY(p2)).isEqualTo("0.60/0.70 0.65 MID YAHOO 2026-11-20T10:00:00 MISSING_FROM_SOURCE");
        assertThat(p2.get("contractStatusLabel").asText()).isEqualTo("数据源已无该合约（最后报价：11-20 10:00）");
        assertThat(p2.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p2.get("staleLabel").isNull()).isTrue();
        assertThat(shownGreeks(greeks("1", Y_P2))).as("Q143 last Greeks kept").isEqualTo(Y_P2_G);
        assertThat(storedContract("QRTV", DEC, "45", "PUT").get("missingSince").asText())
                .isEqualTo("2026-11-20T16:00:00Z");
    }

    // ============================================================================================== AC 9

    @Test
    @DisplayName("AC9 P1 Mark 手动设为 4.20：Greeks 不变，标注 Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整；状态给出 YAHOO 与 无风险利率 0.00")
    void ac9ManualMarkNote() {
        scenarioPulled052();
        setNow("2026-11-20T10:05");
        JsonNode o = overrideMark("QRTV", FEB, "50", "PUT", "4.20");
        assertThat(o.get("mark").asText()).isEqualTo("4.20");
        assertThat(o.get("automaticMark").asText()).isEqualTo("4.00");
        assertThat(o.get("source").asText()).isEqualTo("YAHOO");

        JsonNode g = greeks("1", Y_P1);
        assertThat(shownGreeks(g)).isEqualTo(okGNote("30.00", "47.01", "-0.4701", "-5.30", "0.0530", "1.63",
                "-0.0163", "-9.95", "0.0995", "Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整"));
        assertThat(g.get("source").asText()).isEqualTo("YAHOO");
        approx(storedContract("QRTV", FEB, "50", "PUT"), "delta", "-0.47010736");
        assertThat(val(optRow("1", Y_P1))).as("P&L uses the manual mark").isEqualTo(okVal("-420.00", "-40.00",
                "-10.53"));

        JsonNode s = status();
        assertThat(s.get("optionSource").asText()).isEqualTo("YAHOO");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("0.00");
    }

    // ============================================================================================== AC 10

    /** CBOE chain (FEAT-016 shape) with P1 3.40 / 3.60 (mark 3.50) and CBOE's own Greeks. */
    ObjectNode cboeP1(String bid, String ask) {
        return ok(cboeBody("QRTV", "49.80", "2026-11-20 09:45:00", List.of(option(OCC_Y_P1, "bid", bid, "ask", ask,
                "theo", "3.48", "iv", "0.2950", "delta", "-0.4600", "gamma", "0.0520", "theta", "-0.0170", "vega",
                "0.0980", "rho", "-0.0500"))));
    }

    @Test
    @DisplayName("AC10 升级前 P1 有 CBOE 报价（3.50，来源 CBOE），之后 Yahoo 不可达：P1 仍 3.50（CBOE、CBOE 的 Greeks）并标 行情未更新，录入照常；Yahoo 无有效报价 / 只有 last 时仍为 CBOE，有效报价才替换为 YAHOO")
    void ac10LegacyCboeQuoteShownUntilValidYahoo() {
        settings("CBOE", null);
        scenario052();
        sources().cboe("QRTV", cboeP1("3.40", "3.60")).cboe("IXQZ", ixqzOk()).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", Y_P1))).isEqualTo("3.40/3.60 3.50 MID CBOE 2026-11-20T09:45:00 QUOTED");
        String cboeG = okG("29.50", "46.00", "-0.4600", "-5.20", "0.0520", "1.70", "-0.0170", "-9.80", "0.0980");
        assertThat(shownGreeks(greeks("1", Y_P1))).isEqualTo(cboeG);

        // "upgrade": Yahoo, unreachable (handshake fails); no Finnhub key
        settings("YAHOO", null);
        ysources().noKey().handshake(unreachable()).apply();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(yres(t)).contains("QRTV FAILED YAHOO HANDSHAKE_FAILED null NO_KEY null");
        JsonNode p1 = optionQuote("1", Y_P1);
        assertThat(shownY(p1)).isEqualTo("3.40/3.60 3.50 MID CBOE 2026-11-20T09:45:00 QUOTED");
        assertThat(p1.get("staleLabel").asText()).isEqualTo(STALE_1000);
        JsonNode g = greeks("1", Y_P1);
        assertThat(shownGreeks(g)).isEqualTo(cboeG);
        assertThat(g.get("source").asText()).isEqualTo("CBOE");
        assertThat(storedContract("QRTV", FEB, "50", "PUT").get("source").asText()).isEqualTo("CBOE");
        // bookkeeping unaffected (rule 10)
        setNow("2026-11-20T11:05");
        openN("STO", 1, "QRTV", "2027-01-15", "40", "PUT", "60.00", "2026-11-20T11:04");
        assertThat(optionQuote("1", "QRTV 2027-01-15 40 P").get("mark").isNull()).isTrue();

        // Yahoo back, NONE for P1 → CBOE kept
        ysources().handshake(crumbOk())
                .date("QRTV", FEB, ok(qrtvFeb("50.00", "2026-11-20T12:00", yc(OCC_Y_P1, "0", "0", "0", "0.3"),
                        p3Y())))
                .date("QRTV", DEC, decOk()).date("QRTV", "2027-01-15", ok(yBody("QRTV", "50.00", "2026-11-20T12:00",
                        QRTV_EXPS, "2027-01-15", List.of())))
                .dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T12:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", Y_P1))).as("NONE keeps the CBOE quote")
                .isEqualTo("3.40/3.60 3.50 MID CBOE 2026-11-20T09:45:00 QUOTED");
        assertThat(optionQuote("1", Y_P1).get("fetchFailed").asBoolean()).isFalse();

        // LAST_ONLY → CBOE quote has a usable mark → kept
        ysources().handshake(crumbOk())
                .date("QRTV", FEB, ok(qrtvFeb("50.00", "2026-11-20T13:00", yc(OCC_Y_P1, "0", "0", "3.95", "0.3"),
                        p3Y())))
                .date("QRTV", DEC, decOk()).date("QRTV", "2027-01-15", ok(yBody("QRTV", "50.00", "2026-11-20T13:00",
                        QRTV_EXPS, "2027-01-15", List.of())))
                .dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T13:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", Y_P1))).as("LAST_ONLY keeps the CBOE quote")
                .isEqualTo("3.40/3.60 3.50 MID CBOE 2026-11-20T09:45:00 QUOTED");
        assertThat(shownGreeks(greeks("1", Y_P1))).isEqualTo(cboeG);

        // VALID → replaced by Yahoo, Greeks computed
        ysources().handshake(crumbOk())
                .date("QRTV", FEB, ok(qrtvFeb("50.00", "2026-11-20T10:00", p1Y(), p3Y())))
                .date("QRTV", DEC, decOk()).date("QRTV", "2027-01-15", ok(yBody("QRTV", "50.00", "2026-11-20T10:00",
                        QRTV_EXPS, "2027-01-15", List.of())))
                .dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T14:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", Y_P1))).isEqualTo("3.90/4.10 4.00 MID YAHOO 2026-11-20T10:00:00 QUOTED");
        assertThat(shownGreeks(greeks("1", Y_P1))).isEqualTo(Y_P1_G);
        assertThat(greeks("1", Y_P1).get("source").asText()).isEqualTo("YAHOO");
        JsonNode raw = storedContract("QRTV", FEB, "50", "PUT");
        assertThat(raw.get("source").asText()).isEqualTo("YAHOO");
        assertThat(raw.get("theo").isNull()).as("CBOE theo cleared on replacement").isTrue();
        assertThat(raw.get("rho").isNull()).isTrue();
    }

    @Test
    @DisplayName("AC10 数据源改为 CBOE：只请求假 CBOE（不请求 Yahoo、不握手），行为同 FEAT-016（CBOE 原值 Greeks、Q139 theo mark、不用 r）")
    void ac10CboeSourceBehavesLikeFeat016() {
        JsonNode s = settings("cboe", "5.00");
        assertThat(s.get("optionSource").asText()).as("case-insensitive").isEqualTo("CBOE");
        scenario052();
        scenarioYahoo().cboe("QRTV", ok(cboeBody("QRTV", "49.80", "2026-11-20 09:45:00", List.of(
                        option(OCC_Y_P1, "bid", "3.40", "ask", "3.60", "theo", "3.48", "iv", "0.2950", "delta",
                                "-0.4600", "gamma", "0.0520", "theta", "-0.0170", "vega", "0.0980"),
                        option(OCC_Y_P2, "bid", "0", "ask", "0.70", "theo", "0.64")))))
                .cboe("IXQZ", ixqzOk()).apply();
        // a configured fake Yahoo must not be touched either
        clearRequests();
        JsonNode t = tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS CBOE null SUCCESS null CBOE",
                "QRTV SUCCESS CBOE null SUCCESS null CBOE");
        assertThat(allRequests()).containsExactlyInAnyOrder("CBOE IXQZ", "CBOE QRTV");
        assertThat(shownY(optionQuote("1", Y_P1))).isEqualTo("3.40/3.60 3.50 MID CBOE 2026-11-20T09:45:00 QUOTED");
        assertThat(shownY(optionQuote("1", Y_P2))).as("Q139 theo fallback still applies under CBOE")
                .isEqualTo("0.00/0.70 0.64 THEO CBOE 2026-11-20T09:45:00 QUOTED");
        JsonNode g = greeks("1", Y_P1);
        assertThat(shownGreeks(g)).as("CBOE Greeks stored as received, r not used")
                .isEqualTo(okG("29.50", "46.00", "-0.4600", "-5.20", "0.0520", "1.70", "-0.0170", "-9.80", "0.0980"));
        assertThat(g.get("source").asText()).isEqualTo("CBOE");
        num(storedContract("QRTV", FEB, "50", "PUT"), "delta", "-0.46");
        assertThat(status().get("optionSource").asText()).isEqualTo("CBOE");
        assertThat(underlyingQuote("1", Y_P1).get("source").asText()).isEqualTo("CBOE");

        setNow("2026-11-20T10:05");
        overrideMark("QRTV", FEB, "50", "PUT", "3.70");
        assertThat(text(greeks("1", Y_P1).get("manualMarkNote")))
                .isEqualTo("Greeks 按 CBOE 报价（11-20 09:45），未随手动 Mark 调整");
    }
}
