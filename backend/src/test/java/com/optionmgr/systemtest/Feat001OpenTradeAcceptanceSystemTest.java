package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FEAT-001 acceptance criteria 1–11, 13–15, 17, 18 (backend part) end-to-end over HTTP. */
class Feat001OpenTradeAcceptanceSystemTest extends OptionTradeSystemTestBase {

    static final String DEP = "2026-11-02T10:00";
    static final String AT = "2026-11-10T10:30";

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC1 STO put 成功：存 UTC、汇总 30,349.35/20,000.00/10,349.35、流水 OPTION_OPEN +349.35、GET 与 POST 一致")
    void ac1_stoPut() {
        deposit("30000.00", DEP);
        ObjectNode req = aapl("STO", "200", "PUT", "349.35", AT);
        req.put("note", "第一笔 CSP");
        JsonNode t = okTrade(req);

        assertThat(t.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(t.get("expirationDate").asText()).isEqualTo("2026-12-18");
        assertThat(t.get("strike").asText()).isEqualTo("200");
        assertThat(t.get("optionType").asText()).isEqualTo("PUT");
        assertThat(t.get("action").asText()).isEqualTo("STO");
        assertThat(t.get("contracts").isIntegralNumber()).isTrue();
        assertThat(t.get("contracts").asInt()).isEqualTo(1);
        assertThat(t.get("multiplier").isIntegralNumber()).isTrue();
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("netAmount").asText()).isEqualTo("349.35");
        assertThat(t.get("netAmount").isTextual()).isTrue();
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-10T15:30:00Z");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2026-11-10T10:30:00");
        assertThat(t.get("cashChange").asText()).isEqualTo("349.35");
        assertThat(t.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(t.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 200 P");
        assertThat(t.get("note").asText()).isEqualTo("第一笔 CSP");
        assertThat(Instant.parse(t.get("createdAt").asText())).isEqualTo(FEAT001_NOW);

        assertSummary("30349.35", "20000.00", "10349.35");

        JsonNode top = ledger().get("items").get(0);
        assertThat(top.get("id").asLong()).isEqualTo(t.get("ledgerEntryId").asLong());
        assertThat(top.get("occurredAt").asText()).isEqualTo("2026-11-10T15:30:00Z");
        assertThat(top.get("sourceType").asText()).isEqualTo("OPTION_OPEN");
        assertThat(top.get("sourceId").asLong()).isEqualTo(t.get("id").asLong());
        assertThat(top.get("amount").asText()).isEqualTo("349.35");
        assertThat(top.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(top.get("balanceAfter").asText()).isEqualTo("30349.35");
        assertThat(top.get("description").asText()).isEqualTo("STO 1 张 AAPL 2026-12-18 200 P");
        assertThat(top.get("remark").asText()).isEqualTo("第一笔 CSP");
        // deposits now also carry frozenChange 0.00 (compatible FEAT-007 change)
        assertThat(ledger().get("items").get(1).get("frozenChange").asText()).isEqualTo("0.00");

        assertThat(getTrade(t.get("id").asLong())).isEqualTo(t);
    }

    // ------------------------------------------------------------------ AC 2, 3

    @Test
    @DisplayName("AC2 多张 + 小数行权价：STO 2 F 12.5 P → 5,048.70/2,500.00/2,548.70")
    void ac2_multipleContractsDecimalStrike() {
        deposit("5000.00", DEP);
        JsonNode t = okTrade(trade("STO", 2, "F", "2026-12-18", "12.5", "PUT", "48.70", AT, 100));
        assertThat(t.get("strike").asText()).isEqualTo("12.5");
        assertThat(t.get("frozenChange").asText()).isEqualTo("2500.00");
        assertThat(t.get("contractLabel").asText()).isEqualTo("F 2026-12-18 12.5 P");
        assertSummary("5048.70", "2500.00", "2548.70");
        assertThat(ledgerRowFor("OPTION_OPEN", t.get("id").asLong()).get("description").asText())
                .isEqualTo("STO 2 张 F 2026-12-18 12.5 P");
    }

    @Test
    @DisplayName("AC3 BTO 成功：29,487.35/0.00/29,487.35，流水 −512.65、冻结变动 0.00")
    void ac3_bto() {
        deposit("30000.00", DEP);
        JsonNode t = okTrade(aapl("BTO", "210", "CALL", "512.65", AT));
        assertThat(t.get("cashChange").asText()).isEqualTo("-512.65");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("29487.35", "0.00", "29487.35");
        JsonNode row = ledgerRowFor("OPTION_OPEN", t.get("id").asLong());
        assertThat(row.get("amount").asText()).isEqualTo("-512.65");
        assertThat(row.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(row.get("balanceAfter").asText()).isEqualTo("29487.35");
        assertThat(row.get("description").asText()).isEqualTo("BTO 1 张 AAPL 2026-12-18 210 C");
        assertThat(row.get("remark").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ AC 4, 5, 6

    @Test
    @DisplayName("AC4 BTO 可用不足：需要 512.65，可用 500.00；改 500.00 成功到 0.00")
    void ac4_btoInsufficient() {
        deposit("500.00", DEP);
        assertThat(rejectedTrade(aapl("BTO", "210", "CALL", "512.65", AT), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 512.65，可用 500.00");
        assertSummary("500.00", "0.00", "500.00");
        assertThat(tradeCount()).isZero();
        okTrade(aapl("BTO", "210", "CALL", "500.00", AT));
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("AC5 冻结现金不能用于买入：需要 900.00，可用 849.35；849.35 成功 → 20,000/20,000/0")
    void ac5_frozenCashCannotBuy() {
        deposit("20500.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", AT));
        assertSummary("20849.35", "20000.00", "849.35");
        ObjectNode msft = trade("BTO", 1, "MSFT", "2026-12-18", "450", "CALL", "900.00", "2026-11-10T11:00", 100);
        assertThat(rejectedTrade(msft, "CASH_INSUFFICIENT")).isEqualTo("可用现金不足：需要 900.00，可用 849.35");
        msft.put("netAmount", "849.35");
        okTrade(msft);
        assertSummary("20000.00", "20000.00", "0.00");
    }

    @Test
    @DisplayName("AC6 STO put 担保不足（Q18 权利金不算）：需要 20,000.00，可用 19,999.99；无冻结")
    void ac6_collateralShortByOneCent() {
        deposit("19999.99", DEP);
        assertThat(rejectedTrade(aapl("STO", "200", "PUT", "349.35", AT), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足以担保卖出 put：需要 20,000.00，可用 19,999.99");
        assertSummary("19999.99", "0.00", "19999.99");
    }

    @Test
    @DisplayName("AC6 入金恰好 20,000.00：同样录入成功 → 20,349.35/20,000.00/349.35")
    void ac6_exactCollateral() {
        deposit("20000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", AT));
        assertSummary("20349.35", "20000.00", "349.35");
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("AC7 同一合约多次开仓：2 笔独立交易，冻结累加 → 50,709.35/40,000.00/10,709.35")
    void ac7_sameContractTwice() {
        deposit("50000.00", DEP);
        JsonNode a = okTrade(aapl("STO", "200", "PUT", "349.35", "2026-11-10T10:30"));
        JsonNode b = okTrade(aapl("STO", "200", "PUT", "360.00", "2026-11-11T11:00"));
        assertThat(b.get("id").asLong()).isNotEqualTo(a.get("id").asLong());
        assertThat(tradeCount()).isEqualTo(2);
        assertSummary("50709.35", "40000.00", "10709.35");
        assertThat(getTrade(a.get("id").asLong()).get("netAmount").asText()).isEqualTo("349.35");
        assertThat(getTrade(b.get("id").asLong()).get("netAmount").asText()).isEqualTo("360.00");
    }

    // ------------------------------------------------------------------ AC 8, 9, 10

    @Test
    @DisplayName("AC8 多空冲突：空头时 BTO 被拒（提示 BTC）；不同行权价允许；多头 call 时 STO 被拒（提示 STC）")
    void ac8_directionConflict() {
        deposit("30000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", AT));
        assertThat(rejectedTrade(aapl("BTO", "200", "PUT", "300.00", "2026-11-10T11:00"),
                "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（1 张），不能买入开仓；如要平仓请使用 Buy to Close（BTC）");
        assertSummary("30349.35", "20000.00", "10349.35");

        okTrade(aapl("BTO", "205", "PUT", "300.00", "2026-11-10T11:00"));

        okTrade(aapl("BTO", "210", "CALL", "512.65", "2026-11-10T11:30"));
        assertThat(rejectedTrade(aapl("STO", "210", "CALL", "100.00", "2026-11-10T12:00"),
                "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）");
    }

    @Test
    @DisplayName("AC9（FEAT-038 取代 Q16）STO call 允许：现金 +120.00、冻结 0.00，流水说明同 FEAT-001")
    void ac9_stoCallAcceptedWithoutFreeze() {
        deposit("100000.00", DEP);
        transferInShares("AAPL", 100, "2026-11-10"); // FEAT-039 (Q77): the call needs 100 shares; no cash effect
        JsonNode t = okTrade(aapl("STO", "250", "CALL", "120.00", AT));
        assertThat(t.get("cashChange").asText()).isEqualTo("120.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(t.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 250 C");
        assertSummary("100120.00", "0.00", "100120.00");
        JsonNode row = ledgerRowFor("OPTION_OPEN", t.get("id").asLong());
        assertThat(row.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(row.get("description").asText()).isEqualTo("STO 1 张 AAPL 2026-12-18 250 C");
        assertThat(tradeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC10 标的规范化：' aapl ' 识别为 AAPL 被拒；' msft ' 保存为 MSFT")
    void ac10_symbolNormalisation() {
        deposit("30000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", AT));
        ObjectNode lower = aapl("BTO", "200", "PUT", "300.00", "2026-11-10T11:00");
        lower.put("symbol", " aapl ");
        assertThat(rejectedTrade(lower, "POSITION_DIRECTION_CONFLICT")).contains("AAPL 2026-12-18 200 P");

        deposit("40000.00", DEP);
        JsonNode m = okTrade(trade("STO", 1, " msft ", "2026-12-18", "400", "PUT", "500.00", "2026-11-10T11:00", 100));
        assertThat(m.get("symbol").asText()).isEqualTo("MSFT");
        assertThat(m.get("contractLabel").asText()).isEqualTo("MSFT 2026-12-18 400 P");
        assertThat(getTrade(m.get("id").asLong()).get("symbol").asText()).isEqualTo("MSFT");
    }

    // ------------------------------------------------------------------ AC 11

    @Test
    @DisplayName("AC11 成交时间换算：夏令时 07-15 10:30 → 14:30Z，标准时间 11-10 10:30 → 15:30Z；DST 切换当天规则")
    void ac11_timeConversion() {
        deposit("100000.00", "2026-01-02T10:00");
        JsonNode summer = okTrade(trade("BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "1.00", "2026-07-15T10:30", 100));
        assertThat(summer.get("tradedAt").asText()).isEqualTo("2026-07-15T14:30:00Z");
        assertThat(summer.get("tradedAtLocal").asText()).isEqualTo("2026-07-15T10:30:00");
        assertThat(getTrade(summer.get("id").asLong()).get("tradedAtLocal").asText()).isEqualTo("2026-07-15T10:30:00");

        JsonNode winter = okTrade(trade("BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "1.00", "2026-11-10T10:30", 100));
        assertThat(winter.get("tradedAt").asText()).isEqualTo("2026-11-10T15:30:00Z");

        JsonNode gap = okTrade(trade("BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "1.00", "2026-03-08T02:30", 100));
        assertThat(gap.get("tradedAt").asText()).isEqualTo("2026-03-08T07:30:00Z");
        assertThat(gap.get("tradedAtLocal").asText()).isEqualTo("2026-03-08T03:30:00");
        JsonNode overlap = okTrade(trade("BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "1.00", "2026-11-01T01:30", 100));
        assertThat(overlap.get("tradedAt").asText()).isEqualTo("2026-11-01T05:30:00Z");

        // exactly "now" (10:00 NY on 11-12) is allowed
        JsonNode atNow = okTrade(trade("BTO", 1, "AAPL", "2026-12-18", "210", "CALL", "1.00", "2026-11-12T10:00:00", 100));
        assertThat(atNow.get("tradedAt").asText()).isEqualTo("2026-11-12T15:00:00Z");
    }

    // ------------------------------------------------------------------ AC 13, 14 (Q19)

    @Test
    @DisplayName("AC13 补录 BTO：11-05 会变 −500.00 被拒；800.00 成功 → 200/0/200；早于首笔入金被拒 可用 0.00")
    void ac13_backdatedBtoReplay() {
        deposit("10000.00", "2026-11-02T10:00");
        withdraw("9000.00", "2026-11-05T10:00");
        assertThat(rejectedTrade(aapl("BTO", "210", "CALL", "1500.00", "2026-11-03T10:00"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00");
        okTrade(aapl("BTO", "210", "CALL", "800.00", "2026-11-03T10:00"));
        assertSummary("200.00", "0.00", "200.00");
        assertThat(rejectedTrade(aapl("BTO", "210", "CALL", "100.00", "2026-11-01T10:00"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 100.00，可用 0.00");
    }

    @Test
    @DisplayName("AC14 补录 STO put 使之后出金时可用变为 −150.65 → 拒绝")
    void ac14_backdatedStoPutBreaksLaterWithdrawal() {
        deposit("25000.00", "2026-11-02T10:00");
        withdraw("5500.00", "2026-11-05T10:00");
        assertThat(rejectedTrade(aapl("STO", "200", "PUT", "349.35", "2026-11-03T10:00"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足以担保卖出 put：需要 20,000.00；2026-11-05 10:00 时可用现金将变为 −150.65");
        assertSummary("19500.00", "0.00", "19500.00");
    }

    @Test
    @DisplayName("AC14 出金 4,000.00 的场景：补录成功 → 21,349.35/20,000.00/1,349.35")
    void ac14_backdatedStoPutOk() {
        deposit("25000.00", "2026-11-02T10:00");
        withdraw("4000.00", "2026-11-05T10:00");
        okTrade(aapl("STO", "200", "PUT", "349.35", "2026-11-03T10:00"));
        assertSummary("21349.35", "20000.00", "1349.35");
        // running balances after the backfill
        assertThat(ledgerLines()).containsExactly(
                "2026-11-05T15:00:00Z WITHDRAWAL -4000.00 21349.35",
                "2026-11-03T15:00:00Z OPTION_OPEN 349.35 25349.35",
                "2026-11-02T15:00:00Z DEPOSIT 25000.00 25000.00");
    }

    @Test
    @DisplayName("FEAT-007 AC11 补测：冻结的现金不能取出（入金 25,000、STO put 冻结 20,000 后出金 5,500 被拒）")
    void frozenCashCannotBeWithdrawn() {
        deposit("25000.00", "2026-11-02T10:00");
        okTrade(aapl("STO", "200", "PUT", "349.35", "2026-11-03T10:00"));
        assertSummary("25349.35", "20000.00", "5349.35");
        assertThat(rejectedWithdrawal("5500.00", "2026-11-05T10:00"))
                .isEqualTo("可用现金不足：需要 5,500.00，可用 5,349.35");
        // backdated withdrawal before the STO: fine at its own point, but the STO point goes negative
        assertThat(rejectedWithdrawal("5500.00", "2026-11-02T11:00"))
                .isEqualTo("可用现金不足：需要 5,500.00；2026-11-03 10:00 时可用现金将变为 −150.65");
        withdraw("5349.35", "2026-11-05T10:00");
        assertSummary("20000.00", "20000.00", "0.00");
        assertThat(rejectedWithdrawal("0.01", "2026-11-05T11:00")).isEqualTo("可用现金不足：需要 0.01，可用 0.00");
    }

    // ------------------------------------------------------------------ AC 15

    @Test
    @DisplayName("AC15 multiplier 10：冻结 2,000.00；同合约再开 multiplier 100 被拒（先于现金检查）")
    void ac15_nonStandardMultiplier() {
        deposit("5000.00", DEP);
        JsonNode t = okTrade(trade("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", AT, 10));
        assertThat(t.get("frozenChange").asText()).isEqualTo("2000.00");
        assertThat(t.get("multiplier").asInt()).isEqualTo(10);
        assertSummary("5034.35", "2000.00", "3034.35");
        assertThat(ledgerRowFor("OPTION_OPEN", t.get("id").asLong()).get("description").asText())
                .isEqualTo("STO 1 张 XYZ 2026-12-18 200 P（multiplier 10）");
        // would also lack cash (20,000 collateral) -> Q23 is reported first
        assertThat(rejectedTrade(trade("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "300.00", "2026-11-10T11:00", 100),
                "MULTIPLIER_MISMATCH"))
                .isEqualTo("该合约（XYZ 2026-12-18 200 P）已有未平仓持仓，multiplier 为 10，新开仓的 multiplier 必须相同");
    }

    // ------------------------------------------------------------------ AC 17 (Q27)

    @Test
    @DisplayName("AC17 补录较早 BTO 使之后的 STO 冲突 → POSITION_DIRECTION_CONFLICT；同方向补录成功")
    void ac17_backfillDirectionConflict() {
        deposit("50000.00", DEP);
        okTrade(aapl("STO", "200", "PUT", "349.35", "2026-11-11T10:00"));
        assertSummary("50349.35", "20000.00", "30349.35");
        assertThat(rejectedTrade(aapl("BTO", "200", "PUT", "300.00", "2026-11-10T10:00"), "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-11 10:00 的 STO 将与多头持仓（1 张）冲突；一个合约任一时刻只能纯多或纯空");
        assertSummary("50349.35", "20000.00", "30349.35");

        okTrade(aapl("STO", "200", "PUT", "340.00", "2026-11-10T10:00"));
        assertThat(tradeCount()).isEqualTo(2);
        assertSummary("50689.35", "40000.00", "10689.35");
    }

    @Test
    @DisplayName("AC17 补录较早不同 multiplier → MULTIPLIER_MISMATCH；multiplier 10 补录成功")
    void ac17_backfillMultiplierMismatch() {
        deposit("30000.00", DEP);
        okTrade(trade("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-11T10:00", 10));
        assertSummary("30034.35", "2000.00", "28034.35");
        assertThat(rejectedTrade(trade("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "300.00", "2026-11-10T10:00", 100),
                "MULTIPLIER_MISMATCH"))
                .isEqualTo("补录后该合约（XYZ 2026-12-18 200 P）在 2026-11-11 10:00 的交易 multiplier 为 10，与当时持仓的 multiplier 100 不一致");
        assertSummary("30034.35", "2000.00", "28034.35");
        okTrade(trade("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "30.00", "2026-11-10T10:00", 10));
        assertSummary("30064.35", "4000.00", "26064.35");
    }

    // ------------------------------------------------------------------ AC 18 (Q28)

    @Test
    @DisplayName("AC18 担保 12.345 → 向上取整 12.35：100.50/12.35/88.15")
    void ac18_collateralRoundedUp() {
        deposit("100.00", DEP);
        JsonNode t = okTrade(trade("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", AT, 1));
        assertThat(t.get("strike").asText()).isEqualTo("12.345");
        assertThat(t.get("frozenChange").asText()).isEqualTo("12.35");
        assertSummary("100.50", "12.35", "88.15");
        assertThat(ledgerRowFor("OPTION_OPEN", t.get("id").asLong()).get("frozenChange").asText()).isEqualTo("12.35");
    }

    @Test
    @DisplayName("AC18 入金 12.34：担保 12.35 不足被拒")
    void ac18_roundedCollateralInsufficient() {
        deposit("12.34", DEP);
        assertThat(rejectedTrade(trade("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", AT, 1), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足以担保卖出 put：需要 12.35，可用 12.34");
    }

    @Test
    @DisplayName("Q28 其它取整：12.341×1×1 → 12.35；12.345×1×3 → 37.04；12.345×10×1 → 123.45（不取整）")
    void q28_moreRounding() {
        deposit("1000.00", DEP);
        assertThat(okTrade(trade("STO", 1, "F", "2026-12-18", "12.341", "PUT", "0.50", AT, 1))
                .get("frozenChange").asText()).isEqualTo("12.35");
        assertThat(okTrade(trade("STO", 3, "G", "2026-12-18", "12.345", "PUT", "0.50", AT, 1))
                .get("frozenChange").asText()).isEqualTo("37.04");
        assertThat(okTrade(trade("STO", 1, "H", "2026-12-18", "12.345", "PUT", "0.50", AT, 10))
                .get("frozenChange").asText()).isEqualTo("123.45");
        assertSummary("1001.50", "172.84", "828.66");
    }
}
