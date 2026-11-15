package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-019 组合 Greeks 汇总: the 10 acceptance criteria end-to-end over HTTP (AC10 is mostly the
 * page — toggle, help tooltip, expiry filter — covered by e2e/tests/feat-019-portfolio-greeks.spec.ts; here only its
 * API side: the endpoint has no filter and always returns the whole account). Every read also asserts the contract
 * shape and recomputes the response from the positions endpoints (see {@link Feat019PortfolioGreeksSystemTestBase}).
 */
@DisplayName("FEAT-019 组合 Greeks 汇总（验收）")
class Feat019PortfolioGreeksAcceptanceSystemTest extends Feat019PortfolioGreeksSystemTestBase {

    /** The contract's AC1–3 response for account A, verbatim. */
    static final String AC1_3_JSON = """
            {
              "asOfDate": "2026-11-23",
              "groups": [
                {
                  "symbol": "IXQZ", "optionDelta": "-18.35", "stockDelta": "100.00", "netDelta": "81.65",
                  "gamma": "-3.13", "theta": "2.47", "vega": "-5.03",
                  "underlyingPrice": "61.10", "underlyingPriceManual": false, "underlyingPriceLabel": null,
                  "dollarExposure": "4988.82", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
                  "optionRowCount": 1, "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0,
                  "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                  "excludedLabel": null, "missingLabel": null
                },
                {
                  "symbol": "QRTV", "optionDelta": "87.21", "stockDelta": "0.00", "netDelta": "87.21",
                  "gamma": "4.48", "theta": "-3.24", "vega": "5.89",
                  "underlyingPrice": "52.40", "underlyingPriceManual": false, "underlyingPriceLabel": null,
                  "dollarExposure": "4569.80", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
                  "optionRowCount": 3, "includedCount": 2, "noQuoteCount": 1, "expiredPendingCount": 0,
                  "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                  "excludedLabel": "1 个持仓无报价，未计入", "missingLabel": null
                }
              ],
              "portfolio": {
                "dollarExposure": "9558.62", "theta": "-0.77", "vega": "0.86",
                "noQuoteCount": 1, "expiredPendingCount": 0, "noUnderlyingPriceCount": 0,
                "deltaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                "excludedLabel": "1 个持仓无报价，未计入", "noUnderlyingPriceLabel": null, "missingLabel": null
              }
            }
            """;

    static final String AC4_JSON = """
            {
              "asOfDate": "2026-11-23",
              "groups": [
                { "symbol": "QRTV", "optionDelta": "90.63", "stockDelta": "100.00", "netDelta": "190.63",
                  "gamma": "-15.36", "theta": "13.68", "vega": "-21.33",
                  "underlyingPrice": "52.40", "underlyingPriceManual": false, "underlyingPriceLabel": null,
                  "dollarExposure": "9989.01", "exposureStatus": "OK", "exposureLabel": null, "manualPriceNote": null,
                  "optionRowCount": 1, "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0,
                  "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                  "excludedLabel": null, "missingLabel": null }
              ],
              "portfolio": { "dollarExposure": "9989.01", "theta": "13.68", "vega": "-21.33",
                "noQuoteCount": 0, "expiredPendingCount": 0, "noUnderlyingPriceCount": 0,
                "deltaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                "excludedLabel": null, "noUnderlyingPriceLabel": null, "missingLabel": null }
            }
            """;

    static final String AC8_JSON = """
            {
              "asOfDate": "2026-11-23",
              "groups": [
                { "symbol": "IXQZ", "optionDelta": null, "stockDelta": "100.00", "netDelta": "100.00",
                  "gamma": null, "theta": null, "vega": null,
                  "underlyingPrice": null, "underlyingPriceManual": false, "underlyingPriceLabel": "无标的价",
                  "dollarExposure": null, "exposureStatus": "NO_UNDERLYING_PRICE", "exposureLabel": "无标的价",
                  "manualPriceNote": null,
                  "optionRowCount": 0, "includedCount": 0, "noQuoteCount": 0, "expiredPendingCount": 0,
                  "deltaMissingCount": 0, "gammaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                  "excludedLabel": null, "missingLabel": null }
              ],
              "portfolio": { "dollarExposure": "0.00", "theta": "0.00", "vega": "0.00",
                "noQuoteCount": 0, "expiredPendingCount": 0, "noUnderlyingPriceCount": 1,
                "deltaMissingCount": 0, "thetaMissingCount": 0, "vegaMissingCount": 0,
                "excludedLabel": null, "noUnderlyingPriceLabel": "1 个标的无标的价，美元敞口未计入", "missingLabel": null }
            }
            """;

    static final String QRTV_A = okRow("87.21", "0.00", "87.21", "52.40", "4569.80", "4.48", "-3.24", "5.89",
            "1 个持仓无报价，未计入", "null");
    static final String IXQZ_A = okRow("-18.35", "100.00", "81.65", "61.10", "4988.82", "-3.13", "2.47", "-5.03",
            "null", "null");
    static final String QRTV_B = okRow("90.63", "100.00", "190.63", "52.40", "9989.01", "-15.36", "13.68", "-21.33",
            "null", "null");

    @Test
    @DisplayName("AC1 账户 A 的 QRTV：期权 Delta 87.21、股票 0.00、净 87.21、标的价 52.40、敞口 4569.80（4569.804）、Gamma 4.48、Theta -3.24、Vega 5.89；1 个持仓无报价，未计入")
    void ac1QrtvGroup() {
        scenarioPulled019();
        JsonNode s = greeksSummary("1");
        JsonNode q = group(s, "QRTV");
        assertThat(row(q)).isEqualTo(QRTV_A);
        assertThat(counts(q)).as("P1, P2 OK; P4 无报价").isEqualTo("3 2/1/0 missing 0/0/0/0");
        // Q181: equals the sum of the rows' position values shown in the positions table
        assertThat(d(q.get("optionDelta").asText())).isEqualByComparingTo(
                d(greeks("1", C_P1).get("deltaPosition").asText()).add(d(greeks("1", C_P2).get("deltaPosition").asText())));
    }

    @Test
    @DisplayName("AC2 账户 A 的 IXQZ（covered call 与股票相抵）：-18.35 / 100.00 / 81.65、61.10、敞口 4988.82（4988.815 HALF_UP）、-3.13 / 2.47 / -5.03，无提示")
    void ac2IxqzCoveredCall() {
        scenarioPulled019();
        JsonNode s = greeksSummary("1");
        JsonNode x = group(s, "IXQZ");
        assertThat(row(x)).isEqualTo(IXQZ_A);
        assertThat(counts(x)).isEqualTo("1 1/0/0 missing 0/0/0/0");
    }

    @Test
    @DisplayName("AC3 账户 A 全组合：敞口 9558.62、Theta -0.77、Vega 0.86（无跨标的 Delta / Gamma 字段）；1 个持仓无报价，未计入；整个响应与契约示例一致")
    void ac3PortfolioA() {
        scenarioPulled019();
        JsonNode s = greeksSummary("1");
        assertThat(pf(s)).isEqualTo("9558.62 -0.77 0.86 | 1 个持仓无报价，未计入 | null | null");
        assertThat(pfCounts(s)).isEqualTo("1/0/0 missing 0/0/0");
        assertThat(fieldNames(s.get("portfolio")))
                .as("Q177 no cross-symbol Delta shares / Gamma total")
                .noneMatch(f -> f.equals("netDelta") || f.equals("optionDelta") || f.equals("gamma")
                        || f.equals("stockDelta"));
        assertThat(symbols(s)).containsExactly("IXQZ", "QRTV");
        assertThat(s).as("whole response = contract example (AC1–3)").isEqualTo(parse(AC1_3_JSON));
    }

    @Test
    @DisplayName("AC4 账户 B 只有 QRTV：90.63 / 100.00 / 190.63、敞口 9989.01、-15.36 / 13.68 / -21.33；全组合 9989.01 / 13.68 / -21.33 无提示；与账户 A 各算各的")
    void ac4AccountB() {
        scenarioPulled019();
        JsonNode s = greeksSummary(accountB);
        assertThat(symbols(s)).containsExactly("QRTV");
        assertThat(row(group(s, "QRTV"))).isEqualTo(QRTV_B);
        assertThat(pf(s)).isEqualTo("9989.01 13.68 -21.33 | null | null | null");
        assertThat(s).as("whole response = contract example (AC4)").isEqualTo(parse(AC4_JSON));
        // account A unaffected by B's shares / P5
        assertThat(row(group(greeksSummary("1"), "QRTV"))).isEqualTo(QRTV_A);
    }

    @Test
    @DisplayName("AC5 10:05 QRTV 手动 52.00 → A 4534.92（按手动标的价）全组合 9523.74；B 9912.76；Greeks 不变；10:15 拉取失败仍不变；清除后恢复 AC1–4")
    void ac5ManualUnderlyingPrice() {
        scenarioPulled019();
        String beforeA = all(greeksSummary("1"));
        String beforeB = all(greeksSummary(accountB));

        setNow("2026-11-23T10:05");
        overrideUnderlying("QRTV", "52.00");
        JsonNode a = greeksSummary("1");
        String qA = manualRow("87.21", "0.00", "87.21", "52.00", "4534.92", "4.48", "-3.24", "5.89",
                "1 个持仓无报价，未计入", "null");
        assertThat(row(group(a, "QRTV"))).isEqualTo(qA);
        assertThat(row(group(a, "IXQZ"))).as("IXQZ untouched").isEqualTo(IXQZ_A);
        assertThat(pf(a)).isEqualTo("9523.74 -0.77 0.86 | 1 个持仓无报价，未计入 | null | null");
        JsonNode b = greeksSummary(accountB);
        String qB = manualRow("90.63", "100.00", "190.63", "52.00", "9912.76", "-15.36", "13.68", "-21.33", "null",
                "null");
        assertThat(row(group(b, "QRTV"))).isEqualTo(qB);
        assertThat(pf(b)).isEqualTo("9912.76 13.68 -21.33 | null | null | null");

        // 10:15 QRTV fails → the manual price is kept (and the values with it)
        sources().cboe("QRTV", httpError(500)).cboe("IXQZ", ixqz018("2026-11-23 10:15:00")).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");
        assertThat(optRow("1", C_P1).get("underlyingQuote").get("fetchFailed").asBoolean()).isTrue();
        a = greeksSummary("1");
        assertThat(row(group(a, "QRTV"))).isEqualTo(qA);
        assertThat(pf(a)).startsWith("9523.74 -0.77 0.86 |");
        assertThat(row(group(greeksSummary(accountB), "QRTV"))).isEqualTo(qB);

        // clear → back to the automatic 52.40 (last success kept), note gone
        assertThat(deleteUnderlyingOverride("QRTV").status()).isEqualTo(204);
        assertThat(all(greeksSummary("1"))).isEqualTo(beforeA);
        assertThat(all(greeksSummary(accountB))).isEqualTo(beforeB);
    }

    @Test
    @DisplayName("AC6 10:15 P2 的 gamma、vega 为 null → QRTV Gamma -5.12、Vega -7.11，另注 Gamma、Vega：1 个持仓缺值，未计入；Delta / Theta / 敞口同 AC1；全组合 Vega -12.14、Theta -0.77，下方另有 Vega：1 个持仓缺值，未计入")
    void ac6MissingFieldsOfOneRow() {
        scenarioPulled019();
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00", p1Greeks(),
                        greekOption(OCC_C_P2, "1.80", "1.90", "0.3320", "0.2850", null, "-0.0390", null)))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");
        assertThat(greeks("1", C_P2).get("gammaPosition").isNull()).isTrue();

        JsonNode s = greeksSummary("1");
        JsonNode q = group(s, "QRTV");
        assertThat(row(q)).isEqualTo(okRow("87.21", "0.00", "87.21", "52.40", "4569.80", "-5.12", "-3.24", "-7.11",
                "1 个持仓无报价，未计入", "Gamma、Vega：1 个持仓缺值，未计入"));
        assertThat(counts(q)).as("P2 still included (Delta / Theta)").isEqualTo("3 2/1/0 missing 0/1/0/1");
        assertThat(row(group(s, "IXQZ"))).isEqualTo(IXQZ_A);
        assertThat(pf(s)).isEqualTo(
                "9558.62 -0.77 -12.14 | 1 个持仓无报价，未计入 | null | Vega：1 个持仓缺值，未计入");
        assertThat(pfCounts(s)).isEqualTo("1/0/0 missing 0/0/1");
    }

    @Test
    @DisplayName("AC7 另有 P6 已过期未处理、P7 今天到期（+45.00 / -12.00 / +8.00 / -1.50）→ QRTV 132.21、敞口 6927.80、-7.52 / 4.76 / 4.39；1 个持仓无报价、1 个持仓已到期待处理，未计入；全组合 11916.62 / 7.23 / -0.64")
    void ac7ExpiredPendingAndExpirationDay() {
        // P6 / P7 opened 11-17, P6 quoted on 11-20 (so the DB has its last Greeks)
        setNow("2026-11-17T09:50");
        accountId = "1";
        deposit("15000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        openN("STO", 1, "QRTV", "2026-11-23", "52", "PUT", "150.00", "2026-11-17T09:45");
        sources().cboe("QRTV", qrtv018("2026-11-20 09:45:00",
                greekOption(OCC_C_EXPIRED, "0.04", "0.06", "0.4100", "-0.0100", "0.0040", "-0.0080", "0.0010"),
                greekOption(OCC_C_P7, "0.60", "0.70", "0.3000", "-0.2000", "0.1000", "-0.0500", "0.0200"))).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        scenarioPulled019(greekOption(OCC_C_P7, "0.55", "0.65", "0.3800", "-0.4500", "0.1200", "-0.0800", "0.0150"));

        assertThat(greeks("1", C_EXPIRED).get("status").asText()).isEqualTo("EXPIRED_PENDING");
        JsonNode g7 = greeks("1", C_P7);
        assertThat(g7.get("status").asText()).as("Q171 expiration day is normal").isEqualTo("OK");
        assertThat(text(g7.get("deltaPosition")) + " " + text(g7.get("gammaPosition")) + " "
                + text(g7.get("thetaPosition")) + " " + text(g7.get("vegaPosition")))
                .isEqualTo("45.00 -12.00 8.00 -1.50");

        JsonNode s = greeksSummary("1");
        JsonNode q = group(s, "QRTV");
        assertThat(row(q)).isEqualTo(okRow("132.21", "0.00", "132.21", "52.40", "6927.80", "-7.52", "4.76", "4.39",
                "1 个持仓无报价、1 个持仓已到期待处理，未计入", "null"));
        assertThat(counts(q)).isEqualTo("5 3/1/1 missing 0/0/0/0");
        assertThat(pf(s)).isEqualTo(
                "11916.62 7.23 -0.64 | 1 个持仓无报价、1 个持仓已到期待处理，未计入 | null | null");
        assertThat(pfCounts(s)).isEqualTo("1/1/0 missing 0/0/0");
    }

    @Test
    @DisplayName("AC8 行情库为空，账户 C 只持 IXQZ 100 股 → 期权 —、股票 / 净 Delta 100.00、无标的价；全组合 0.00 × 3 + 1 个标的无标的价，美元敞口未计入；手动 60.00 → 6000.00（按手动标的价），提示消失")
    void ac8NoUnderlyingPrice() {
        setNow("2026-11-23T10:00");
        String c = createAccount("账户C");
        accountId = c;
        transferInShares("IXQZ", 100, "6000.00", "2026-11-19");
        accountId = "1";
        assertThat(stockQuote(c, "IXQZ").get("price").isNull()).as("never fetched").isTrue();

        JsonNode s = greeksSummary(c);
        assertThat(s).as("whole response = contract example (AC8)").isEqualTo(parse(AC8_JSON));
        assertThat(pf(s)).isEqualTo("0.00 0.00 0.00 | null | 1 个标的无标的价，美元敞口未计入 | null");

        overrideUnderlying("IXQZ", "60.00");
        s = greeksSummary(c);
        assertThat(row(group(s, "IXQZ"))).isEqualTo(
                "null 100.00 100.00 | 60.00 null true | 6000.00 OK null " + MANUAL_PRICE + " | null null null | null | null");
        assertThat(pf(s)).isEqualTo("6000.00 0.00 0.00 | null | null | null");
        assertThat(pfCounts(s)).isEqualTo("0/0/0 missing 0/0/0");
        // account A (empty) has no groups
        assertThat(greeksSummary("1").get("groups")).isEmpty();
    }

    @Test
    @DisplayName("AC9 STC 2 张 P2 → QRTV 30.21、敞口 1583.00、-5.12 / 4.56 / -7.11；全组合 6571.82 / 7.03 / -12.14；再 BTC P3 并卖出全部 IXQZ → IXQZ 组消失")
    void ac9TradesChangeTheSummary() {
        scenarioPulled019();
        setNow("2026-11-23T10:05");
        closeN("STC", 2, "QRTV", "2026-12-18", "55", "CALL", "368.70", "2026-11-23T10:03");

        JsonNode s = greeksSummary("1");
        JsonNode q = group(s, "QRTV");
        assertThat(row(q)).isEqualTo(okRow("30.21", "0.00", "30.21", "52.40", "1583.00", "-5.12", "4.56", "-7.11",
                "1 个持仓无报价，未计入", "null"));
        assertThat(counts(q)).isEqualTo("2 1/1/0 missing 0/0/0/0");
        assertThat(pf(s)).isEqualTo("6571.82 7.03 -12.14 | 1 个持仓无报价，未计入 | null | null");

        closeN("BTC", 1, "IXQZ", "2026-12-18", "65", "CALL", "85.00", "2026-11-23T10:04");
        s = greeksSummary("1");
        assertThat(row(group(s, "IXQZ"))).as("only shares left").isEqualTo(
                okRow("null", "100.00", "100.00", "61.10", "6110.00", "null", "null", "null", "null", "null"));
        sellShares("IXQZ", 100, "6110.00", "2026-11-23T10:04");
        s = greeksSummary("1");
        assertThat(symbols(s)).containsExactly("QRTV");
        assertThat(pf(s)).isEqualTo("1583.00 4.56 -7.11 | 1 个持仓无报价，未计入 | null | null");
        // account B untouched
        assertThat(row(group(greeksSummary(accountB), "QRTV"))).isEqualTo(QRTV_B);
    }

    @Test
    @DisplayName("AC10（API 侧）接口无到期过滤：带 expiry=today 等查询参数仍返回全账户（同 AC1–3）；GET /api/option-positions 本身也不带过滤")
    void ac10NotAffectedByExpiryFilter() {
        scenarioPulled019();
        JsonNode plain = greeksSummary("1");
        assertThat(plain).isEqualTo(parse(AC1_3_JSON));
        for (String q : new String[] {"?expiry=today", "?expiry=expired", "?expiry=7"}) {
            Resp r = get(GREEKS_SUMMARY + q);
            assertThat(r.status()).as("GET %s%s", GREEKS_SUMMARY, q).isEqualTo(200);
            assertThat(r.body()).as("filter param ignored: %s", q).isEqualTo(plain);
        }
    }
}
