package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-051 平仓时指定批次: acceptance criteria 1–7 and 9 of
 * docs/requirements/FEAT-051-choose-lots-to-close.md over HTTP, checked against docs/api/FEAT-051.md. AC 8 (data entered
 * before the upgrade) is in {@link Feat051CloseLotsMigrationSystemTest}. See {@link Feat051CloseLotsSystemTestBase}
 * for the scenarios Z / P.
 */
@DisplayName("FEAT-051 平仓时指定批次（验收标准）")
class Feat051CloseLotsAcceptanceSystemTest extends Feat051CloseLotsSystemTestBase {

    // ============================================================================================== AC 1

    @Test
    @DisplayName("AC1 场景 Z：持仓批次 A（11-10 10:00、1 张、+18.34、closeDefault true）、B（11-11 10:00、+37.34、false），closeAction BTC")
    void ac1_lotsWithCloseDefault() {
        scenarioZ();
        JsonNode row = optRow(A, C9);
        assertThat(row.get("closeAction").asText()).isEqualTo("BTC");
        assertThat(row.get("direction").asText()).isEqualTo("SHORT");
        assertThat(lots(row)).containsExactly(
                "A 2026-11-10T10:00:00 1 18.34 0.00 true",
                "B 2026-11-11T10:00:00 1 37.34 0.00 false");
        for (JsonNode l : row.get("lots")) {
            assertThat(l.get("closeDefault").isBoolean()).as("closeDefault is a JSON boolean").isTrue();
            assertThat(l.get("action").asText()).isEqualTo("STO");
            // FEAT-048 fields the dialog shows (Q258) are still on the lot
            assertThat(fieldNames(l)).contains("unrealizedPnl", "unrealizedLabel", "remainingFrozen");
        }
        // allowedCloseContracts kept (FIFO mode / assignment / exercise still use it)
        assertThat(row.get("allowedCloseContracts").toString()).isEqualTo("[1,2]");
    }

    // ============================================================================================== AC 2

    @Test
    @DisplayName("AC2 只选较晚的 B、净额 15.10：响应被平批次 B、盈亏 +22.24、张数 1；现金 1,040.58/0/1,040.58；持仓只剩 A；股票被覆盖 100 可用 100；已平仓开仓净额 +37.34；按 id 与列表一致；保存 (close, B)")
    void ac2_closeLaterLot() {
        scenarioZ();
        JsonNode c = okCloseLots("BTC", "9", "CALL", ls(ids.get("B")), "15.10", "2026-11-19T10:00");
        ids.put("X", c.get("id").asLong());
        assertThat(c.get("accountId").asLong()).isEqualTo(1);
        assertThat(c.get("accountName").asText()).isEqualTo(JIA);
        assertThat(c.get("action").asText()).isEqualTo("BTC");
        assertThat(c.get("contracts").asInt()).as("contracts = Σ chosen lots").isEqualTo(1);
        assertThat(c.get("multiplier").asInt()).isEqualTo(100);
        assertThat(c.get("netAmount").asText()).isEqualTo("15.10");
        assertThat(c.get("tradedAt").asText()).isEqualTo("2026-11-19T15:00:00Z");
        assertThat(c.get("tradedAtLocal").asText()).isEqualTo("2026-11-19T10:00:00");
        assertThat(c.get("cashChange").asText()).isEqualTo("-15.10");
        assertThat(c.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(c.get("contractLabel").asText()).isEqualTo(C9);
        assertThat(covered(c)).containsExactly("B STO 1 37.34 0.00");
        JsonNode lot = c.get("coveredLots").get(0);
        assertThat(lot.get("tradedAt").asText()).isEqualTo("2026-11-11T15:00:00Z");
        assertThat(lot.get("tradedAtLocal").asText()).isEqualTo("2026-11-11T10:00:00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(c.get("hasAuditLog").asBoolean()).isFalse();

        assertThat(cash(A)).isEqualTo("1040.58 / 0.00 / 1040.58");
        JsonNode row = optRow(A, C9);
        assertThat(row.get("contracts").asInt()).isEqualTo(1);
        assertThat(lots(row)).containsExactly("A 2026-11-10T10:00:00 1 18.34 0.00 true");
        assertThat(stock(A, "ZZQ")).as("B's 100 shares released (CHECKED lot)").isEqualTo("200/100/100");

        JsonNode closed = closedItem(A, c.get("id").asLong());
        assertThat(closed.get("openingNetTotal").asText()).isEqualTo("37.34");
        assertThat(closed.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(covered(closed)).containsExactly("B STO 1 37.34 0.00");

        assertThat(tradeById(c.get("id").asLong())).as("GET by id = POST response").isEqualTo(c);
        JsonNode list = okAs(A, TRADES + "?action=BTC");
        assertThat(list.get("items").get(0)).as("list item = GET by id").isEqualTo(c);
        assertThat(storedLots(c.get("id").asLong())).containsExactly(ids.get("B"));
        assertThat(closeLotRows()).hasSize(1);
        assertThat(closeLotRows().get(0).get("account_id").toString()).isEqualTo("1");
    }

    // ============================================================================================== AC 3

    @Test
    @DisplayName("AC3 不改默认（选 A）：被平批次 A、盈亏 +3.24；剩余 B +37.34（成为 closeDefault）；现金 1,040.58/0/1,040.58")
    void ac3_defaultFifoLot() {
        scenarioZ();
        JsonNode c = okCloseLots("BTC", "9", "CALL", ls(ids.get("A")), "15.10", "2026-11-19T10:00");
        assertThat(covered(c)).containsExactly("A STO 1 18.34 0.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("3.24");
        assertThat(lots(optRow(A, C9))).containsExactly("B 2026-11-11T10:00:00 1 37.34 0.00 true");
        assertThat(cash(A)).isEqualTo("1040.58 / 0.00 / 1040.58");
        assertThat(stock(A, "ZZQ")).isEqualTo("200/100/100");
    }

    // ============================================================================================== AC 4

    @Test
    @DisplayName("AC4 场景 P 只选 P2：冻结变动 −800.00、盈亏 +11.35、现金 30,041.35/1,600.00/28,441.35、剩 P1 2 张 +30.00 冻结 1,600.00")
    void ac4_shortPutLaterLotReleasesItsFrozen() {
        scenarioP();
        JsonNode c = okCloseLots("BTC", "8", "PUT", ls(ids.get("P2")), "9.65", "2026-11-19T10:00");
        assertThat(c.get("contracts").asInt()).isEqualTo(1);
        assertThat(c.get("frozenChange").asText()).isEqualTo("-800.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("11.35");
        assertThat(covered(c)).containsExactly("P2 STO 1 21.00 800.00");
        assertThat(cash(A)).isEqualTo("30041.35 / 1600.00 / 28441.35");
        assertThat(lots(optRow(A, P8))).containsExactly("P1 2026-11-10T10:00:00 2 30.00 1600.00 true");
        JsonNode ledger = okAs(A, "/api/cash/ledger?size=100").get("items").get(0);
        assertThat(ledger.get("sourceType").asText()).isEqualTo("OPTION_CLOSE");
        assertThat(ledger.get("frozenChange").asText()).isEqualTo("-800.00");
    }

    @Test
    @DisplayName("AC4（另起场景 P）选 P1 + P2、净额 20.00：张数 3、冻结变动 −2,400.00、盈亏 +31.00、现金 30,031.00/0/30,031.00、没有该持仓")
    void ac4_shortPutBothLots() {
        scenarioP();
        JsonNode c = okCloseLots("BTC", "8", "PUT", ls(ids.get("P1"), ids.get("P2")), "20.00", "2026-11-19T10:00");
        assertThat(c.get("contracts").asInt()).isEqualTo(3);
        assertThat(c.get("frozenChange").asText()).isEqualTo("-2400.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("31.00");
        assertThat(covered(c)).containsExactly("P1 STO 2 30.00 1600.00", "P2 STO 1 21.00 800.00");
        assertThat(cash(A)).isEqualTo("30031.00 / 0.00 / 30031.00");
        assertThat(optRow(A, P8)).isNull();
    }

    // ============================================================================================== AC 5

    @Test
    @DisplayName("AC5 后端校验：成交时 B 未开仓；[] → 400；乙的批次 C；同一批次两次；B 已被平掉 — 每项原文、现金持仓不变")
    void ac5_lotValidation() {
        scenarioZ();
        accountYiWithC();
        long a = ids.get("A");
        long b = ids.get("B");
        long cOfYi = ids.get("C");

        Map<String, Object> before = snapshot();
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(b), null, "15.10", "2026-11-10T15:00"), before,
                CLOSES)).isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓");

        rejected400(closeLotsResp("BTC", "9", "CALL", List.of(), null, "15.10", "2026-11-19T10:00"), before, CLOSES,
                "lotTradeIds=请至少选择一笔开仓批次");

        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(cOfYi), null, "15.10", "2026-11-19T10:00"),
                before, CLOSES)).isEqualTo("所选批次 #" + cOfYi + " 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易");
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(b, b), null, "15.10", "2026-11-19T10:00"),
                before, CLOSES)).isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）重复选择");
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(a), 2, "15.10", "2026-11-19T10:00"),
                before, CLOSES)).isEqualTo("平仓张数 2 张与所选批次张数之和 1 张不一致");

        // after AC 2, B is already closed
        okCloseLots("BTC", "9", "CALL", ls(b), "15.10", "2026-11-19T10:00");
        Map<String, Object> after2 = snapshot();
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(b), null, "1.00", "2026-11-20T09:00"), after2,
                CLOSES)).isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）已被 2026-11-19 10:00 的 BTC 1 张平掉");
        // 乙 untouched throughout
        assertThat(lots(optRow(B, C9))).containsExactly("C 2026-11-12T10:00:00 1 20.00 0.00 true");
    }

    // ============================================================================================== AC 6

    @Test
    @DisplayName("AC6 AC2 之后：把 B 改到 11-19 11:00 / 删除 B → CLOSE_LOTS_INVALID 指出那笔 BTC 和批次 B；补录选 B → 拒绝；改选 A 净额 10.00 → +8.34，原平仓仍 B +22.24，持仓清空，1,030.58/0/1,030.58")
    void ac6_replayUsesStoredLots() {
        scenarioZ();
        long a = ids.get("A");
        long b = ids.get("B");
        long x = okCloseLots("BTC", "9", "CALL", ls(b), "15.10", "2026-11-19T10:00").get("id").asLong();
        ids.put("X", x);

        Map<String, Object> before = snapshot();
        assertThat(rejectedLots(putOpen(b, 1, "37.34", "2026-11-19T11:00"), before, TRADES + "/" + b))
                .isEqualTo("修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-19 11:00 STO 1 张 在该平仓成交时还未开仓");
        assertThat(rejectedLots(deleteTrade(b), before, TRADES + "/" + b))
                .isEqualTo("该开仓被 2026-11-19 10:00 的 BTC 1 张平掉（所选批次 2026-11-11 10:00 STO 1 张），请先修改或删除那笔平仓");
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(b), null, "10.00", "2026-11-15T10:00"), before,
                CLOSES)).isEqualTo("补录后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-11 10:00 STO 1 张 已被更早的平仓平掉");

        JsonNode back = okCloseLots("BTC", "9", "CALL", ls(a), "10.00", "2026-11-15T10:00");
        assertThat(covered(back)).containsExactly("A STO 1 18.34 0.00");
        assertThat(back.get("realizedPnl").asText()).isEqualTo("8.34");
        JsonNode later = tradeById(x);
        assertThat(covered(later)).as("the 11-19 close keeps its stored lot B (Q255)").containsExactly("B STO 1 37.34 0.00");
        assertThat(later.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(optRow(A, C9)).isNull();
        assertThat(cash(A)).isEqualTo("1030.58 / 0.00 / 1030.58");
        assertThat(stock(A, "ZZQ")).isEqualTo("200/0/200");
        assertThat(storedLots(x)).containsExactly(b);
        assertThat(storedLots(back.get("id").asLong())).containsExactly(a);
    }

    // ============================================================================================== AC 7

    @Test
    @DisplayName("AC7 AC3 之后把那笔 BTC 改为只选 B：盈亏 +22.24、剩 A +18.34、现金不变；审计一条修改，被平批次 2026-11-10 10:00 1 张 → 2026-11-11 10:00 1 张；hasAuditLog true")
    void ac7_editSelectedLots() {
        scenarioZ();
        long a = ids.get("A");
        long b = ids.get("B");
        long x = okCloseLots("BTC", "9", "CALL", ls(a), "15.10", "2026-11-19T10:00").get("id").asLong();
        ids.put("X", x);

        JsonNode t = okPutClose(x, ls(b), "15.10", "2026-11-19T10:00");
        assertThat(t.get("id").asLong()).isEqualTo(x);
        assertThat(covered(t)).containsExactly("B STO 1 37.34 0.00");
        assertThat(t.get("contracts").asInt()).isEqualTo(1);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(t.get("hasAuditLog").asBoolean()).isTrue();
        assertThat(tradeById(x)).isEqualTo(t);
        assertThat(lots(optRow(A, C9))).containsExactly("A 2026-11-10T10:00:00 1 18.34 0.00 true");
        assertThat(cash(A)).isEqualTo("1040.58 / 0.00 / 1040.58");
        assertThat(stock(A, "ZZQ")).isEqualTo("200/100/100");
        assertThat(storedLots(x)).containsExactly(b);

        JsonNode audit = auditItems();
        assertThat(audit).hasSize(1);
        JsonNode r = audit.get(0);
        assertThat(r.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(r.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(r.get("objectId").asLong()).isEqualTo(x);
        assertThat(texts(r.get("changes")))
                .containsExactly("被平批次：2026-11-10 10:00 1 张 → 2026-11-11 10:00 1 张");
        JsonNode ch = r.get("changes").get(0);
        assertThat(ch.get("field").asText()).isEqualTo("selectedLots");
        assertThat(ch.get("label").asText()).isEqualTo("被平批次");
        assertThat(ch.get("before").asText()).isEqualTo("2026-11-10 10:00 1 张");
        assertThat(ch.get("after").asText()).isEqualTo("2026-11-11 10:00 1 张");
        assertThat(selectedLots(r.get("before"))).containsExactly("A STO 2026-11-10T10:00:00 1");
        assertThat(selectedLots(r.get("after"))).containsExactly("B STO 2026-11-11T10:00:00 1");
        assertThat(r.get("before").get("selectedLots").get(0).get("tradedAt").asText())
                .isEqualTo("2026-11-10T15:00:00Z");
        assertThat(r.get("sideEffects")).isEmpty();
    }

    @Test
    @DisplayName("AC7（场景 P，AC4 第一种之后）所选批次 P2 → P1：张数 2、冻结变动 −1,600.00、盈亏 +20.35、现金 30,041.35/800.00/29,241.35；审计 被平批次 / 张数 / 冻结变动")
    void ac7_editSelectedLotsShortPut() {
        scenarioP();
        long p1 = ids.get("P1");
        long p2 = ids.get("P2");
        long x = okCloseLots("BTC", "8", "PUT", ls(p2), "9.65", "2026-11-19T10:00").get("id").asLong();
        long ledgerId = tradeById(x).get("ledgerEntryId").asLong();

        JsonNode t = okPutClose(x, ls(p1), "9.65", "2026-11-19T10:00");
        assertThat(t.get("contracts").asInt()).isEqualTo(2);
        assertThat(t.get("frozenChange").asText()).isEqualTo("-1600.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("20.35");
        assertThat(t.get("ledgerEntryId").asLong()).as("ledger row updated in place").isEqualTo(ledgerId);
        assertThat(covered(t)).containsExactly("P1 STO 2 30.00 1600.00");
        assertThat(cash(A)).isEqualTo("30041.35 / 800.00 / 29241.35");
        assertThat(lots(optRow(A, P8))).containsExactly("P2 2026-11-11T10:00:00 1 21.00 800.00 true");

        JsonNode r = auditItems().get(0);
        assertThat(texts(r.get("changes"))).containsExactly(
                "被平批次：2026-11-11 10:00 1 张 → 2026-11-10 10:00 2 张",
                "张数：1 → 2",
                "冻结变动：−800.00 → −1,600.00");
    }

    // ============================================================================================== AC 9

    @Test
    @DisplayName("AC9 录入交易页（不带 lotTradeIds、张数 1）：按 FIFO 平掉 A，结果同 AC3，并保存 A 为所选批次")
    void ac9_entryPageStillFifo() {
        scenarioZ();
        JsonNode c = okCloseFifo("BTC", 1, "9", "CALL", "15.10", "2026-11-19T10:00");
        assertThat(covered(c)).containsExactly("A STO 1 18.34 0.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("3.24");
        assertThat(c.get("contracts").asInt()).isEqualTo(1);
        assertThat(lots(optRow(A, C9))).containsExactly("B 2026-11-11T10:00:00 1 37.34 0.00 true");
        assertThat(cash(A)).isEqualTo("1040.58 / 0.00 / 1040.58");
        assertThat(storedLots(c.get("id").asLong())).as("FIFO match stored as the chosen lots (Q257)")
                .containsExactly(ids.get("A"));
    }

    @Test
    @DisplayName("AC9 全部账户：持仓 lots 带 closeDefault；已平仓该行账户甲、被平批次 B；平仓 / 修改 / 删除 → ALL_ACCOUNTS_READ_ONLY；close-lot-choices → ALL_ACCOUNTS_NOT_SUPPORTED")
    void ac9_allAccountsReadOnly() {
        scenarioZ();
        long x = okCloseLots("BTC", "9", "CALL", ls(ids.get("B")), "15.10", "2026-11-19T10:00").get("id").asLong();
        ids.put("X", x);

        JsonNode allRow = optRowOf(okAs(ALL, OPTION_POSITIONS), 1, C9);
        assertThat(lots(allRow)).containsExactly("A 2026-11-10T10:00:00 1 18.34 0.00 true");
        assertThat(allRow).as("ALL row = single-account row (Q53)").isEqualTo(optRow(A, C9));

        JsonNode item = closedItem(ALL, x);
        assertThat(item.get("accountName").asText()).isEqualTo(JIA);
        assertThat(item.get("accountId").asLong()).isEqualTo(1);
        assertThat(covered(item)).containsExactly("B STO 1 37.34 0.00");
        assertThat(item.get("realizedPnl").asText()).isEqualTo("22.24");

        Map<String, Object> before = snapshot();
        accountId = ALL;
        assertGuardError(closeLotsResp("BTC", "9", "CALL", ls(ids.get("A")), null, "1.00", "2026-11-19T11:00"),
                READ_ONLY_CODE, CLOSES);
        assertGuardError(putClose(x, ls(ids.get("A")), null, "15.10", "2026-11-19T10:00"), READ_ONLY_CODE,
                TRADES + "/" + x);
        assertGuardError(exchange(HttpMethod.DELETE, TRADES + "/" + x, null), READ_ONLY_CODE, TRADES + "/" + x);
        assertGuardError(lotChoicesResp(x, null), NOT_SUPPORTED_CODE, TRADES + "/" + x + "/close-lot-choices");
        accountId = A;
        assertThat(snapshot()).isEqualTo(before);
    }
}
