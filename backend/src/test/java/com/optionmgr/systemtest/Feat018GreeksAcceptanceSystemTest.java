package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-018 单合约 Greeks: the 10 acceptance criteria, end-to-end over HTTP (AC10 is the page
 * toggle / help texts — covered by e2e/tests/feat-018-greeks.spec.ts; here only its API side: the five values exist
 * per row and nothing else, no Rho). See {@link Feat018GreeksSystemTestBase} for the scenario and the fakes.
 */
@DisplayName("FEAT-018 单合约 Greeks（验收）")
class Feat018GreeksAcceptanceSystemTest extends Feat018GreeksSystemTestBase {

    @Test
    @DisplayName("AC1 P1 STO 1 QRTV 50P：IV 35.12；Delta 30.21（-0.3021）；Gamma -5.12（0.0512）；Theta 4.56（-0.0456）；Vega -7.11（0.0711）；无 Rho")
    void ac1P1ShortPut() {
        scenarioPulled018();

        JsonNode row = optRow("1", C_P1);
        assertThat(row.get("direction").asText()).isEqualTo("SHORT");
        assertThat(row.get("contracts").asInt()).isEqualTo(1);
        JsonNode g = greeksOf(row);
        assertThat(shownGreeks(g)).isEqualTo(P1_G);
        assertThat(text(g.get("quoteTime"))).isEqualTo("2026-11-23T14:45:00Z");
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T09:45:00");
        // no Rho anywhere on the row (Q167), the FEAT-016 quote objects keep their exact field sets
        assertThat(fieldNames(g)).noneMatch(f -> f.toLowerCase().contains("rho"));
        assertThat(fieldNames(row)).noneMatch(f -> f.toLowerCase().contains("rho"));
        assertThat(fieldNames(row.get("optionQuote"))).containsExactlyInAnyOrderElementsOf(OPTION_QUOTE_FIELDS);
        // FEAT-017 values untouched by the new field
        assertThat(val(row)).isEqualTo(okVal("-125.00", "-6.30", "-5.31"));

        assertAllRowsShape("1");
        assertGreeksMatchStored("1");
    }

    @Test
    @DisplayName("AC2 P2 BTO 2 QRTV 55C：IV 33.20；Delta 57.00（0.2850）；Gamma 9.60；Theta -7.80（-0.0390）；Vega 13.00")
    void ac2P2LongCall() {
        scenarioPulled018();
        JsonNode row = optRow("1", C_P2);
        assertThat(row.get("direction").asText()).isEqualTo("LONG");
        assertThat(row.get("contracts").asInt()).isEqualTo(2);
        assertThat(shownGreeks(greeksOf(row))).isEqualTo(P2_G);
    }

    @Test
    @DisplayName("AC3 P3 STO 1 IXQZ 65C：每张 HALF_UP 0.1835 / 0.0313，持仓用未取整原值 -18.345 → -18.35、-3.125 → -3.13；Theta 2.47；Vega -5.03")
    void ac3P3RoundingFromUnroundedRaw() {
        scenarioPulled018();
        JsonNode g = greeks("1", C_P3);
        assertThat(shownGreeks(g)).isEqualTo(P3_G);
        // the raw values really are the 5-decimal ones (so the test exercises "round per-contract and position
        // separately from the unrounded raw value")
        JsonNode raw = storedContract("IXQZ", "2026-12-18", "65", "CALL");
        assertThat(d(raw.get("delta").asText())).isEqualByComparingTo("0.18345");
        assertThat(d(raw.get("gamma").asText())).isEqualByComparingTo("0.03125");
    }

    @Test
    @DisplayName("AC4 P4（链中从无该合约）五项全部 无报价；FEAT-017 合计与本需求前相同（160.00 / 61.75 …），合计接口没有 Greeks；股票行没有 greeks")
    void ac4NeverQuotedAndNoTotals() {
        scenarioPulled018();
        JsonNode row = optRow("1", C_P4);
        assertThat(row.get("optionQuote").get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        JsonNode g = greeksOf(row);
        assertThat(shownGreeks(g)).isEqualTo(NO_QUOTE_G);
        assertThat(g.get("quoteTime").isNull()).isTrue();
        assertThat(g.get("quoteTimeLocal").isNull()).isTrue();

        // FEAT-017 summary exactly as before this requirement (same scenario numbers as FEAT-017 AC3)
        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("160.00 61.75 3/1/0");
        assertThat(grp(s.get("stock"))).isEqualTo("6110.00 230.00 1/0/0");
        assertThat(grp(s.get("total"))).isEqualTo("6270.00 291.75 4/1/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价，未计入");
        assertSummaryMatchesRows("1");

        // no Greeks totals anywhere (Q165): the positions body top level and the summary have no greeks fields
        JsonNode body = optionPositions("1");
        assertThat(fieldNames(body)).noneMatch(f -> f.toLowerCase().contains("greek") || f.toLowerCase().contains("delta"));
        assertThat(s.toString().toLowerCase()).doesNotContain("greek").doesNotContain("delta");
        assertAllRowsShape("1");
    }

    @Test
    @DisplayName("AC5 账户 B P5 STO 3 QRTV 50P：35.12；90.63；-15.36；13.68；-21.33（每张同 P1）；账户 A 的 P1 不受影响")
    void ac5AccountBSharesTheQuote() {
        scenarioPulled018();
        JsonNode row = optRow(accountB, C_P5);
        assertThat(row.get("contracts").asInt()).isEqualTo(3);
        assertThat(shownGreeks(greeksOf(row))).isEqualTo(P5_G);
        assertThat(optionPositions(accountB).get("positions")).hasSize(1);
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(P1_G);
        assertGreeksMatchStored(accountB);
    }

    @Test
    @DisplayName("AC6 11:00（Q270 整点）成功拉到新值 → 36.00 / 33.00 / -5.30 / 4.70 / -7.20；12:00 起 QRTV 失败（行情未更新（上次：11-23 11:00））→ 仍为 11:00 的值")
    void ac6NewValuesThenFetchFailureKeepsThem() {
        scenarioPulled018();
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00",
                        greekOption(OCC_C_P1, "1.25", "1.35", "0.3600", "-0.3300", "0.0530", "-0.0470", "0.0720"),
                        p2Greeks()))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");

        String p1New = okG("36.00", "33.00", "-0.3300", "-5.30", "0.0530", "4.70", "-0.0470", "-7.20", "0.0720");
        JsonNode g = greeks("1", C_P1);
        assertThat(shownGreeks(g)).isEqualTo(p1New);
        assertThat(text(g.get("quoteTime"))).isEqualTo("2026-11-23T15:00:00Z");
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T10:00:00");

        sources().cboe("QRTV", httpError(500)).cboe("IXQZ", ixqz018("2026-11-23 11:00:00")).apply();
        tickAt("2026-11-23T12:00", "SCHEDULED");

        JsonNode row = optRow("1", C_P1);
        assertThat(row.get("optionQuote").get("fetchFailed").asBoolean()).isTrue();
        assertThat(text(row.get("optionQuote").get("staleLabel"))).isEqualTo("行情未更新（上次：11-23 11:00）");
        g = greeksOf(row);
        assertThat(shownGreeks(g)).as("Q141 kept after the failure").isEqualTo(p1New);
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T10:00:00");
        // account B follows the same shared quote
        assertThat(shownGreeks(greeks(accountB, C_P5)))
                .isEqualTo(okG("36.00", "99.00", "-0.3300", "-15.90", "0.0530", "14.10", "-0.0470", "-21.60", "0.0720"));
    }

    @Test
    @DisplayName("AC7 P1 手动 Mark 1.40 → Greeks 不变并标注 Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整；清除后标注消失；P4 手动 0.30 仍 无报价")
    void ac7ManualMark() {
        scenarioPulled018();
        setNow("2026-11-23T10:05");
        overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");

        JsonNode row = optRow("1", C_P1);
        assertThat(row.get("optionQuote").get("manual").asBoolean()).isTrue();
        assertThat(row.get("optionQuote").get("mark").asText()).isEqualTo("1.40");
        String note = manualNote("11-23 09:45");
        assertThat(note).isEqualTo("Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整");
        assertThat(shownGreeks(greeksOf(row))).isEqualTo(
                okGNote("35.12", "30.21", "-0.3021", "-5.12", "0.0512", "4.56", "-0.0456", "-7.11", "0.0711", note));
        // the same contract in account B shows the same note (manual mark is per contract)
        assertThat(text(greeks(accountB, C_P5).get("manualMarkNote"))).isEqualTo(note);

        Resp del = deleteMarkOverride("?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT");
        assertThat(del.status()).isEqualTo(204);
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(P1_G);

        overrideMark("QRTV", "2026-12-18", "47.5", "PUT", "0.30");
        JsonNode p4 = optRow("1", C_P4);
        assertThat(p4.get("optionQuote").get("manual").asBoolean()).isTrue();
        assertThat(p4.get("unrealizedStatus").asText()).as("FEAT-017 computes with the manual mark").isEqualTo("OK");
        assertThat(shownGreeks(greeksOf(p4))).as("Q170 never quoted → 无报价 even with a manual mark")
                .isEqualTo(NO_QUOTE_G);
    }

    @Test
    @DisplayName("AC8 11:00 P2 的 gamma、vega 为 null → Gamma、Vega 每张值和持仓值 null（页面 —），IV / Delta / Theta 正常")
    void ac8SingleFieldMissing() {
        scenarioPulled018();
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00", p1Greeks(),
                        greekOption(OCC_C_P2, "1.80", "1.90", "0.3320", "0.2850", null, "-0.0390", null)))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");

        JsonNode raw = storedContract("QRTV", "2026-12-18", "55", "CALL");
        assertThat(raw.get("gamma").isNull()).as("success replaces the whole quote incl. null").isTrue();
        assertThat(raw.get("vega").isNull()).isTrue();

        JsonNode g = greeks("1", C_P2);
        assertThat(shownGreeks(g)).isEqualTo(okG("33.20", "57.00", "0.2850", "null", "null", "-7.80", "-0.0390",
                "null", "null"));
        assertThat(text(g.get("quoteTimeLocal"))).isEqualTo("2026-11-23T10:00:00");
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(P1_G);
        assertGreeksMatchStored("1");
    }

    /**
     * Account A also holds P6 STO 1 QRTV 2026-11-20 45P (quoted 11-20 with delta −0.0100) and P7 STO 1 QRTV 2026-11-23
     * 52P (in the 11-23 chain with delta −0.4500).
     */
    void scenarioWithExpiredAndToday() {
        setNow("2026-11-17T09:50");
        accountId = "1";
        deposit("15000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        openN("STO", 1, "QRTV", "2026-11-23", "52", "PUT", "150.00", "2026-11-17T09:45");
        sources().cboe("QRTV", qrtv018("2026-11-20 09:45:00",
                greekOption(OCC_C_EXPIRED, "0.04", "0.06", "0.4100", "-0.0100", "0.0040", "-0.0080", "0.0010"),
                greekOption(OCC_C_P7, "0.60", "0.70", "0.3000", "-0.2000", "0.1000", "-0.0500", "0.0200"))).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        scenario018();
        sources018(greekOption(OCC_C_P7, "0.55", "0.65", "0.3800", "-0.4500", "0.2100", "-0.1500", "0.0100"));
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    @Test
    @DisplayName("AC9 P6 已过期未处理（库里 delta -0.0100）→ 已到期，待处理；P7 今天到期照常：Delta 45.00（-0.4500）")
    void ac9ExpiredPendingAndExpirationDay() {
        scenarioWithExpiredAndToday();

        JsonNode p6 = optRow("1", C_EXPIRED);
        assertThat(p6.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        JsonNode raw = storedContract("QRTV", "2026-11-20", "45", "PUT");
        assertThat(d(raw.get("delta").asText())).as("last Greeks kept in the DB").isEqualByComparingTo("-0.0100");
        JsonNode g6 = greeksOf(p6);
        assertThat(shownGreeks(g6)).isEqualTo(EXPIRED_G);
        assertThat(text(g6.get("quoteTimeLocal"))).as("EXPIRED_PENDING still gives the last quote time")
                .isEqualTo("2026-11-20T09:45:00");

        JsonNode p7 = optRow("1", C_P7);
        assertThat(p7.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        JsonNode g7 = greeksOf(p7);
        assertThat(g7.get("status").asText()).isEqualTo("OK");
        assertThat(text(g7.get("deltaPosition"))).isEqualTo("45.00");
        assertThat(text(g7.get("deltaPerContract"))).isEqualTo("-0.4500");
        assertThat(shownGreeks(g7)).isEqualTo(okG("38.00", "45.00", "-0.4500", "-21.00", "0.2100", "15.00", "-0.1500",
                "-1.00", "0.0100"));

        // the scenario rows are unaffected
        assertThat(shownGreeks(greeks("1", C_P1))).isEqualTo(P1_G);
        assertThat(shownGreeks(greeks("1", C_P4))).isEqualTo(NO_QUOTE_G);
        assertGreeksMatchStored("1");
    }

    @Test
    @DisplayName("AC10（API 侧）每个期权行都有 greeks（永不为 null，字段集合与契约一致）；账户 A 四行的状态 OK / OK / OK / NO_QUOTE")
    void ac10EveryRowHasGreeks() {
        scenarioPulled018();
        JsonNode body = optionPositions("1");
        assertThat(body.get("positions")).hasSize(4);
        List<String> statuses = new java.util.ArrayList<>();
        for (JsonNode row : body.get("positions")) {
            statuses.add(row.get("contractLabel").asText() + " " + greeksOf(row).get("status").asText());
        }
        assertThat(statuses).containsExactlyInAnyOrder(C_P1 + " OK", C_P2 + " OK", C_P3 + " OK",
                C_P4 + " NO_QUOTE");
    }
}
