package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-051: boundaries of docs/requirements/FEAT-051-choose-lots-to-close.md and the contract
 * docs/api/FEAT-051.md — non-contiguous choice, Q20 release-before-cash-check with a later lot, FEAT-039 coverage release
 * (CHECKED vs LEGACY lot, a later stock sale made illegal by re-choosing), [] vs absent / null lotTradeIds, field
 * validation and check order, edit of the chosen lots (NO_CHANGES as a set, conflicts with a later close, same-instant
 * entry order), opening-trade edits that invalidate / keep a stored choice, earlier backfills now allowed, FIFO mode and
 * EXP / ASG acting on what is left, close-lot-choices, per-account isolation. See
 * {@link Feat051CloseLotsSystemTestBase}.
 */
@DisplayName("FEAT-051 平仓时指定批次（边界与错误）")
class Feat051CloseLotsBoundarySystemTest extends Feat051CloseLotsSystemTestBase {

    // ============================================================================================== choice shapes

    @Test
    @DisplayName("不连续：P1 2 张、P2 1 张、P3 1 张，只选 P1 + P3（顺序 [P3, P1]）→ 张数 3、冻结 −2,400.00、盈亏 +43.00、按开仓时间返回；剩 P2 且成为 closeDefault")
    void nonContiguousChoice() {
        scenarioP();
        ids.put("P3", openLot("STO", 1, "8", "PUT", "25.00", "2026-11-12T10:00"));
        assertThat(cash(A)).isEqualTo("30076.00 / 3200.00 / 26876.00");

        JsonNode c = okCloseLots("BTC", "8", "PUT", ls(ids.get("P3"), ids.get("P1")), "12.00", "2026-11-19T10:00");
        assertThat(c.get("contracts").asInt()).isEqualTo(3);
        assertThat(c.get("frozenChange").asText()).isEqualTo("-2400.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("43.00");
        assertThat(covered(c)).as("coveredLots ascending by opening time, request order irrelevant")
                .containsExactly("P1 STO 2 30.00 1600.00", "P3 STO 1 25.00 800.00");
        assertThat(cash(A)).isEqualTo("30064.00 / 800.00 / 29264.00");
        JsonNode row = optRow(A, P8);
        assertThat(lots(row)).containsExactly("P2 2026-11-11T10:00:00 1 21.00 800.00 true");
        assertThat(row.get("allowedCloseContracts").toString()).isEqualTo("[1]");
        assertThat(storedLots(c.get("id").asLong())).containsExactlyInAnyOrder(ids.get("P1"), ids.get("P3"));
    }

    @Test
    @DisplayName("Q20 先释放所选批次冻结再查现金：可用 51.00，BTC 900.00 只选 P2（释放 800）→ CASH_INSUFFICIENT 原文；只选 P1（释放 1,600）→ 成功 −870.00，1,551.00/800.00/751.00")
    void releaseBeforeCashCheckFollowsChosenLot() {
        setNow(NOW);
        renameAccount(A, JIA);
        depositId("2400.00", "2026-11-01T10:00");
        ids.put("P1", openLot("STO", 2, "8", "PUT", "30.00", "2026-11-10T10:00"));
        ids.put("P2", openLot("STO", 1, "8", "PUT", "21.00", "2026-11-11T10:00"));
        assertThat(cash(A)).isEqualTo("2451.00 / 2400.00 / 51.00");

        Map<String, Object> before = snapshot();
        assertThat(rejected422(closeLotsResp("BTC", "8", "PUT", ls(ids.get("P2")), null, "900.00", "2026-11-19T10:00"),
                before, "CASH_INSUFFICIENT", CLOSES))
                .isEqualTo("可用现金不足：需要 900.00，可用 51.00 + 释放冻结 800.00 = 851.00");

        JsonNode c = okCloseLots("BTC", "8", "PUT", ls(ids.get("P1")), "900.00", "2026-11-19T10:00");
        assertThat(c.get("frozenChange").asText()).isEqualTo("-1600.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("-870.00");
        assertThat(cash(A)).isEqualTo("1551.00 / 800.00 / 751.00");
    }

    @Test
    @DisplayName("多头 STC 只选较晚的 L2（2 张 −41.30）、实收 50.00：张数 2、盈亏 +8.70、剩 L1；冻结 0")
    void longStcLaterLot() {
        setNow(NOW);
        renameAccount(A, JIA);
        depositId("1000.00", "2026-11-01T10:00");
        ids.put("L1", openLot("BTO", 1, "8", "PUT", "25.65", "2026-11-12T10:00"));
        ids.put("L2", openLot("BTO", 2, "8", "PUT", "41.30", "2026-11-13T10:00"));
        JsonNode c = okCloseLots("STC", "8", "PUT", ls(ids.get("L2")), "50.00", "2026-11-19T10:00");
        assertThat(c.get("contracts").asInt()).isEqualTo(2);
        assertThat(c.get("cashChange").asText()).isEqualTo("50.00");
        assertThat(c.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(covered(c)).containsExactly("L2 BTO 2 -41.30 0.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("8.70");
        assertThat(lots(optRow(A, P8))).containsExactly("L1 2026-11-12T10:00:00 1 -25.65 0.00 true");
        assertThat(cash(A)).isEqualTo("983.05 / 0.00 / 983.05");
    }

    // ============================================================================================== FEAT-039 coverage

    @Test
    @DisplayName("覆盖释放跟随所选批次：LEGACY 批次 L（11-09，当时无股）被选中 → 被覆盖仍 100、可用 0（释放 0，Q79）；另起选受检批次 A → 被覆盖 0、可用 100，L 仍未覆盖")
    void coverageReleaseLegacyVsChecked() {
        setNow(NOW);
        renameAccount(A, JIA);
        depositId("1000.00", "2026-11-01T10:00");
        ids.put("L", legacyCall("15.00", "2026-11-09T10:00"));
        ids.put("ZZQ", transferInId("ZZQ", 100, "800.00", "2026-11-12"));
        ids.put("A", openLot("STO", 1, "9", "CALL", "18.34", "2026-11-13T10:00"));
        assertThat(lotCoverage()).containsExactly("L LEGACY", "A CHECKED");
        assertThat(stock(A, "ZZQ")).isEqualTo("100/100/0");

        okCloseLots("BTC", "9", "CALL", ls(ids.get("L")), "5.00", "2026-11-19T10:00");
        assertThat(stock(A, "ZZQ")).as("LEGACY lot releases 0 shares").isEqualTo("100/100/0");
        assertThat(lotCoverage()).containsExactly("A CHECKED");

        reset();
        ids.clear();
        setNow(NOW);
        renameAccount(A, JIA);
        depositId("1000.00", "2026-11-01T10:00");
        ids.put("L", legacyCall("15.00", "2026-11-09T10:00"));
        ids.put("ZZQ", transferInId("ZZQ", 100, "800.00", "2026-11-12"));
        ids.put("A", openLot("STO", 1, "9", "CALL", "18.34", "2026-11-13T10:00"));
        okCloseLots("BTC", "9", "CALL", ls(ids.get("A")), "5.00", "2026-11-19T10:00");
        assertThat(stock(A, "ZZQ")).as("CHECKED lot releases its 100 shares").isEqualTo("100/0/100");
        assertThat(lotCoverage()).as("L stays LEGACY (short at 11-09)").containsExactly("L LEGACY");
    }

    @Test
    @DisplayName("覆盖 + 重放（Q19）：A 1 张、B 2 张、300 股；BTC 选 B 后卖出 200 股合法；把 BTC 改选 A → 之后持股 100 < 被覆盖 200 → COVERAGE_INSUFFICIENT，什么都不变")
    void rechoosingMakesLaterStockSaleIllegal() {
        setNow(NOW);
        renameAccount(A, JIA);
        depositId("1000.00", "2026-11-01T10:00");
        transferInId("ZZQ", 300, "2400.00", "2026-11-02");
        ids.put("A", openLot("STO", 1, "9", "CALL", "18.34", "2026-11-10T10:00"));
        ids.put("B", openLot("STO", 2, "9", "CALL", "60.00", "2026-11-11T10:00"));
        long x = okCloseLots("BTC", "9", "CALL", ls(ids.get("B")), "20.00", "2026-11-19T10:00").get("id").asLong();
        assertThat(stock(A, "ZZQ")).isEqualTo("300/100/200");
        sellSharesId("ZZQ", 200, "1800.00", "2026-11-19T11:00");
        assertThat(stock(A, "ZZQ")).isEqualTo("100/100/0");

        Map<String, Object> before = snapshot();
        Resp r = putClose(x, ls(ids.get("A")), null, "20.00", "2026-11-19T10:00");
        rejected422(r, before, "COVERAGE_INSUFFICIENT", TRADES + "/" + x);
    }

    // ============================================================================================== request shapes

    @Test
    @DisplayName("lotTradeIds 不带 / null = FIFO 模式（张数必填，缺少 → 张数不能为空）；[] → 请至少选择一笔开仓批次；null 元素 / 0 / −1 → 批次必须是开仓交易 id；\"abc\" / 1.5 → BAD_REQUEST；1001 个 → 最多选择 1000 笔批次")
    void requestShapes() {
        scenarioZ();
        Map<String, Object> before = snapshot();

        ObjectNode noContracts = closeBody("BTC", "9", "CALL", "15.10", "2026-11-19T10:00");
        rejected400(post(CLOSES, noContracts.toString()), before, CLOSES, "contracts=张数不能为空");
        ObjectNode nullLots = closeBody("BTC", "9", "CALL", "15.10", "2026-11-19T10:00");
        nullLots.putNull("lotTradeIds");
        rejected400(post(CLOSES, nullLots.toString()), before, CLOSES, "contracts=张数不能为空");

        for (String raw : List.of("[null]", "[0]", "[-1]")) {
            ObjectNode b = closeBody("BTC", "9", "CALL", "15.10", "2026-11-19T10:00");
            b.set("lotTradeIds", parse(raw));
            rejected400(post(CLOSES, b.toString()), before, CLOSES, "lotTradeIds=批次必须是开仓交易 id");
        }
        for (String raw : List.of("[\"abc\"]", "[1.5]")) {
            ObjectNode b = closeBody("BTC", "9", "CALL", "15.10", "2026-11-19T10:00");
            b.set("lotTradeIds", parse(raw));
            Resp r = post(CLOSES, b.toString());
            assertErrorShape(r, 400, "BAD_REQUEST", CLOSES);
            assertThat(snapshot()).isEqualTo(before);
        }
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 1001; i++) {
            many.add(i);
        }
        rejected400(closeLotsResp("BTC", "9", "CALL", many, null, "15.10", "2026-11-19T10:00"), before, CLOSES,
                "lotTradeIds=最多选择 1000 笔批次");

        // null lotTradeIds + contracts = FIFO mode, same as absent
        ObjectNode fifo = closeBody("BTC", "9", "CALL", "15.10", "2026-11-19T10:00");
        fifo.putNull("lotTradeIds");
        fifo.put("contracts", 1);
        Resp ok = post(CLOSES, fifo.toString());
        assertThat(ok.status()).as("%s", ok.body()).isEqualTo(201);
        assertThat(covered(ok.body())).containsExactly("A STO 1 18.34 0.00");
    }

    @Test
    @DisplayName("选择模式 contracts 可省略；带了且相等 → 允许；结果与 FIFO 模式（录入交易页）的同一选择完全相同（除 createdAt）")
    void chosenModeEqualsFifoModeForDefault() {
        scenarioZ();
        Resp withCount = closeLotsResp("BTC", "9", "CALL", ls(ids.get("A")), 1, "15.10", "2026-11-19T10:00");
        assertThat(withCount.status()).as("%s", withCount.body()).isEqualTo(201);
        ObjectNode chosen = withCount.body().deepCopy();
        Map<String, Object> chosenState = stateWithoutTimes();

        reset();
        ids.clear();
        scenarioZ();
        ObjectNode fifo = okCloseFifo("BTC", 1, "9", "CALL", "15.10", "2026-11-19T10:00").deepCopy();
        chosen.remove("createdAt");
        fifo.remove("createdAt");
        assertThat(fifo).isEqualTo(chosen);
        assertThat(stateWithoutTimes()).isEqualTo(chosenState);
    }

    @Test
    @DisplayName("检查顺序：字段 → 持仓 / 方向 → 批次（a 不属于 → b 重复 → c 未开仓 → d 已平 → e 张数）；[B, #99999] 在 11-10 15:00 报 #99999；平仓 / 别的合约的开仓 / 不存在 → a")
    void checkOrder() {
        scenarioZ();
        long a = ids.get("A");
        long b = ids.get("B");
        ids.put("P8", openLot("STO", 1, "8", "PUT", "21.00", "2026-11-12T10:00"));
        long x = okCloseLots("BTC", "9", "CALL", ls(a), "15.10", "2026-11-19T10:00").get("id").asLong();
        Map<String, Object> before = snapshot();

        // 2 before 3
        assertThat(rejected422(closeLotsResp("BTC", "9", "CALL", ls(a), null, "1.00", "2026-11-09T10:00"), before,
                "NO_POSITION_TO_CLOSE", CLOSES)).isEqualTo("该合约（ZZQ 2026-12-18 9 C）在成交时没有持仓，无法平仓");
        assertThat(rejected422(closeLotsResp("STC", "9", "CALL", ls(b), null, "1.00", "2026-11-19T11:00"), before,
                "POSITION_DIRECTION_CONFLICT", CLOSES)).startsWith("该合约（ZZQ 2026-12-18 9 C）在成交时为空头持仓");
        // a before c, even for a later element
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(b, 99999L), null, "1.00", "2026-11-10T15:00"),
                before, CLOSES)).isEqualTo("所选批次 #99999 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易");
        // a: a close record, an opening of another contract
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(x), null, "1.00", "2026-11-19T11:00"), before,
                CLOSES)).isEqualTo("所选批次 #" + x + " 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易");
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(ids.get("P8")), null, "1.00",
                "2026-11-19T11:00"), before, CLOSES))
                .isEqualTo("所选批次 #" + ids.get("P8") + " 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易");
        // b before d: [A, A] where A is already closed → 重复
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(a, a), null, "1.00", "2026-11-19T11:00"),
                before, CLOSES)).isEqualTo("所选批次（2026-11-10 10:00 STO 1 张）重复选择");
        // d before e
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(a), 5, "1.00", "2026-11-19T11:00"), before,
                CLOSES)).isEqualTo("所选批次（2026-11-10 10:00 STO 1 张）已被 2026-11-19 10:00 的 BTC 1 张平掉");
        // field errors first (400) even with an invalid lot
        rejected400(closeLotsResp("BTC", "9", "CALL", ls(99999L), null, "0", "2026-11-19T11:00"), before, CLOSES,
                "netAmount=净额必须大于 0");
    }

    @Test
    @DisplayName("到期日规则不变（Q83）：今天到期的合约选批次平仓，成交 16:01 → tradedAtLocal 字段错误；16:00 成功")
    void expirationDayCutoff() {
        setNow("2026-11-20T16:30");
        renameAccount(A, JIA);
        depositId("10000.00", "2026-11-01T10:00");
        long q1 = postOpen("STO", "8", "PUT", "2026-11-20", "10.00", "2026-11-16T10:00");
        long q2 = postOpen("STO", "8", "PUT", "2026-11-20", "12.00", "2026-11-17T10:00");
        ObjectNode late = closeBody("BTC", "8", "PUT", "1.00", "2026-11-20T16:01");
        late.put("expirationDate", "2026-11-20");
        longs(late, "lotTradeIds", ls(q2));
        Resp r = post(CLOSES, late.toString());
        assertErrorShape(r, 400, "VALIDATION_ERROR", CLOSES);
        assertThat(detailFields(r)).containsExactly("tradedAtLocal");
        late.put("tradedAtLocal", "2026-11-20T16:00");
        Resp ok = post(CLOSES, late.toString());
        assertThat(ok.status()).as("%s", ok.body()).isEqualTo(201);
        assertThat(ok.body().get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(q2);
        assertThat(q1).isLessThan(q2);
    }

    // ============================================================================================== edit / delete

    @Test
    @DisplayName("修改平仓：同一集合（顺序不同）→ NO_CHANGES；不带 lotTradeIds = 批次不变（只改净额成功）；只带 contracts ≠ 批次之和 → CLOSE_LOTS_INVALID；改时间使所选批次未开仓 → 原文；[] → 400")
    void editCloseShapes() {
        scenarioP();
        long p1 = ids.get("P1");
        long p2 = ids.get("P2");
        long x = okCloseLots("BTC", "8", "PUT", ls(p1, p2), "20.00", "2026-11-19T10:00").get("id").asLong();
        String path = TRADES + "/" + x;
        Map<String, Object> before = snapshot();

        assertThat(rejected422(putClose(x, ls(p2, p1), null, "20.00", "2026-11-19T10:00"), before, "NO_CHANGES",
                path)).isEqualTo("没有修改");
        assertThat(rejectedLots(putClose(x, null, 2, "20.00", "2026-11-19T10:00"), before, path))
                .isEqualTo("平仓张数 2 张与所选批次张数之和 3 张不一致");
        assertThat(rejectedLots(putClose(x, ls(p1, p2), null, "20.00", "2026-11-10T15:00"), before, path))
                .isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓");
        rejected400(putClose(x, List.of(), null, "20.00", "2026-11-19T10:00"), before, path,
                "lotTradeIds=请至少选择一笔开仓批次");

        JsonNode t = okPutClose(x, null, "25.00", "2026-11-19T10:00");
        assertThat(covered(t)).containsExactly("P1 STO 2 30.00 1600.00", "P2 STO 1 21.00 800.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("26.00");
        assertThat(texts(auditItems().get(0).get("changes"))).containsExactly("净额：20.00 → 25.00");
        assertThat(selectedLots(auditItems().get(0).get("before")))
                .containsExactly("P1 STO 2026-11-10T10:00:00 2", "P2 STO 2026-11-11T10:00:00 1");
        assertThat(selectedLots(auditItems().get(0).get("after")))
                .containsExactly("P1 STO 2026-11-10T10:00:00 2", "P2 STO 2026-11-11T10:00:00 1");
    }

    @Test
    @DisplayName("修改平仓改选之后另一笔平仓已选的批次 → 修改后，… 已被更早的平仓平掉；删除之后那笔平仓后可以改选；审计 被平批次 多批次用、连接")
    void editCloseConflictsWithLaterClose() {
        scenarioZ();
        long a = ids.get("A");
        long b = ids.get("B");
        long x = okCloseLots("BTC", "9", "CALL", ls(a), "10.00", "2026-11-15T10:00").get("id").asLong();
        long y = okCloseLots("BTC", "9", "CALL", ls(b), "15.10", "2026-11-19T10:00").get("id").asLong();
        Map<String, Object> before = snapshot();
        assertThat(rejectedLots(putClose(x, ls(b), null, "10.00", "2026-11-15T10:00"), before, TRADES + "/" + x))
                .isEqualTo("修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-11 10:00 STO 1 张 已被更早的平仓平掉");

        assertThat(deleteTrade(y).status()).isEqualTo(204);
        assertThat(lots(optRow(A, C9))).as("deleted close's lot is held again")
                .containsExactly("B 2026-11-11T10:00:00 1 37.34 0.00 true");
        assertThat(storedLots(y)).isEmpty();
        JsonNode del = auditItems().get(0);
        assertThat(del.get("operation").asText()).isEqualTo("DELETE");
        assertThat(selectedLots(del.get("before"))).containsExactly("B STO 2026-11-11T10:00:00 1");

        JsonNode t = okPutClose(x, ls(a, b), "10.00", "2026-11-15T10:00");
        assertThat(t.get("contracts").asInt()).isEqualTo(2);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("45.68");
        assertThat(texts(auditItems().get(0).get("changes"))).containsExactly(
                "被平批次：2026-11-10 10:00 1 张 → 2026-11-10 10:00 1 张、2026-11-11 10:00 1 张", "张数：1 → 2");
        assertThat(stock(A, "ZZQ")).isEqualTo("200/0/200");
    }

    @Test
    @DisplayName("同一时间按录入顺序（Q19）：11-19 10:00 的 BTC 先录入，之后录入的同一时间开仓 D 不能被它选中（还未开仓）；同一时间之后录入的新平仓可以选 D")
    void sameInstantEntryOrder() {
        scenarioZ();
        long x = okCloseLots("BTC", "9", "CALL", ls(ids.get("A")), "15.10", "2026-11-19T10:00").get("id").asLong();
        ids.put("D", openLot("STO", 1, "9", "CALL", "30.00", "2026-11-19T10:00"));
        Map<String, Object> before = snapshot();
        assertThat(rejectedLots(putClose(x, ls(ids.get("D")), null, "15.10", "2026-11-19T10:00"), before,
                TRADES + "/" + x)).isEqualTo("所选批次（2026-11-19 10:00 STO 1 张）在成交时间 2026-11-19 10:00 还未开仓");
        JsonNode y = okCloseLots("BTC", "9", "CALL", ls(ids.get("D")), "5.00", "2026-11-19T10:00");
        assertThat(y.get("realizedPnl").asText()).isEqualTo("25.00");
    }

    @Test
    @DisplayName("开仓修改：张数改动 → 张数与平仓不一致原文；只改净额 → 允许，所选平仓盈亏随之变（+24.90）；时间改但仍早于平仓 → 允许；在之后补录更早开仓 → 允许，不改变已有平仓")
    void openingEditsAgainstStoredChoice() {
        scenarioP();
        long p2 = ids.get("P2");
        long x = okCloseLots("BTC", "8", "PUT", ls(p2), "9.65", "2026-11-19T10:00").get("id").asLong();
        Map<String, Object> before = snapshot();
        assertThat(rejectedLots(putOpen(p2, 2, "21.00", "2026-11-11T10:00"), before, TRADES + "/" + p2))
                .isEqualTo("修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 8 P 选中的批次 2026-11-11 10:00 STO 2 张 张数与平仓不一致（所选批次共 2 张，平仓 1 张）");

        assertThat(putOpen(p2, 1, "34.55", "2026-11-12T10:00").status()).isEqualTo(200);
        JsonNode c = tradeById(x);
        assertThat(covered(c)).containsExactly("P2 STO 1 34.55 800.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("24.90");
        assertThat(c.get("frozenChange").asText()).isEqualTo("-800.00");
        assertThat(auditItems().get(0).get("sideEffects")).as("no BTC frozen side effect any more").isEmpty();

        // an earlier backfilled open no longer re-matches the existing close (was CLOSE_NOT_WHOLE_LOTS / re-match)
        ids.put("P0", openLot("STO", 1, "8", "PUT", "40.00", "2026-11-05T10:00"));
        JsonNode again = tradeById(x);
        assertThat(covered(again)).containsExactly("P2 STO 1 34.55 800.00");
        assertThat(again.get("realizedPnl").asText()).isEqualTo("24.90");
        assertThat(lots(optRow(A, P8))).containsExactly(
                "P0 2026-11-05T10:00:00 1 40.00 800.00 true",
                "P1 2026-11-10T10:00:00 2 30.00 1600.00 false");
        assertFrozenConsistent(A);
    }

    // ============================================================================================== other events (Q260)

    @Test
    @DisplayName("FIFO 模式在不连续的剩余批次上：A、B、D 三笔，先选 B，再 FIFO 1 张 → A，再 FIFO 1 张 → D；allowedCloseContracts [1, 2]")
    void fifoModeOnRemainder() {
        scenarioZ();
        transferInId("ZZQ", 100, "800.00", "2026-11-12");
        ids.put("D", openLot("STO", 1, "9", "CALL", "30.00", "2026-11-12T10:00"));
        okCloseLots("BTC", "9", "CALL", ls(ids.get("B")), "15.10", "2026-11-17T10:00");
        JsonNode row = optRow(A, C9);
        assertThat(row.get("allowedCloseContracts").toString()).isEqualTo("[1,2]");
        assertThat(lots(row)).containsExactly("A 2026-11-10T10:00:00 1 18.34 0.00 true",
                "D 2026-11-12T10:00:00 1 30.00 0.00 false");
        assertThat(covered(okCloseFifo("BTC", 1, "9", "CALL", "1.00", "2026-11-18T10:00")))
                .containsExactly("A STO 1 18.34 0.00");
        assertThat(covered(okCloseFifo("BTC", 1, "9", "CALL", "1.00", "2026-11-19T10:00")))
                .containsExactly("D STO 1 30.00 0.00");
        assertThat(optRow(A, C9)).isNull();
    }

    @Test
    @DisplayName("FIFO 模式补录选到之后平仓已选的批次 → 第 4 步拒绝（不排除之后已选的批次）：先选 A 于 11-19，再 FIFO 补录 11-15 1 张 → 补录后，… 已被更早的平仓平掉")
    void fifoBackfillHitsLaterChoice() {
        scenarioZ();
        okCloseFifo("BTC", 1, "9", "CALL", "15.10", "2026-11-19T10:00");
        Map<String, Object> before = snapshot();
        assertThat(rejectedLots(closeFifoResp("BTC", 1, "9", "CALL", "10.00", "2026-11-15T10:00"), before, CLOSES))
                .isEqualTo("补录后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-10 10:00 STO 1 张 已被更早的平仓平掉");
    }

    @Test
    @DisplayName("Q260 call 被指派作用于剩余批次：AC2 之后（B 已平）指派 1 张 → 指派的是 A；之后 C9 没有持仓")
    void callAssignmentTakesRemainingLot() {
        scenarioZ();
        okCloseLots("BTC", "9", "CALL", ls(ids.get("B")), "15.10", "2026-11-19T10:00");
        Resp r = assignResp("9", "CALL", 1, "2026-11-19");
        assertThat(r.status()).as("%s", r.body()).isEqualTo(201);
        assertThat(covered(r.body())).containsExactly("A STO 1 18.34 0.00");
        assertThat(optRow(A, C9)).isNull();
    }

    @Test
    @DisplayName("Q260 补录被指派（FIFO）平掉之后平仓已选的批次：P 中 BTC 选 P1（11-19），补录 11-18 指派 2 张 → 补录后，… 已被更早的被指派平掉；指派 11-19 之后 → 指派的是 P2")
    void assignmentBackfillHitsChosenLot() {
        scenarioP();
        okCloseLots("BTC", "8", "PUT", ls(ids.get("P1")), "10.00", "2026-11-19T10:00");
        Map<String, Object> before = snapshot();
        assertThat(rejectedLots(assignResp("8", "PUT", 2, "2026-11-18"), before, TRADES + "/assignments"))
                .isEqualTo("补录后，2026-11-19 10:00 BTC 2 张 ZZQ 2026-12-18 8 P 选中的批次 2026-11-10 10:00 STO 2 张 已被更早的被指派平掉");
        Resp ok = assignResp("8", "PUT", 1, "2026-11-19");
        assertThat(ok.status()).as("%s", ok.body()).isEqualTo(201);
        assertThat(covered(ok.body())).containsExactly("P2 STO 1 21.00 800.00");
    }

    @Test
    @DisplayName("Q260 到期作废作用于剩余批次：今天到期的 Q1、Q2，BTC 选 Q1；17:45 作废 1 张 → 作废的是 Q2")
    void expireTakesRemainingLot() {
        setNow("2026-11-20T10:00");
        renameAccount(A, JIA);
        depositId("10000.00", "2026-11-01T10:00");
        ids.put("Q1", postOpen("STO", "8", "PUT", "2026-11-20", "10.00", "2026-11-16T10:00"));
        ids.put("Q2", postOpen("STO", "8", "PUT", "2026-11-20", "12.00", "2026-11-17T10:00"));
        ObjectNode c = closeBody("BTC", "8", "PUT", "1.00", "2026-11-19T10:00");
        c.put("expirationDate", "2026-11-20");
        longs(c, "lotTradeIds", ls(ids.get("Q1")));
        assertThat(post(CLOSES, c.toString()).status()).isEqualTo(201);

        setNow("2026-11-20T17:45");
        ObjectNode e = json.createObjectNode();
        e.put("symbol", "ZZQ");
        e.put("expirationDate", "2026-11-20");
        e.put("strike", "8");
        e.put("optionType", "PUT");
        e.put("contracts", 1);
        e.putNull("note");
        Resp r = post(TRADES + "/expirations", e.toString());
        assertThat(r.status()).as("%s", r.body()).isEqualTo(201);
        assertThat(covered(r.body())).containsExactly("Q2 STO 1 12.00 800.00");
        assertThat(r.body().get("realizedPnl").asText()).isEqualTo("12.00");
    }

    // ============================================================================================== close-lot-choices

    @Test
    @DisplayName("close-lot-choices：本笔当前所选 selected=true；排除之后其它平仓已选的批次；?tradedAtLocal 早于 B → 只有 A；早于 A → 空；错误 abc / 404 / 别的账户 / 开仓 / 时间格式")
    void closeLotChoices() {
        scenarioZ();
        accountYiWithC();
        long a = ids.get("A");
        long b = ids.get("B");
        long x = okCloseLots("BTC", "9", "CALL", ls(a), "15.10", "2026-11-19T10:00").get("id").asLong();

        JsonNode body = lotChoices(x, null);
        assertThat(body.get("closeTradeId").asLong()).isEqualTo(x);
        assertThat(body.get("tradedAt").asText()).isEqualTo("2026-11-19T15:00:00Z");
        assertThat(body.get("tradedAtLocal").asText()).isEqualTo("2026-11-19T10:00:00");
        assertThat(choices(body)).containsExactly(
                "A 2026-11-10T10:00:00 1 18.34 0.00 true",
                "B 2026-11-11T10:00:00 1 37.34 0.00 false");
        assertThat(choices(lotChoices(x, "2026-11-10T15:00"))).containsExactly("A 2026-11-10T10:00:00 1 18.34 0.00 true");
        assertThat(lotChoices(x, "2026-11-10T15:00").get("tradedAtLocal").asText()).isEqualTo("2026-11-10T15:00:00");
        assertThat(lotChoices(x, "2026-11-09T10:00").get("lots")).isEmpty();

        // a later close takes B → B no longer offered to x; y sees only its own B (A taken by the earlier x)
        long y = okCloseLots("BTC", "9", "CALL", ls(b), "15.10", "2026-11-19T11:00").get("id").asLong();
        assertThat(choices(lotChoices(x, null))).containsExactly("A 2026-11-10T10:00:00 1 18.34 0.00 true");
        assertThat(choices(lotChoices(y, null))).containsExactly("B 2026-11-11T10:00:00 1 37.34 0.00 true");

        String base = TRADES + "/";
        assertErrorShape(get(base + "abc/close-lot-choices"), 400, "BAD_REQUEST", base + "abc/close-lot-choices");
        assertErrorShape(lotChoicesResp(999999, null), 404, "NOT_FOUND", base + "999999/close-lot-choices");
        Resp open = lotChoicesResp(a, null);
        assertErrorShape(open, 422, "BUSINESS_RULE_VIOLATION", base + a + "/close-lot-choices");
        assertThat(open.body().get("message").asText()).isEqualTo("只有平仓（BTC / STC）可以选择批次");
        Resp badTime = lotChoicesResp(x, "2026-13-01T10:00");
        assertErrorShape(badTime, 400, "BAD_REQUEST", base + x + "/close-lot-choices");
        assertThat(detailFields(badTime)).containsExactly("tradedAtLocal");
        accountId = B;
        assertErrorShape(lotChoicesResp(x, null), 404, "NOT_FOUND", base + x + "/close-lot-choices");
        accountId = A;
    }

    @Test
    @DisplayName("按账户独立（Q53）：乙选甲的批次 A → 不是本账户的开仓交易；乙 PUT 甲的平仓 → 404")
    void perAccount() {
        scenarioZ();
        accountYiWithC();
        long x = okCloseLots("BTC", "9", "CALL", ls(ids.get("A")), "15.10", "2026-11-19T10:00").get("id").asLong();
        Map<String, Object> before = snapshot();
        accountId = B;
        assertThat(rejectedLots(closeLotsResp("BTC", "9", "CALL", ls(ids.get("A")), null, "1.00", "2026-11-19T10:00"),
                before, CLOSES)).isEqualTo("所选批次 #" + ids.get("A") + " 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易");
        assertErrorShape(putClose(x, ls(ids.get("C")), null, "15.10", "2026-11-19T10:00"), 404, "NOT_FOUND",
                TRADES + "/" + x);
        accountId = A;
        assertThat(snapshot()).isEqualTo(before);
    }

    // ============================================================================================== helpers

    long postOpen(String action, String strike, String type, String expiration, String net, String at) {
        ObjectNode body = trade(action, 1, "ZZQ", expiration, strike, type, net, at);
        Resp r = post(TRADES, body.toString());
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** FEAT-039 e2e-only LEGACY STO call (1 contract ZZQ 2026-12-18 9 C). */
    long legacyCall(String net, String at) {
        ObjectNode body = trade("STO", 1, "ZZQ", EXP, "9", "CALL", net, at);
        Resp r = post(LEGACY, body.toString());
        assertThat(r.status()).as("POST legacy %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** "label coverage" of every lot of the 9C row. */
    List<String> lotCoverage() {
        List<String> out = new ArrayList<>();
        JsonNode row = optRow(A, C9);
        if (row != null) {
            row.get("lots").forEach(l -> out.add(label(l.get("tradeId").asLong()) + " " + l.get("coverage").asText()));
        }
        return out;
    }

    static List<String> detailFields(Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("details").forEach(d -> out.add(d.get("field").asText()));
        return out;
    }

    /** Cash, positions, ledger, trades (createdAt dropped) and stored lots — for comparing two equivalent runs. */
    Map<String, Object> stateWithoutTimes() {
        Map<String, Object> w = new java.util.LinkedHashMap<>();
        w.put("cash", okAs(A, CASH_SUMMARY));
        w.put("positions", okAs(A, OPTION_POSITIONS));
        w.put("stocks", okAs(A, STOCK_POSITIONS));
        JsonNode trades = okAs(A, TRADES + "?size=100").deepCopy();
        trades.get("items").forEach(i -> ((ObjectNode) i).remove("createdAt"));
        w.put("trades", trades);
        JsonNode ledger = okAs(A, "/api/cash/ledger?size=100").deepCopy();
        ledger.get("items").forEach(i -> ((ObjectNode) i).remove("createdAt"));
        w.put("ledger", ledger);
        w.put("lots", closeLotRows());
        return w;
    }
}
