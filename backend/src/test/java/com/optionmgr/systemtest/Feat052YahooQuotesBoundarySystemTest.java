package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA boundary tests for FEAT-052 Yahoo 行情 (contract docs/api/FEAT-052.md): session handshake / re-handshake, 429,
 * whole-symbol failure, VALID / LAST_ONLY / NONE classification, Black-Scholes null cases, manual mark, schedule,
 * settings endpoint. Scenario and fakes: {@link Feat052YahooQuotesSystemTestBase}.
 */
@DisplayName("FEAT-052 Yahoo 行情（边界：握手、429、整标的失败、有效报价分类、Greeks 存空、手动 mark、时刻、设置）")
class Feat052YahooQuotesBoundarySystemTest extends Feat052YahooQuotesSystemTestBase {

    static final String NEVER_ROW = "行情未更新（从未成功）";

    // ============================================================================================== handshake

    @Test
    @DisplayName("首次拉取握手失败（getcrumb 401）：所有标的 HANDSHAKE_FAILED，不发 Yahoo 数据请求；Finnhub 兜底股价照常；期权 无报价 + 行情未更新（从未成功）")
    void handshakeFailsOnFirstPull() {
        scenario052();
        scenarioYahoo().handshake(httpError(401)).finnhub("QRTV", finnhubAt("50.20", "2026-11-20T09:59"))
                .finnhub("IXQZ", finnhubAt("61.00", "2026-11-20T09:59")).apply();
        JsonNode t = tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ FALLBACK YAHOO HANDSHAKE_FAILED null SUCCESS FINNHUB",
                "QRTV FALLBACK YAHOO HANDSHAKE_FAILED null SUCCESS FINNHUB");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE", "FINNHUB IXQZ", "FINNHUB QRTV");
        for (JsonNode r : t.get("results")) {
            assertThat(r.get("failureDetail").asText()).containsIgnoringCase("handshake").doesNotContain(CRUMB)
                    .doesNotContain("testcrumb01").doesNotContain("cookie");
        }

        assertThat(shownU(underlyingQuote("1", Y_P1)))
                .isEqualTo("50.20 FINNHUB 2026-11-20T09:59:00 false null");
        assertThat(shownU(stockQuote(accountB, "IXQZ"))).isEqualTo("61.00 FINNHUB 2026-11-20T09:59:00 false null");
        for (String[] row : new String[][]{{"1", Y_P1}, {"1", Y_P2}, {accountB, Y_P3}}) {
            JsonNode q = optionQuote(row[0], row[1]);
            assertThat(shownY(q)).isEqualTo("null/null null null null null NEVER_QUOTED");
            assertThat(q.get("fetchFailed").asBoolean()).isTrue();
            assertThat(q.get("staleLabel").asText()).isEqualTo(NEVER_ROW);
            assertThat(shownGreeks(greeks(row[0], row[1]))).isEqualTo(NO_QUOTE_G);
        }
        assertThat(storedUnderlying("QRTV").get("lastYahooResult").asText()).isEqualTo("HANDSHAKE_FAILED");
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isTrue();
        assertThat(s.get("warning").asText()).isEqualTo(NEVER_SUCCEEDED_WARNING);
    }

    @Test
    @DisplayName("握手网络错误（fc / getcrumb 不可达）同样 HANDSHAKE_FAILED；无 key 时 Finnhub NO_KEY → FAILED；之后握手恢复即成功")
    void handshakeUnreachableThenRecovers() {
        scenario052();
        scenarioYahoo().handshake(unreachable()).noKey().apply();
        JsonNode t = tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ FAILED YAHOO HANDSHAKE_FAILED null NO_KEY null",
                "QRTV FAILED YAHOO HANDSHAKE_FAILED null NO_KEY null");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE");

        scenarioYahoo().apply();
        clearRequests();
        t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null YAHOO",
                "QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        assertThat(allRequests()).first().isEqualTo("YAHOO HANDSHAKE");
        assertThat(shownY(optionQuote("1", Y_P1))).isEqualTo("3.90/4.10 4.00 MID YAHOO 2026-11-20T10:00:00 QUOTED");
    }

    @Test
    @DisplayName("会话被拒：数据请求 401 → 丢弃会话、重新握手一次、重发这一个请求 → 成功；同一 run 再遇 401 → HTTP_ERROR，不再握手")
    void rejectedSessionReHandshakeOnce() {
        scenarioPulled052();
        ysources().handshake(crumbOk())
                .dflt("IXQZ", seq(httpError(401), ok(ixqzBody("61.50", "2026-11-20T11:00"))))
                .date("QRTV", DEC, seq(httpError(401), decOk())).date("QRTV", FEB, febOk()).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE", "YAHOO OPTIONS IXQZ -", "YAHOO HANDSHAKE",
                "YAHOO OPTIONS IXQZ -", "YAHOO OPTIONS QRTV 2026-12-18", "FINNHUB QRTV");
        assertThat(yres(t)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null YAHOO",
                "QRTV FAILED YAHOO HTTP_ERROR null NETWORK_ERROR null");
        assertThat(result(t, "QRTV").get("failureDetail").asText()).contains("HTTP 401");
        assertThat(stockQuote(accountB, "IXQZ").get("price").asText()).as("resent request succeeded")
                .isEqualTo("61.50");
        JsonNode p1 = optionQuote("1", Y_P1);
        assertThat(p1.get("fetchFailed").asBoolean()).isTrue();
        assertThat(p1.get("staleLabel").asText()).isEqualTo("行情未更新（上次：11-20 10:00）");
    }

    @Test
    @DisplayName("会话被拒（403）→ 重新握手成功 → 重发成功：该标的 SUCCESS，数据更新")
    void http403ThenReHandshakeSucceeds() {
        scenarioPulled052();
        ysources().handshake(crumbOk()).dflt("IXQZ", ixqzOkY())
                .date("QRTV", DEC, seq(httpError(403), ok(qrtvDec("50.40", "2026-11-20T11:00",
                        yc(OCC_Y_P2, "0.70", "0.80", "0.75", "0.3500")))))
                .date("QRTV", FEB, ok(qrtvFeb("50.40", "2026-11-20T11:00", p1Y(), p3Y()))).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE", "YAHOO OPTIONS IXQZ -",
                "YAHOO OPTIONS QRTV 2026-12-18", "YAHOO HANDSHAKE", "YAHOO OPTIONS QRTV 2026-12-18",
                "YAHOO OPTIONS QRTV 2027-02-19");
        assertThat(yres(t)).contains("QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        assertThat(shownY(optionQuote("1", Y_P2))).isEqualTo("0.70/0.80 0.75 MID YAHOO 2026-11-20T11:00:00 QUOTED");
        assertThat(underlyingQuote("1", Y_P1).get("price").asText()).isEqualTo("50.40");
    }

    @Test
    @DisplayName("重新握手失败 → 该标的及本 run 尚未处理的标的都 HANDSHAKE_FAILED，不再发 Yahoo 数据请求")
    void reHandshakeFails() {
        scenarioPulled052();
        ysources().noKey().handshake(seq(crumbOk(), httpError(401))).dflt("IXQZ", httpError(403))
                .date("QRTV", DEC, decOk()).date("QRTV", FEB, febOk()).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE", "YAHOO OPTIONS IXQZ -", "YAHOO HANDSHAKE");
        assertThat(yres(t)).containsExactly("IXQZ FAILED YAHOO HANDSHAKE_FAILED null NO_KEY null",
                "QRTV FAILED YAHOO HANDSHAKE_FAILED null NO_KEY null");
        assertThat(shownY(optionQuote("1", Y_P1))).isEqualTo("3.90/4.10 4.00 MID YAHOO 2026-11-20T10:00:00 QUOTED");
    }

    // ============================================================================================== 429

    @Test
    @DisplayName("429：该标的 HTTP_ERROR（failureDetail 含 HTTP 429），不重试、不重新握手；Finnhub 兜底；继续处理下一个标的")
    void http429NoRetry() {
        scenarioPulled052();
        ysources().handshake(crumbOk()).dflt("IXQZ", seq(httpError(429), ok(ixqzBody("99.00", "2026-11-20T11:00"))))
                .date("QRTV", DEC, decOk()).date("QRTV", FEB, febOk())
                .finnhub("IXQZ", finnhubAt("61.20", "2026-11-20T10:59")).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE", "YAHOO OPTIONS IXQZ -", "FINNHUB IXQZ",
                "YAHOO OPTIONS QRTV 2026-12-18", "YAHOO OPTIONS QRTV 2027-02-19");
        assertThat(yres(t)).containsExactly("IXQZ FALLBACK YAHOO HTTP_ERROR null SUCCESS FINNHUB",
                "QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        assertThat(result(t, "IXQZ").get("failureDetail").asText()).contains("HTTP 429");
        assertThat(shownU(stockQuote(accountB, "IXQZ"))).isEqualTo("61.20 FINNHUB 2026-11-20T10:59:00 false null");
    }

    // ============================================================================================== whole symbol

    @Test
    @DisplayName("一个到期日失败 → 整个标的失败：2026-12-18 返回 500 → 不再请求 2027-02-19；P1、P2、P3 都保留 10:00 值并标 行情未更新；标的价 Finnhub")
    void firstDateFailsNoMoreRequests() {
        scenarioPulled052();
        ysources().handshake(crumbOk()).dflt("IXQZ", ixqzOkY()).date("QRTV", DEC, httpError(500))
                .date("QRTV", FEB, ok(qrtvFeb("50.40", "2026-11-20T11:00", yc(OCC_Y_P1, "4.50", "4.70", "4.60",
                        "0.3200"), p3Y())))
                .finnhub("QRTV", finnhubAt("50.20", "2026-11-20T10:59")).apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).containsExactly("YAHOO HANDSHAKE", "YAHOO OPTIONS IXQZ -",
                "YAHOO OPTIONS QRTV 2026-12-18", "FINNHUB QRTV");
        assertThat(yres(t)).contains("QRTV FALLBACK YAHOO HTTP_ERROR null SUCCESS FINNHUB");
        assertKeptAt1000();
    }

    @Test
    @DisplayName("一个到期日失败 → 整个标的失败：2026-12-18 成功（新值）但 2027-02-19 返回 500 → 12-18 的新值也丢弃，标的价用 Finnhub（不用 Yahoo 12-18 的 50.40）")
    void laterDateFailsDiscardsEarlierResponse() {
        scenarioPulled052();
        ysources().handshake(crumbOk()).dflt("IXQZ", ixqzOkY())
                .date("QRTV", DEC, ok(qrtvDec("50.40", "2026-11-20T11:00", yc(OCC_Y_P2, "0.90", "1.00", "0.95",
                        "0.3600"))))
                .date("QRTV", FEB, unreachable())
                .finnhub("QRTV", finnhubAt("50.20", "2026-11-20T10:59")).apply();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(yres(t)).contains("QRTV FALLBACK YAHOO NETWORK_ERROR null SUCCESS FINNHUB");
        assertKeptAt1000();
        num(storedContract("QRTV", DEC, "45", "PUT"), "bid", "0.60");
    }

    void assertKeptAt1000() {
        for (String[] row : new String[][]{{"1", Y_P1, "3.90/4.10 4.00"}, {"1", Y_P2, "0.60/0.70 0.65"},
                {accountB, Y_P3, "4.00/4.20 4.10"}}) {
            JsonNode q = optionQuote(row[0], row[1]);
            assertThat(shownY(q)).as(row[1]).isEqualTo(row[2] + " MID YAHOO 2026-11-20T10:00:00 QUOTED");
            assertThat(q.get("fetchFailed").asBoolean()).isTrue();
            assertThat(q.get("staleLabel").asText()).isEqualTo("行情未更新（上次：11-20 10:00）");
        }
        assertThat(shownGreeks(greeks("1", Y_P1))).isEqualTo(Y_P1_G);
        assertThat(shownGreeks(greeks("1", Y_P2))).isEqualTo(Y_P2_G);
        assertThat(shownU(underlyingQuote("1", Y_P1))).isEqualTo("50.20 FINNHUB 2026-11-20T10:59:00 false null");
    }

    // ============================================================================================== VALID / LAST / NONE

    ObjectNode febWith(String nyTime, ObjectNode p4) {
        return ok(qrtvFeb("50.00", nyTime, p1Y(), p3Y(), p4));
    }

    void applyWithP4(String nyTime, ObjectNode p4) {
        ysources().handshake(crumbOk()).date("QRTV", FEB, febWith(nyTime, p4))
                .date("QRTV", DEC, ok(qrtvDec("50.00", nyTime, p2Y()))).dflt("IXQZ", ok(ixqzBody("61.10", nyTime)))
                .apply();
    }

    @Test
    @DisplayName("LAST_ONLY 只填空行：P4 从未报价 → 16:20 last 1.10（IV 0.35）写入、Greeks 按本次值算；次日 NONE → 保留 1.10；LAST_ONLY 1.20 → 替换（LAST 不算可用报价）；VALID → 1.35；之后 LAST_ONLY → 保留 VALID")
    void lastOnlyFillsOnlyEmptyRows() {
        scenarioPulled052();
        setNow("2026-11-20T15:00");
        openN("STO", 1, "QRTV", FEB, "45", "PUT", "105.00", "2026-11-20T15:00");
        ysources().handshake(crumbOk())
                .date("QRTV", FEB, ok(qrtvFeb("50.30", "2026-11-20T16:00", yc(OCC_Y_P1, "0", "0", "3.95", "0.3"),
                        yc(OCC_Y_P3, "0", "0", "4.10", "0.3"), yc(OCC_Y_P4, "0", "0", "1.10", "0.3500"))))
                .date("QRTV", DEC, ok(qrtvDec("50.30", "2026-11-20T16:00", p2Y())))
                .dflt("IXQZ", ok(ixqzBody("61.10", "2026-11-20T16:00"))).apply();
        tickAt("2026-11-20T16:20", "CLOSE");
        assertThat(shownY(optionQuote("1", Y_P4))).isEqualTo("0.00/0.00 1.10 LAST YAHOO 2026-11-20T16:00:00 QUOTED");
        // S 50.30 (same response), K 45, σ 0.35, T = 91 days (16:00 → 16:00, both EST)
        assertThat(shownGreeks(greeks("1", Y_P4))).isEqualTo(okG("35.00", "23.44", "-0.2344", "-3.49", "0.0349",
                "1.48", "-0.0148", "-7.71", "0.0771"));
        approx(storedContract("QRTV", FEB, "45", "PUT"), "delta", "-0.2343804");

        // next trading day 10:00: NONE → the LAST quote is kept, no failure label
        applyWithP4("2026-11-23T10:00", yc(OCC_Y_P4, "0", "0", "0", "0"));
        tickAt("2026-11-23T10:00", "SCHEDULED");
        JsonNode p4 = optionQuote("1", Y_P4);
        assertThat(shownY(p4)).isEqualTo("0.00/0.00 1.10 LAST YAHOO 2026-11-20T16:00:00 QUOTED");
        assertThat(p4.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p4.get("staleLabel").isNull()).isTrue();

        // 11:00: LAST_ONLY again → replaces the LAST quote
        applyWithP4("2026-11-23T11:00", yc(OCC_Y_P4, "0", "0", "1.20", "0.3600"));
        tickAt("2026-11-23T11:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", Y_P4))).isEqualTo("0.00/0.00 1.20 LAST YAHOO 2026-11-23T11:00:00 QUOTED");
        approx(storedContract("QRTV", FEB, "45", "PUT"), "delta", "-0.24704127");

        // 12:00: VALID → MID
        applyWithP4("2026-11-23T12:00", yc(OCC_Y_P4, "1.30", "1.40", "1.32", "0.3600"));
        tickAt("2026-11-23T12:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", Y_P4))).isEqualTo("1.30/1.40 1.35 MID YAHOO 2026-11-23T12:00:00 QUOTED");

        // 13:00: LAST_ONLY → the VALID quote is kept
        applyWithP4("2026-11-23T13:00", yc(OCC_Y_P4, "0", "0", "1.50", "0.3600"));
        tickAt("2026-11-23T13:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", Y_P4))).isEqualTo("1.30/1.40 1.35 MID YAHOO 2026-11-23T12:00:00 QUOTED");
    }

    @Test
    @DisplayName("Q271 有效报价判定：bid 0 / ask 0.05 有效 → Mark 0.025（MID，不取整）；ask < bid 无效 → 从未报价时用 last（LAST）且 Greeks 照算；bid、ask 缺失 + last 缺失 → 无报价")
    void validQuoteDefinition() {
        scenario052();
        ysources().handshake(crumbOk())
                .date("QRTV", FEB, ok(qrtvFeb("50.00", "2026-11-20T10:00", yc(OCC_Y_P1, "0", "0.05", "0.03", "0.3"),
                        yc(OCC_Y_P3, "4.20", "4.00", "4.10", "0.3000"))))
                .date("QRTV", DEC, ok(qrtvDec("50.00", "2026-11-20T10:00", yc(OCC_Y_P2, null, null, null, "0.35"))))
                .dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        JsonNode p1 = optionQuote("1", Y_P1);
        assertThat(shownY(p1)).isEqualTo("0.00/0.05 0.025 MID YAHOO 2026-11-20T10:00:00 QUOTED");
        JsonNode p3 = optionQuote(accountB, Y_P3);
        assertThat(shownY(p3)).isEqualTo("4.20/4.00 4.10 LAST YAHOO 2026-11-20T10:00:00 QUOTED");
        assertThat(shownGreeks(greeks(accountB, Y_P3))).isEqualTo(Y_P3_G);
        JsonNode p2 = optionQuote("1", Y_P2);
        assertThat(shownY(p2)).isEqualTo("null/null null null null null NEVER_QUOTED");
        assertThat(p2.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p2.get("staleLabel").isNull()).isTrue();
    }

    // ============================================================================================== Black-Scholes nulls

    @Test
    @DisplayName("Greeks 存空：IV 缺失 → IV —、Greeks —；IV 为负 → Greeks —（IV 照常显示）；单字段格式错误（IV \"N/A\"）按缺失；报价本身照常有效")
    void greeksNullForMissingOrNonPositiveIv() {
        scenario052();
        ObjectNode badIv = p2Y();
        badIv.put("impliedVolatility", "N/A");
        ysources().handshake(crumbOk())
                .date("QRTV", FEB, ok(qrtvFeb("50.00", "2026-11-20T10:00", yc(OCC_Y_P1, "3.90", "4.10", "3.95", null),
                        yc(OCC_Y_P3, "4.00", "4.20", "4.10", "-0.1000"))))
                .date("QRTV", DEC, ok(qrtvDec("50.00", "2026-11-20T10:00", badIv))).dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");

        String allNull = okG("null", "null", "null", "null", "null", "null", "null", "null", "null");
        assertThat(optionQuote("1", Y_P1).get("mark").asText()).isEqualTo("4.00");
        assertThat(shownGreeks(greeks("1", Y_P1))).as("IV missing").isEqualTo(allNull);
        allGreeksNull(storedContract("QRTV", FEB, "50", "PUT"));
        assertThat(shownGreeks(greeks(accountB, Y_P3))).as("IV < 0: Greeks null, IV still shown")
                .isEqualTo(okG("-10.00", "null", "null", "null", "null", "null", "null", "null", "null"));
        assertThat(optionQuote("1", Y_P2).get("mark").asText()).isEqualTo("0.65");
        assertThat(shownGreeks(greeks("1", Y_P2))).as("IV \"N/A\" = missing").isEqualTo(allNull);
        assertThat(storedContract("QRTV", DEC, "45", "PUT").get("iv").isNull()).isTrue();
    }

    @Test
    @DisplayName("S ≤ 0：2027-02-19 响应 regularMarketPrice 为 0 → 该响应的合约 Greeks 存空（不用别的响应 / Finnhub 的价）；标的价取 12-18 响应的 50.00；12-18 合约照常计算")
    void greeksNullForNonPositiveUnderlying() {
        scenario052();
        ysources().handshake(crumbOk()).date("QRTV", FEB, ok(qrtvFeb("0", "2026-11-20T10:00", p1Y(), p3Y())))
                .date("QRTV", DEC, decOk()).dflt("IXQZ", ixqzOkY())
                .finnhub("QRTV", finnhubAt("50.20", "2026-11-20T09:59")).apply();
        JsonNode t = tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(yres(t)).contains("QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        assertThat(shownY(optionQuote("1", Y_P1))).isEqualTo("3.90/4.10 4.00 MID YAHOO 2026-11-20T10:00:00 QUOTED");
        assertThat(shownGreeks(greeks("1", Y_P1)))
                .isEqualTo(okG("30.00", "null", "null", "null", "null", "null", "null", "null", "null"));
        assertThat(shownGreeks(greeks("1", Y_P2))).isEqualTo(Y_P2_G);
        assertThat(shownU(underlyingQuote("1", Y_P1))).isEqualTo("50.00 YAHOO 2026-11-20T10:00:00 false null");
    }

    @Test
    @DisplayName("T ≤ 0（到期日当天）：10:00 报价正常计算；16:00 报价（regularMarketTime = 到期日 16:00 美东）→ Greeks —，IV 照常；Mark 照常更新")
    void greeksNullOnExpirationDayClose() {
        scenario052();
        openN("STO", 1, "QRTV", "2026-11-20", "48", "PUT", "40.00", "2026-11-19T11:00");
        String occ = "QRTV261120P00048000";
        List<String> exps = List.of("2026-11-20", "2026-11-27", DEC, FEB);
        ysources().handshake(crumbOk())
                .date("QRTV", "2026-11-20", ok(yBody("QRTV", "50.00", "2026-11-20T10:00", exps, "2026-11-20",
                        List.of(yc(occ, "0.10", "0.14", "0.12", "0.4000")))))
                .date("QRTV", FEB, febOk()).date("QRTV", DEC, decOk()).dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        String label = "QRTV 2026-11-20 48 P";
        JsonNode g = greeks("1", label);
        assertThat(g.get("status").asText()).isEqualTo("OK");
        assertThat(text(g.get("ivPercent"))).isEqualTo("40.00");
        for (String k : GREEKS) {
            assertThat(g.get(k + "PerContract").isNull()).as("%s computed before 16:00", k).isFalse();
        }

        ysources().handshake(crumbOk())
                .date("QRTV", "2026-11-20", ok(yBody("QRTV", "50.10", "2026-11-20T16:00", exps, "2026-11-20",
                        List.of(yc(occ, "0.01", "0.03", "0.02", "0.4500")))))
                .date("QRTV", FEB, ok(qrtvFeb("50.10", "2026-11-20T16:00", p1Y(), p3Y())))
                .date("QRTV", DEC, ok(qrtvDec("50.10", "2026-11-20T16:00", p2Y()))).dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T16:00", "SCHEDULED");
        assertThat(shownY(optionQuote("1", label))).isEqualTo("0.01/0.03 0.02 MID YAHOO 2026-11-20T16:00:00 QUOTED");
        assertThat(shownGreeks(greeks("1", label)))
                .isEqualTo(okG("45.00", "null", "null", "null", "null", "null", "null", "null", "null"));
        allGreeksNull(storedContract("QRTV", "2026-11-20", "48", "PUT"));
    }

    @Test
    @DisplayName("夏令时：2027-03-19 到期（到期日 16:00 为 EDT）按真实时间差 119 天 5 小时计算 T；IV 600% 不设上限照常计算")
    void dstTimeToExpiryAndExtremeIv() {
        scenario052();
        openN("BTO", 1, "QRTV", "2027-03-19", "50", "CALL", "500.00", "2026-11-19T11:00");
        openN("BTO", 1, "QRTV", "2027-03-19", "55", "CALL", "300.00", "2026-11-19T11:01");
        List<String> exps = List.of(DEC, FEB, "2027-03-19");
        ysources().handshake(crumbOk()).date("QRTV", FEB, febOk()).date("QRTV", DEC, decOk())
                .date("QRTV", "2027-03-19", ok(yBody("QRTV", "50.00", "2026-11-20T10:00", exps, "2027-03-19",
                        List.of(yc("QRTV270319C00050000", "5.00", "5.20", "5.10", "0.3000"),
                                yc("QRTV270319C00055000", "30.00", "31.00", "30.50", "6.0000")))))
                .dflt("IXQZ", ixqzOkY()).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        JsonNode raw = storedContract("QRTV", "2027-03-19", "50", "CALL");
        approx(raw, "delta", "0.53415675");
        approx(raw, "gamma", "0.04636777");
        approx(raw, "vega", "0.11357722");
        JsonNode extreme = greeks("1", "QRTV 2027-03-19 55 C");
        assertThat(text(extreme.get("ivPercent"))).isEqualTo("600.00");
        for (String k : GREEKS) {
            assertThat(extreme.get(k + "PerContract").isNull()).as("%s computed for IV 600%%", k).isFalse();
        }
        assertGreeksMatchStored("1");
    }

    // ============================================================================================== manual mark

    @Test
    @DisplayName("手动 mark 保留到下一次 VALID：NONE、LAST_ONLY、链中没有、标的失败都保留 4.20；VALID 4.30 / 4.50 → 删除手动值，Mark 4.40")
    void manualMarkKeptUntilValid() {
        scenarioPulled052();
        setNow("2026-11-20T10:05");
        overrideMark("QRTV", FEB, "50", "PUT", "4.20");

        String[][] steps = {
                {"2026-11-20T11:00", "NONE"}, {"2026-11-20T12:00", "LAST_ONLY"}, {"2026-11-20T13:00", "MISSING"},
                {"2026-11-20T14:00", "FAILED"}};
        for (String[] step : steps) {
            YSources ys = ysources().handshake(crumbOk()).date("QRTV", DEC, decOk()).dflt("IXQZ", ixqzOkY());
            switch (step[1]) {
                case "NONE" -> ys.date("QRTV", FEB, ok(qrtvFeb("50.00", step[0], yc(OCC_Y_P1, "0", "0", "0", "0.3"),
                        p3Y())));
                case "LAST_ONLY" -> ys.date("QRTV", FEB, ok(qrtvFeb("50.00", step[0],
                        yc(OCC_Y_P1, "0", "0", "3.80", "0.3"), p3Y())));
                case "MISSING" -> ys.date("QRTV", FEB, ok(qrtvFeb("50.00", step[0], p3Y())));
                default -> ys.date("QRTV", FEB, httpError(500));
            }
            ys.apply();
            tickAt(step[0], "SCHEDULED");
            JsonNode q = optionQuote("1", Y_P1);
            assertThat(q.get("mark").asText()).as("manual kept after %s", step[1]).isEqualTo("4.20");
            assertThat(q.get("manual").asBoolean()).isTrue();
            assertThat(q.get("automaticMark").asText()).isEqualTo("4.00");
            assertThat(text(greeks("1", Y_P1).get("manualMarkNote")))
                    .isEqualTo("Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整");
        }

        ysources().handshake(crumbOk()).date("QRTV", DEC, decOk()).dflt("IXQZ", ixqzOkY())
                .date("QRTV", FEB, ok(qrtvFeb("50.00", "2026-11-20T15:00", yc(OCC_Y_P1, "4.30", "4.50", "4.40",
                        "0.3000"), p3Y()))).apply();
        tickAt("2026-11-20T15:00", "SCHEDULED");
        JsonNode q = optionQuote("1", Y_P1);
        assertThat(shownY(q)).isEqualTo("4.30/4.50 4.40 MID YAHOO 2026-11-20T15:00:00 QUOTED");
        assertThat(q.get("manual").asBoolean()).isFalse();
        assertThat(greeks("1", Y_P1).get("manualMarkNote").isNull()).isTrue();
        assertThat(storedContract("QRTV", FEB, "50", "PUT").get("manualMark").isNull()).isTrue();
    }

    @Test
    @DisplayName("手动标的价：下一次 Yahoo 标的价成功时删除；手动 mark / 手动标的价都不触发 Greeks 重算（Q170）")
    void manualUnderlyingAndNoRecompute() {
        scenarioPulled052();
        setNow("2026-11-20T10:05");
        overrideUnderlying("QRTV", "55.00");
        assertThat(shownGreeks(greeks("1", Y_P1))).as("Greeks not recomputed with the manual price")
                .isEqualTo(Y_P1_G);
        scenarioYahoo().apply();
        tickAt("2026-11-20T11:00", "SCHEDULED");
        JsonNode u = underlyingQuote("1", Y_P1);
        assertThat(u.get("manual").asBoolean()).isFalse();
        assertThat(u.get("price").asText()).isEqualTo("50.00");
        assertThat(u.get("source").asText()).isEqualTo("YAHOO");
    }

    // ============================================================================================== chain content

    @Test
    @DisplayName("请求的到期日不在 Yahoo expirationDates（Yahoo 回了别的到期日的链）→ 算 链中已无该合约：从未报价 → 无报价（不算失败）；曾有报价 → MISSING_FROM_SOURCE")
    void requestedDateNotListed() {
        scenarioPulled052();
        ObjectNode otherChain = ok(yBody("QRTV", "50.10", "2026-11-20T11:00", List.of("2026-11-27", FEB),
                "2026-11-27", List.of(yc("QRTV261127P00045000", "0.05", "0.08", "0.06", "0.5000"))));
        ysources().handshake(crumbOk()).dflt("QRTV", otherChain).date("QRTV", FEB, febOk()).dflt("IXQZ", ixqzOkY())
                .apply();
        clearRequests();
        JsonNode t = tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).contains("YAHOO OPTIONS QRTV 2026-12-18");
        assertThat(yres(t)).contains("QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        JsonNode p2 = optionQuote("1", Y_P2);
        assertThat(shownY(p2)).isEqualTo("0.60/0.70 0.65 MID YAHOO 2026-11-20T10:00:00 MISSING_FROM_SOURCE");
        assertThat(p2.get("contractStatusLabel").asText()).isEqualTo("数据源已无该合约（最后报价：11-20 10:00）");
        assertThat(p2.get("fetchFailed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("响应格式变化 → INVALID_RESPONSE 只算该标的失败：非 JSON、optionChain.error 非空、result 为空、quote 缺 regularMarketTime")
    void invalidResponses() {
        scenario052();
        ObjectNode withError = ixqzBody("61.10", "2026-11-20T10:00");
        ObjectNode err = ((ObjectNode) withError.get("optionChain")).putObject("error");
        err.put("code", "Not Found");
        err.put("description", "No data found");
        ((ArrayNode) withError.get("optionChain").get("result")).removeAll();
        ObjectNode emptyResult = ixqzBody("61.10", "2026-11-20T10:00");
        ((ArrayNode) emptyResult.get("optionChain").get("result")).removeAll();
        ObjectNode noTime = ixqzBody("61.10", null);

        String[] slots = {"2026-11-20T10:00", "2026-11-20T11:00", "2026-11-20T12:00", "2026-11-20T13:00"};
        List<ObjectNode> bad = List.of(rawBody("<html>Will be right back</html>"), ok(withError), ok(emptyResult),
                ok(noTime));
        for (int i = 0; i < bad.size(); i++) {
            ysources().handshake(crumbOk()).date("QRTV", FEB, febOk()).date("QRTV", DEC, decOk())
                    .dflt("IXQZ", bad.get(i)).apply();
            JsonNode t = tickAt(slots[i], "SCHEDULED");
            assertThat(yres(t)).as("case %d", i).containsExactly(
                    "IXQZ FAILED YAHOO INVALID_RESPONSE null NETWORK_ERROR null",
                    "QRTV SUCCESS YAHOO SUCCESS null null YAHOO");
        }
        assertThat(stockQuote(accountB, "IXQZ").get("price").isNull()).isTrue();
        assertThat(optionQuote("1", Y_P1).get("mark").asText()).isEqualTo("4.00");
    }

    @Test
    @DisplayName("Q149 指数期权在 Yahoo 下也不请求：SPX 无报价、无失败标签")
    void indexSymbolsNotRequested() {
        scenario052();
        openN("BTO", 1, "SPX", DEC, "7000", "CALL", "500.00", "2026-11-19T11:00");
        scenarioYahoo().apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(allRequests()).noneMatch(r -> r.contains("SPX"));
        JsonNode q = optionQuote("1", "SPX 2026-12-18 7000 C");
        assertThat(q.get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(q.get("fetchFailed").asBoolean()).isFalse();
    }

    // ============================================================================================== schedule

    @Test
    @DisplayName("Q270 时刻（Yahoo）：交易日 10:00–16:00 每整点 SCHEDULED + 16:20 CLOSE；09:30 / 09:45 / 10:15 / 10:20 / 10:30 / 10:45 / 11:20 / 15:20 / 15:45 / 16:15 不拉取（定时器 cron 每小时 :20 也会触发）、不发请求；周六不拉；会话整天复用")
    void scheduleUnderYahoo() {
        scenario052();
        scenarioYahoo().apply();
        setNow("2026-11-20T09:30");
        clearRequests();
        for (String hm : List.of("09:30", "09:45")) {
            tickSkipped("2026-11-20T" + hm, "NOT_A_SLOT");
        }
        assertThat(allRequests()).isEmpty();
        int handshakes = 0;
        for (String hm : List.of("10:00", "10:15", "10:20", "10:30", "10:45", "11:00", "11:20", "12:00", "13:00",
                "14:00", "15:00", "15:20",
                "15:45", "16:00", "16:15", "16:20")) {
            clearRequests();
            boolean slot = hm.endsWith(":00") || hm.equals("16:20");
            if (slot) {
                tickAt("2026-11-20T" + hm, hm.equals("16:20") ? "CLOSE" : "SCHEDULED");
                List<String> reqs = allRequests();
                assertThat(reqs.stream().filter(r -> r.startsWith("YAHOO OPTIONS")).count()).as("data requests at %s",
                        hm).isEqualTo(3);
                handshakes += (int) reqs.stream().filter(r -> r.equals("YAHOO HANDSHAKE")).count();
            } else {
                tickSkipped("2026-11-20T" + hm, "NOT_A_SLOT");
                assertThat(allRequests()).as("nothing requested at %s", hm).isEmpty();
            }
        }
        assertThat(handshakes).as("one handshake, then the session is reused").isEqualTo(1);
        tickSkipped("2026-11-21T10:00", "NON_TRADING_DAY");
        tickSkipped("2026-11-23T09:30", "NOT_A_SLOT");
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    @Test
    @DisplayName("顶部提示阈值 90 分钟（Yahoo）：10:00 成功、之后全部失败 → 11:30 不提示，11:31 提示；次日 09:30 起算，11:00 不提示、11:01 提示")
    void staleThreshold90Minutes() {
        scenarioPulled052();
        ysources().handshake(unreachable()).noKey().apply();
        tickAt("2026-11-20T11:00", "SCHEDULED");
        setNow("2026-11-20T11:30");
        assertThat(status().get("stale").asBoolean()).isFalse();
        setNow("2026-11-20T11:31");
        assertThat(status().get("warning").asText()).isEqualTo("行情未更新（上次：2026-11-20 10:00 美东）");
        setNow("2026-11-23T09:31");
        assertThat(status().get("stale").asBoolean()).isFalse();
        setNow("2026-11-23T11:00");
        assertThat(status().get("stale").asBoolean()).isFalse();
        setNow("2026-11-23T11:01");
        assertThat(status().get("stale").asBoolean()).isTrue();
    }

    // ============================================================================================== settings

    @Test
    @DisplayName("PUT settings：大小写不敏感；利率至少 2 位小数（5 → 5.00、4.125）；0 与 20 为边界；null / 省略恢复配置（CBOE、0.00）；GET status 同步")
    void settingsValues() {
        assertThat(settings("yahoo", "5").get("riskFreeRatePercent").asText()).isEqualTo("5.00");
        assertThat(settings("Yahoo", "4.125").get("riskFreeRatePercent").asText()).isEqualTo("4.125");
        assertThat(status().get("riskFreeRatePercent").asText()).isEqualTo("4.125");
        assertThat(settings("YAHOO", "20").get("riskFreeRatePercent").asText()).isEqualTo("20.00");
        assertThat(settings("YAHOO", "0").get("riskFreeRatePercent").asText()).isEqualTo("0.00");

        JsonNode s = settings(null, null);
        assertThat(s.get("optionSource").asText()).as("test profile default").isEqualTo("CBOE");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("0.00");
        settings("YAHOO", "5.00");
        Resp r = settingsResp("{}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("optionSource").asText()).isEqualTo("CBOE");
        assertThat(status().get("optionSource").asText()).isEqualTo("CBOE");
    }

    @Test
    @DisplayName("PUT settings 错误：未知数据源 / 利率 < 0 / > 20 → 400 VALIDATION_ERROR（details 指出字段）；利率非数字、JSON 损坏 → 400 BAD_REQUEST；设置不变")
    void settingsErrors() {
        settings("YAHOO", "3.00");
        Resp r = settingsResp("{\"optionSource\":\"FOO\"}");
        assertErrorShape(r, 400, "VALIDATION_ERROR", SETTINGS);
        assertThat(details(r)).extracting(x -> x.split("=")[0]).containsExactly("optionSource");
        for (String bad : List.of("-0.01", "20.01", "100")) {
            Resp rr = settingsResp("{\"riskFreeRatePercent\":\"" + bad + "\"}");
            assertErrorShape(rr, 400, "VALIDATION_ERROR", SETTINGS);
            assertThat(details(rr)).as(bad).extracting(x -> x.split("=")[0]).containsExactly("riskFreeRatePercent");
        }
        assertErrorShape(settingsResp("{\"riskFreeRatePercent\":\"abc\"}"), 400, "BAD_REQUEST", SETTINGS);
        assertErrorShape(settingsResp("{\"optionSource\":"), 400, "BAD_REQUEST", SETTINGS);
        JsonNode s = status();
        assertThat(s.get("optionSource").asText()).as("unchanged after errors").isEqualTo("YAHOO");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("3.00");
    }

    @Test
    @DisplayName("reset 恢复设置（CBOE、0.00）、清 Yahoo 会话与假 Yahoo 配置（握手成功、标的 unreachable）")
    void resetRestoresSettingsAndYahoo() {
        settings("YAHOO", "5.00");
        scenarioPulled052();
        reset();
        JsonNode s = status();
        assertThat(s.get("optionSource").asText()).isEqualTo("CBOE");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("0.00");

        settings("YAHOO", null);
        scenario052();
        JsonNode t = tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(allRequests()).as("session dropped by reset → handshake again").first()
                .isEqualTo("YAHOO HANDSHAKE");
        assertThat(yres(t)).containsExactly("IXQZ FAILED YAHOO NETWORK_ERROR null NETWORK_ERROR null",
                "QRTV FAILED YAHOO NETWORK_ERROR null NETWORK_ERROR null");
    }

    @Test
    @DisplayName("PUT sources 丢弃 Yahoo 会话：同样的配置再 PUT 一次，下一次 run 重新握手")
    void putSourcesDropsSession() {
        scenarioPulled052();
        clearRequests();
        tickAt("2026-11-20T11:00", "SCHEDULED");
        assertThat(allRequests()).doesNotContain("YAHOO HANDSHAKE");
        scenarioYahoo().apply();
        clearRequests();
        tickAt("2026-11-20T12:00", "SCHEDULED");
        assertThat(allRequests()).first().isEqualTo("YAHOO HANDSHAKE");
    }
}
