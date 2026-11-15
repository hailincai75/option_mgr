package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-023 按标的盈亏: acceptance criteria 1–8 over HTTP (AC 9 is frontend only). Exact numbers of
 * the requirement / contract examples, null rules, excludedLabel, order, account isolation and consistency with
 * {@code pnl-summary} / {@code unrealized-summary} / the position endpoints. See {@link Feat023UnderlyingPnlSystemTestBase}.
 */
@DisplayName("FEAT-023 按标的盈亏（验收）")
class Feat023UnderlyingPnlAcceptanceSystemTest extends Feat023UnderlyingPnlSystemTestBase {

    @Test
    @DisplayName("AC1 账户 A：依次 IXQZ、QRTV、ZWLK 三组（QFZ 无组）；已实现 / 未实现 / 合计 / 市值 / 净权利金 / 计数 / 提示与契约示例逐字相同")
    void ac1ScenarioGroups() {
        scenario023Pulled();
        JsonNode body = underlying("1");
        assertThat(body.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(groupLines("1")).containsExactly(G_IXQZ, G_QRTV, G_ZWLK);
        // ZWLK: everything not computable → null (not "0.00"); no option rows → netPremium null
        JsonNode zwlk = body.get("groups").get(2);
        assertThat(zwlk.get("marketValue").isNull()).isTrue();
        assertThat(zwlk.get("unrealizedPnl").isNull()).isTrue();
        assertThat(zwlk.get("netPremium").isNull()).isTrue();
        assertThat(zwlk.get("excludedLabel").isTextual()).isTrue();
        // QRTV: no exclusions → excludedLabel JSON null
        assertThat(body.get("groups").get(1).get("excludedLabel").isNull()).isTrue();
        assertConsistent("1");
    }

    @Test
    @DisplayName("AC2 行上市值 / 未实现（FEAT-017 字段）：40P +30.00 / −10.65（−26.20%）、45P −110.00 / +10.35（8.60%）、55C −60.00 / +25.35（29.70%）、QRTV 股票 5,000.00 / 200.00（4.17%）；IXQZ 58P 已到期，待处理；ZWLK 无报价")
    void ac2RowValues() {
        scenario023Pulled();
        assertThat(val(optRow("1", QRTV_40P))).isEqualTo(okVal("30.00", "-10.65", "-26.20"));
        assertThat(val(optRow("1", QRTV_45P))).isEqualTo(okVal("-110.00", "10.35", "8.60"));
        assertThat(val(optRow("1", QRTV_55C))).isEqualTo(okVal("-60.00", "25.35", "29.70"));
        assertThat(val(stkRow("1", "QRTV"))).isEqualTo(okVal("5000.00", "200.00", "4.17"));
        assertThat(val(optRow("1", IXQZ_58P))).isEqualTo(EXPIRED_VAL);
        assertThat(val(optRow("1", IXQZ_60P))).isEqualTo(okVal("-250.00", "-50.00", "-25.00"));
        assertThat(val(stkRow("1", "ZWLK"))).isEqualTo(NO_QUOTE_VAL);
        assertRowInvariants("1");
        // the card figures are built from exactly these rows
        assertConsistent("1");
    }

    @Test
    @DisplayName("AC3 持仓页合计 +4,610.00 / +175.05、1 无报价 + 1 已到期 = 三组之和；盈亏汇总（不带日期）IXQZ / QRTV / ZWLK 与 AC1 已实现相同，另有 QFZ +39.35")
    void ac3ConsistencyWithPositionsAndPnlSummary() {
        scenario023Pulled();
        JsonNode s = summary("1");
        assertThat(grp(s.get("total"))).isEqualTo("4610.00 175.05 5/1/1");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");

        assertThat(pnlBySymbol("1")).containsExactly(
                "IXQZ | 1 80.00 (1 80.00 · 0 0.00)",
                "QFZ | 1 39.35 (1 39.35 · 0 0.00)",
                "QRTV | 1 39.70 (1 39.70 · 0 0.00)",
                "ZWLK | 1 100.00 (0 0.00 · 1 100.00)");
        // QFZ has history only → no group (Q196); no "all symbols" total in the response (Q201)
        assertThat(groupLines("1")).noneMatch(l -> l.startsWith("QFZ "));
        assertThat(fieldNames(underlying("1"))).containsExactlyInAnyOrder("asOfDate", "groups");
        assertConsistent("1");
    }

    @Test
    @DisplayName("AC4 已实现链接的数据：GET /api/closed-trades?symbol=QRTV（不带 type / 日期）只有 H2 一行 +39.70，合计 = QRTV 组已实现")
    void ac4ClosedTradesForSymbol() {
        scenario023Pulled();
        JsonNode c = closedTrades("1", "symbol=QRTV");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("39.70");
        JsonNode row = c.get("items").get(0);
        assertThat(row.get("type").asText()).isEqualTo("OPTION");
        assertThat(row.get("action").asText()).isEqualTo("BTC");
        assertThat(row.get("contractLabel").asText()).isEqualTo("QRTV 2026-11-20 50 C");
        assertThat(row.get("realizedPnl").asText()).isEqualTo("39.70");
        assertThat(c.get("filteredRealizedPnlTotal").asText())
                .isEqualTo(underlying("1").get("groups").get(1).get("realized").get("realizedPnl").asText());
    }

    @Test
    @DisplayName("AC5 10:30 BTC 1 张 QRTV 45P 60.65 → QRTV 已实现 +99.40（期权 2 笔）、未实现 +214.70、合计 +314.10、市值 +4,970.00、净权利金 +44.70、3/0/0；其它组不变")
    void ac5CloseUpdatesQrtv() {
        scenario023Pulled();
        setNow("2026-11-23T10:31");
        closeN("BTC", 1, "QRTV", "2026-12-18", "45", "PUT", "60.65", "2026-11-23T10:30");
        assertThat(hasOptRow("1", QRTV_45P)).as("45P row gone").isFalse();
        assertThat(groupLines("1")).containsExactly(
                G_IXQZ,
                "QRTV | 2 99.40 (2 99.40 · 0 0.00) | mv 4970.00 | upnl 214.70 | total 314.10"
                        + " | prem 44.70 | opt 2 stock true | 3/0/0 | null",
                G_ZWLK);
        assertConsistent("1");
        // account B (same contract, same quote) unaffected
        assertThat(groupLines(accountB)).containsExactly(G_QRTV_B);
    }

    @Test
    @DisplayName("AC6 作废 IXQZ 58P → 已实现 +170.00（2 笔）、未实现 −50.00、合计 +120.00、市值 −250.00、净权利金 +200.00、提示 null；删除作废记录 → 恢复 AC1")
    void ac6ExpireAndDelete() {
        scenario023Pulled();
        long exp = expireId("IXQZ", "2026-11-20", "58", "PUT", 1);
        assertThat(groupLine("1", "IXQZ")).isEqualTo(
                "IXQZ | 2 170.00 (2 170.00 · 0 0.00) | mv -250.00 | upnl -50.00 | total 120.00"
                        + " | prem 200.00 | opt 1 stock false | 1/0/0 | null");
        assertConsistent("1");

        deleteOptionTrade(exp);
        assertThat(groupLines("1")).containsExactly(G_IXQZ, G_QRTV, G_ZWLK);
        assertConsistent("1");
    }

    @Test
    @DisplayName("AC7 ZWLK 手动现价 32.00 → +960.00 / +60.00（6.67%）、合计 +160.00、提示消失；H7 实收改 650.00 → 已实现 +50.00、合计 +110.00；清除现价 → 市值 / 未实现 null、合计 +50.00、1 个无报价")
    void ac7ManualPriceEditAndClear() {
        scenario023Pulled();
        setNow("2026-11-23T10:05");
        overrideUnderlying("ZWLK", "32.00");
        assertThat(val(stkRow("1", "ZWLK"))).isEqualTo(okVal("960.00", "60.00", "6.67"));
        assertThat(groupLine("1", "ZWLK")).isEqualTo(
                "ZWLK | 1 100.00 (0 0.00 · 1 100.00) | mv 960.00 | upnl 60.00 | total 160.00"
                        + " | prem null | opt 0 stock true | 1/0/0 | null");
        assertConsistent("1");

        editStockAmount(h7SellId, 20, "650.00", "2026-11-12T10:00");
        assertThat(groupLine("1", "ZWLK")).isEqualTo(
                "ZWLK | 1 50.00 (0 0.00 · 1 50.00) | mv 960.00 | upnl 60.00 | total 110.00"
                        + " | prem null | opt 0 stock true | 1/0/0 | null");
        // remaining cost still 900.00
        assertThat(stkRow("1", "ZWLK").get("totalCost").asText()).isEqualTo("900.00");
        assertConsistent("1");

        assertThat(deleteUnderlyingOverride("ZWLK").status()).isEqualTo(204);
        assertThat(groupLine("1", "ZWLK")).isEqualTo(
                "ZWLK | 1 50.00 (0 0.00 · 1 50.00) | mv null | upnl null | total 50.00"
                        + " | prem null | opt 0 stock true | 0/1/0 | " + LABEL_1_NO_QUOTE);
        assertConsistent("1");
        // other groups untouched
        assertThat(groupLine("1", "IXQZ")).isEqualTo(G_IXQZ);
        assertThat(groupLine("1", "QRTV")).isEqualTo(G_QRTV);
    }

    @Test
    @DisplayName("AC8 账户 B 只有 QRTV：已实现 0 笔 0.00、未实现 +5.00、合计 +5.00、市值 −110.00、净权利金 +115.00；A 不受影响；空账户 groups []")
    void ac8AccountIsolation() {
        scenario023Pulled();
        JsonNode b = underlying(accountB);
        assertThat(b.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(groupLines(accountB)).containsExactly(G_QRTV_B);
        // realized objects present with zeros, not null (contract)
        JsonNode r = b.get("groups").get(0).get("realized");
        assertThat(r.get("option").isObject()).isTrue();
        assertThat(r.get("stock").isObject()).isTrue();
        assertConsistent(accountB);

        assertThat(groupLines("1")).containsExactly(G_IXQZ, G_QRTV, G_ZWLK);

        String c = createAccount("账户C");
        JsonNode empty = underlying(c);
        assertThat(empty.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(empty.get("groups").isArray()).isTrue();
        assertThat(empty.get("groups")).isEmpty();
        assertConsistent(c);
    }
}
