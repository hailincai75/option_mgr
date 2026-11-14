package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-017 未实现盈亏与市值: the 10 acceptance criteria, end-to-end over HTTP.
 * See {@link Feat017UnrealizedSystemTestBase} for the scenario and the fakes.
 */
@DisplayName("FEAT-017 未实现盈亏与市值（验收）")
class Feat017UnrealizedAcceptanceSystemTest extends Feat017UnrealizedSystemTestBase {

    @Test
    @DisplayName("AC1 账户 A：P1 −125.00 / −6.30 / −5.31；P2 370.00 / 68.70 / 22.80；P3 −85.00 / −0.65 / −0.77（行内 mark 与 optionQuote 一致）")
    void ac1OptionRows() {
        scenarioPulled();

        JsonNode p1 = optRow("1", C_P1);
        assertThat(p1.get("optionQuote").get("mark").asText()).isEqualTo("1.25");
        assertThat(p1.get("openingNetTotal").asText()).isEqualTo("118.70");
        assertThat(val(p1)).isEqualTo(okVal("-125.00", "-6.30", "-5.31"));

        JsonNode p2 = optRow("1", C_P2);
        assertThat(p2.get("direction").asText()).isEqualTo("LONG");
        assertThat(p2.get("contracts").asInt()).isEqualTo(2);
        assertThat(p2.get("openingNetTotal").asText()).isEqualTo("-301.30");
        assertThat(p2.get("optionQuote").get("mark").asText()).isEqualTo("1.85");
        assertThat(val(p2)).isEqualTo(okVal("370.00", "68.70", "22.80"));

        JsonNode p3 = optRow("1", C_P3);
        assertThat(p3.get("optionQuote").get("mark").asText()).isEqualTo("0.85");
        assertThat(val(p3)).isEqualTo(okVal("-85.00", "-0.65", "-0.77"));

        assertRowInvariants("1");
        // only additions: the FEAT-016 quote objects keep their exact field sets
        assertThat(fieldNames(p1.get("optionQuote"))).containsExactlyInAnyOrderElementsOf(OPTION_QUOTE_FIELDS);
    }

    @Test
    @DisplayName("AC2 IXQZ 股票（被 covered call 覆盖）：市值 6110.00、未实现 230.00、3.91")
    void ac2StockRow() {
        scenarioPulled();
        JsonNode s = stkRow("1", "IXQZ");
        assertThat(s.get("shares").asInt()).isEqualTo(100);
        assertThat(s.get("totalCost").asText()).isEqualTo("5880.00");
        assertThat(s.get("quote").get("price").asText()).isEqualTo("61.10");
        assertThat(val(s)).isEqualTo(okVal("6110.00", "230.00", "3.91"));
        assertThat(fieldNames(s.get("quote"))).containsExactlyInAnyOrderElementsOf(UNDERLYING_QUOTE_FIELDS);
    }

    @Test
    @DisplayName("AC3 P4 无报价；合计 期权 160.00 / 61.75，股票 6110.00 / 230.00，合计 6270.00 / 291.75；excludedLabel 1 个持仓无报价，未计入")
    void ac3NoQuoteAndSummary() {
        scenarioPulled();
        JsonNode p4 = optRow("1", C_P4);
        assertThat(p4.get("optionQuote").get("mark").isNull()).isTrue();
        assertThat(p4.get("optionQuote").get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(val(p4)).isEqualTo(NO_QUOTE_VAL);

        JsonNode s = summary("1");
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(grp(s.get("option"))).isEqualTo("160.00 61.75 3/1/0");
        assertThat(grp(s.get("stock"))).isEqualTo("6110.00 230.00 1/0/0");
        assertThat(grp(s.get("total"))).isEqualTo("6270.00 291.75 4/1/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价，未计入");
        assertThat(s.get("asOfDate").asText()).isEqualTo(optionPositions("1").get("asOfDate").asText());
        assertSummaryMatchesRows("1");
    }

    @Test
    @DisplayName("AC4 账户 B：P5 −125.00 / −5.00 / −4.17；合计 期权 −125.00 / −5.00，股票 0.00 / 0.00，合计 −125.00 / −5.00，无未计入；A 不受影响")
    void ac4AccountB() {
        scenarioPulled();
        JsonNode p5 = optRow(accountB, C_P5);
        assertThat(p5.get("openingNetTotal").asText()).isEqualTo("120.00");
        assertThat(val(p5)).isEqualTo(okVal("-125.00", "-5.00", "-4.17"));
        assertThat(stockPositions(accountB).get("positions")).isEmpty();

        JsonNode s = summary(accountB);
        assertThat(grp(s.get("option"))).isEqualTo("-125.00 -5.00 1/0/0");
        assertThat(grp(s.get("stock"))).isEqualTo("0.00 0.00 0/0/0");
        assertThat(grp(s.get("total"))).isEqualTo("-125.00 -5.00 1/0/0");
        assertThat(s.get("excludedLabel").isNull()).isTrue();
        assertSummaryMatchesRows(accountB);

        // account A's summary is not mixed with B (Q157)
        assertThat(grp(summary("1").get("total"))).isEqualTo("6270.00 291.75 4/1/0");
    }

    @Test
    @DisplayName("AC5 手动 P1 Mark 1.40 → A 的 P1 −140.00 / −21.30，期权合计未实现 46.75；B 的 P5 −20.00；P4 手动 0.30 → −30.00 / 15.00，未计入消失")
    void ac5ManualMarks() {
        scenarioPulled();
        setNow("2026-11-23T10:05");
        overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");

        assertThat(val(optRow("1", C_P1))).isEqualTo(okVal("-140.00", "-21.30", "-17.94"));
        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("145.00 46.75 3/1/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价，未计入");
        JsonNode p5 = optRow(accountB, C_P5);
        assertThat(p5.get("unrealizedPnl").asText()).isEqualTo("-20.00");
        assertThat(p5.get("marketValue").asText()).isEqualTo("-140.00");
        assertThat(grp(summary(accountB).get("option"))).isEqualTo("-140.00 -20.00 1/0/0");

        overrideMark("QRTV", "2026-12-18", "47.5", "PUT", "0.30");
        JsonNode p4 = optRow("1", C_P4);
        assertThat(p4.get("optionQuote").get("manual").asBoolean()).isTrue();
        assertThat(val(p4)).isEqualTo(okVal("-30.00", "15.00", "33.33"));
        s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("115.00 61.75 4/0/0");
        assertThat(grp(s.get("total"))).isEqualTo("6225.00 291.75 5/0/0");
        assertThat(s.get("excludedLabel").isNull()).as("未计入 提示消失").isTrue();
        assertSummaryMatchesRows("1");
    }

    @Test
    @DisplayName("AC6 P6 mark 0.125 → 12.50 / −3.15 / −20.13，合计 期权 172.50 / 58.60、总 6282.50 / 288.60；10:15 bid 0 theo 0.03 → 3.00 / −12.65 / −80.83，合计 163.00 / 49.10、6273.00 / 279.10")
    void ac6MidWithThreeDecimalsThenTheo() {
        scenario017();
        openN("BTO", 1, "QRTV", "2026-12-18", "60", "CALL", "15.65", "2026-11-20T10:25");
        sources017(option(OCC_C_P6, "bid", "0.10", "ask", "0.15", "theo", "0.12"));
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode p6 = optRow("1", C_P6);
        assertThat(p6.get("optionQuote").get("mark").asText()).isEqualTo("0.125");
        assertThat(p6.get("optionQuote").get("markBasis").asText()).isEqualTo("MID");
        assertThat(val(p6)).isEqualTo(okVal("12.50", "-3.15", "-20.13"));
        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("172.50 58.60 4/1/0");
        assertThat(grp(s.get("total"))).isEqualTo("6282.50 288.60 5/1/0");

        sources().cboe("QRTV", qrtv017("2026-11-23 10:00:00", option(OCC_C_P6, "bid", "0", "ask", "0.05", "theo", "0.03")))
                .cboe("IXQZ", ixqz017("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");

        p6 = optRow("1", C_P6);
        assertThat(p6.get("optionQuote").get("mark").asText()).isEqualTo("0.03");
        assertThat(p6.get("optionQuote").get("markBasis").asText()).isEqualTo("THEO");
        assertThat(val(p6)).isEqualTo(okVal("3.00", "-12.65", "-80.83"));
        s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("163.00 49.10 4/1/0");
        assertThat(grp(s.get("total"))).isEqualTo("6273.00 279.10 5/1/0");
        assertSummaryMatchesRows("1");
    }

    @Test
    @DisplayName("AC7 10:15 起 QRTV 拉取失败（行情未更新（上次：11-23 10:00））→ P1、P2 仍按 1.25、1.85 算 −6.30、68.70，计入合计")
    void ac7FetchFailedKeepsLastQuote() {
        scenarioPulled();
        sources().cboe("QRTV", httpError(500)).cboe("IXQZ", ixqz017("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");
        tickAt("2026-11-23T10:30", "SCHEDULED");

        JsonNode p1 = optRow("1", C_P1);
        assertThat(p1.get("optionQuote").get("fetchFailed").asBoolean()).isTrue();
        assertThat(text(p1.get("optionQuote").get("staleLabel"))).isEqualTo("行情未更新（上次：11-23 10:00）");
        assertThat(val(p1)).isEqualTo(okVal("-125.00", "-6.30", "-5.31"));
        JsonNode p2 = optRow("1", C_P2);
        assertThat(p2.get("optionQuote").get("fetchFailed").asBoolean()).isTrue();
        assertThat(val(p2)).isEqualTo(okVal("370.00", "68.70", "22.80"));

        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("160.00 61.75 3/1/0");
        assertThat(grp(s.get("total"))).isEqualTo("6270.00 291.75 4/1/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价，未计入");
    }

    /** Account A: STO 1 QRTV 2026-11-20 45P net 60.00 opened 11-17, quoted 0.04 / 0.06 (mark 0.05) on 11-20 10:00. */
    void expiredPositionWithLastMark() {
        setNow("2026-11-17T09:50");
        accountId = "1";
        deposit("5000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-20 09:45:00",
                List.of(option(OCC_C_EXPIRED, "bid", "0.04", "ask", "0.06"))))).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        assertThat(optionQuote("1", C_EXPIRED).get("mark").asText()).as("last mark stored before expiry")
                .isEqualTo("0.05");
    }

    @Test
    @DisplayName("AC8 已过期未处理（最后 mark 0.05）→ 已到期，待处理，合计不变，提示 1 个持仓无报价、1 个持仓已到期待处理，未计入；作废后行消失，提示恢复")
    void ac8ExpiredPending() {
        expiredPositionWithLastMark();
        scenario017();
        sources017();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode e = optRow("1", C_EXPIRED);
        assertThat(e.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(e.get("optionQuote").get("mark").asText()).as("last mark kept (FEAT-016 Q150)").isEqualTo("0.05");
        assertThat(e.get("optionQuote").get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(val(e)).isEqualTo(EXPIRED_VAL);

        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("160.00 61.75 3/1/1");
        assertThat(grp(s.get("stock"))).isEqualTo("6110.00 230.00 1/0/0");
        assertThat(grp(s.get("total"))).isEqualTo("6270.00 291.75 4/1/1");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");
        assertSummaryMatchesRows("1");
        assertRowInvariants("1");

        setNow("2026-11-23T10:05");
        expire("QRTV", "2026-11-20", "45", "PUT", 1);
        assertThat(hasOptRow("1", C_EXPIRED)).as("row gone after 到期作废").isFalse();
        s = summary("1");
        assertThat(grp(s.get("total"))).isEqualTo("6270.00 291.75 4/1/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价，未计入");
    }

    @Test
    @DisplayName("AC9 STC 2 张 P2 净额 368.70 → P2 消失，期权合计 −210.00 / −6.95，合计 5900.00 / 223.05")
    void ac9CloseChangesSummary() {
        scenarioPulled();
        setNow("2026-11-23T10:05");
        closeN("STC", 2, "QRTV", "2026-12-18", "55", "CALL", "368.70", "2026-11-23T10:03");
        assertThat(hasOptRow("1", C_P2)).isFalse();
        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("-210.00 -6.95 2/1/0");
        assertThat(grp(s.get("total"))).isEqualTo("5900.00 223.05 3/1/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价，未计入");
        assertSummaryMatchesRows("1");
        // other account untouched
        assertThat(grp(summary(accountB).get("total"))).isEqualTo("-125.00 -5.00 1/0/0");
    }

    @Test
    @DisplayName("AC10 没有任何持仓的账户 → 三组 0.00 / 0.00 / 0 / 0 / 0，excludedLabel null")
    void ac10EmptyAccount() {
        scenarioPulled();
        String empty = createAccount("空账户");
        JsonNode s = summary(empty);
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        for (String g : List.of("option", "stock", "total")) {
            assertThat(grp(s.get(g))).as(g).isEqualTo("0.00 0.00 0/0/0");
        }
        assertThat(s.get("excludedLabel").isNull()).isTrue();

        // a fresh database (no market data, no positions at all) gives the same
        reset();
        setNow("2026-11-23T10:00");
        JsonNode fresh = summary("1");
        for (String g : List.of("option", "stock", "total")) {
            assertThat(grp(fresh.get(g))).as(g).isEqualTo("0.00 0.00 0/0/0");
        }
        assertThat(fresh.get("excludedLabel").isNull()).isTrue();
    }
}
