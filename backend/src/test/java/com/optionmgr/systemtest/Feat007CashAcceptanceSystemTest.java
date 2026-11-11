package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FEAT-007 acceptance criteria 1–8, 10, 11, 13 end-to-end over HTTP, plus Q19 replay boundaries that go
 * beyond the examples in the requirement (first violating point, later deposits do not rescue an earlier
 * negative point, same-instant ordering against existing entries).
 */
class Feat007CashAcceptanceSystemTest extends CashSystemTestBase {

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC1 空账户：汇总 0.00/0.00/0.00，流水为空且分页字段符合契约")
    void ac1_emptyAccount() {
        assertSummary("0.00", "0.00", "0.00");
        JsonNode page = ledger();
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalItems").asLong()).isZero();
        assertThat(page.get("totalPages").asInt()).isZero();
    }

    // ------------------------------------------------------------------ AC 2, 3, 4, 11, 12 (backend part)

    @Test
    @DisplayName("AC2→AC3→AC4→AC11 连续场景：入金、出金、超额出金被拒且不落库、恰好取完到 0")
    void ac2to4_depositWithdrawOverdrawThenExact() {
        // AC 2
        JsonNode dep = ok("DEPOSIT", "30000.00", "2026-11-02T10:00", "初始资金");
        assertThat(dep.get("id").isNumber()).isTrue();
        assertThat(dep.get("type").asText()).isEqualTo("DEPOSIT");
        assertThat(dep.get("amount").asText()).isEqualTo("30000.00");
        assertThat(dep.get("amount").isTextual()).isTrue();
        assertThat(dep.get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00Z");
        assertThat(dep.get("remark").asText()).isEqualTo("初始资金");
        assertThat(dep.get("ledgerEntryId").isNumber()).isTrue();
        assertThat(java.time.Instant.parse(dep.get("createdAt").asText())).isEqualTo(NOW);
        assertSummary("30000.00", "0.00", "30000.00");

        JsonNode first = ledger().get("items").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(dep.get("ledgerEntryId").asLong());
        assertThat(first.get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00Z");
        assertThat(first.get("sourceType").asText()).isEqualTo("DEPOSIT");
        assertThat(first.get("sourceId").asLong()).isEqualTo(dep.get("id").asLong());
        assertThat(first.get("amount").asText()).isEqualTo("30000.00");
        assertThat(first.get("balanceAfter").asText()).isEqualTo("30000.00");
        assertThat(first.has("description")).as("null fields are present, not omitted").isTrue();
        assertThat(first.get("description").isNull()).isTrue();
        assertThat(first.get("remark").asText()).isEqualTo("初始资金");

        // AC 3
        JsonNode wd = ok("WITHDRAWAL", "2500.50", "2026-11-03T09:30", null);
        assertThat(wd.get("amount").asText()).as("transfer amount is positive").isEqualTo("2500.50");
        assertThat(wd.get("occurredAt").asText()).isEqualTo("2026-11-03T14:30:00Z");
        assertThat(wd.get("remark").isNull()).isTrue();
        assertSummary("27499.50", "0.00", "27499.50");
        assertThat(ledgerLines()).containsExactly(
                "2026-11-03T14:30:00Z WITHDRAWAL -2500.50 27499.50",
                "2026-11-02T15:00:00Z DEPOSIT 30000.00 30000.00");

        // AC 4: one cent too much -> rejected, nothing stored
        String msg = rejectedWithdrawal("27499.51", "2026-11-10T09:00");
        assertThat(msg).isEqualTo("可用现金不足：需要 27,499.51，可用 27,499.50");
        assertSummary("27499.50", "0.00", "27499.50");
        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(transferRowCount()).isEqualTo(2);

        // AC 4: exact amount -> 0.00 (total cash exactly 0 is allowed)
        withdraw("27499.50", "2026-11-10T09:00");
        assertSummary("0.00", "0.00", "0.00");
        assertThat(ledger().get("items").get(0).get("balanceAfter").asText()).isEqualTo("0.00");
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("AC5 补录出金：本笔够但 11-05 会变负 → 拒绝并指出时点；1,000.00 成功到 0")
    void ac5_backdatedWithdrawalReplay() {
        deposit("10000.00", "2026-11-02T10:00");
        withdraw("9000.00", "2026-11-05T10:00");
        assertSummary("1000.00", "0.00", "1000.00");

        String msg = rejectedWithdrawal("1500.00", "2026-11-03T10:00");
        assertThat(msg).isEqualTo("可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00");
        assertSummary("1000.00", "0.00", "1000.00");
        assertThat(ledgerCount()).isEqualTo(2);

        withdraw("1000.00", "2026-11-03T10:00");
        assertSummary("0.00", "0.00", "0.00");
        assertThat(ledgerLines()).containsExactly(
                "2026-11-05T15:00:00Z WITHDRAWAL -9000.00 0.00",
                "2026-11-03T15:00:00Z WITHDRAWAL -1000.00 9000.00",
                "2026-11-02T15:00:00Z DEPOSIT 10000.00 10000.00");
    }

    @Test
    @DisplayName("AC5 补录出金早于第一笔入金 → 拒绝，可用 0.00")
    void ac5_withdrawalBeforeFirstDepositIsRejected() {
        deposit("10000.00", "2026-11-02T10:00");
        withdraw("9000.00", "2026-11-05T10:00");

        String msg = rejectedWithdrawal("100.00", "2026-11-01T10:00");
        assertThat(msg).isEqualTo("可用现金不足：需要 100.00，可用 0.00");
        assertSummary("1000.00", "0.00", "1000.00");
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Q19 边界：取第一个变负的时点（多个后续出金）")
    void q19_reportsFirstNegativePoint() {
        deposit("1000.00", "2026-11-02T10:00");
        withdraw("300.00", "2026-11-04T10:00");
        withdraw("300.00", "2026-11-05T10:00");

        // 1000-600=400, -300=100, -300=-200 -> first negative point is 11-05
        assertThat(rejectedWithdrawal("600.00", "2026-11-03T10:00"))
                .isEqualTo("可用现金不足：需要 600.00；2026-11-05 10:00 时可用现金将变为 −200.00");
        // 1000-800=200, -300=-100 (first), -300=-400 -> must report 11-04, not 11-05
        assertThat(rejectedWithdrawal("800.00", "2026-11-03T10:00"))
                .isEqualTo("可用现金不足：需要 800.00；2026-11-04 10:00 时可用现金将变为 −100.00");
        assertThat(ledgerCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Q19 边界：后面的入金不能“救回”中间某个变负的时点")
    void q19_laterDepositDoesNotRescueIntermediateNegativePoint() {
        deposit("1000.00", "2026-11-02T10:00");
        withdraw("1000.00", "2026-11-05T10:00");
        deposit("500.00", "2026-11-06T10:00");
        assertSummary("500.00", "0.00", "500.00");

        // final total would be 0.00, but at 11-05 it would be -500.00
        assertThat(rejectedWithdrawal("500.00", "2026-11-03T10:00"))
                .isEqualTo("可用现金不足：需要 500.00；2026-11-05 10:00 时可用现金将变为 −500.00");
        // after the last deposit it is fine
        withdraw("500.00", "2026-11-06T10:00");
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("Q19 边界：与已有出金同一时间补录，排在已有记录之后检查")
    void q19_sameInstantAsExistingWithdrawal() {
        deposit("1000.00", "2026-11-02T10:00");
        withdraw("600.00", "2026-11-04T10:00");
        // inserted after the existing 11-04 withdrawal: 400 available right before it
        assertThat(rejectedWithdrawal("500.00", "2026-11-04T10:00"))
                .isEqualTo("可用现金不足：需要 500.00，可用 400.00");
        withdraw("400.00", "2026-11-04T10:00");
        assertSummary("0.00", "0.00", "0.00");
    }

    // ------------------------------------------------------------------ AC 6

    @Test
    @DisplayName("AC6 补录历史入金：之后各条余额重新累计")
    void ac6_backdatedDepositRecomputesBalances() {
        JsonNode later = deposit("10000.00", "2026-11-05T10:00");
        assertThat(ledgerLines()).containsExactly("2026-11-05T15:00:00Z DEPOSIT 10000.00 10000.00");

        JsonNode earlier = deposit("5000.00", "2026-11-02T10:00");
        assertSummary("15000.00", "0.00", "15000.00");
        assertThat(ledgerLines()).containsExactly(
                "2026-11-05T15:00:00Z DEPOSIT 10000.00 15000.00",
                "2026-11-02T15:00:00Z DEPOSIT 5000.00 5000.00");
        // ids keep entry order (earlier-in-time entry was entered later)
        JsonNode items = ledger().get("items");
        assertThat(items.get(0).get("sourceId").asLong()).isEqualTo(later.get("id").asLong());
        assertThat(items.get(1).get("sourceId").asLong()).isEqualTo(earlier.get("id").asLong());
        assertThat(items.get(1).get("id").asLong()).isGreaterThan(items.get(0).get("id").asLong());
    }

    @Test
    @DisplayName("补录入金到最早记录之前：总是允许（含出金把余额扣到 0 之后）")
    void backdatedDepositBeforeEverythingIsAlwaysAllowed() {
        deposit("100.00", "2026-11-05T10:00");
        withdraw("100.00", "2026-11-06T10:00");
        deposit("1.00", "2020-01-01T00:00");
        assertSummary("1.00", "0.00", "1.00");
        assertThat(ledgerLines()).containsExactly(
                "2026-11-06T15:00:00Z WITHDRAWAL -100.00 1.00",
                "2026-11-05T15:00:00Z DEPOSIT 100.00 101.00",
                "2020-01-01T05:00:00Z DEPOSIT 1.00 1.00");
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("AC7 同一时间多条：按录入顺序（入金先录，出金后录）")
    void ac7_sameInstantEntryOrder() {
        deposit("1000.00", "2026-11-03T10:00");
        withdraw("1000.00", "2026-11-03T10:00");
        assertThat(ledgerLines()).containsExactly(
                "2026-11-03T15:00:00Z WITHDRAWAL -1000.00 0.00",
                "2026-11-03T15:00:00Z DEPOSIT 1000.00 1000.00");
        assertSummary("0.00", "0.00", "0.00");

        // more entries at the very same instant keep stacking in entry order
        deposit("500.00", "2026-11-03T10:00");
        withdraw("200.00", "2026-11-03T10:00");
        assertThat(ledgerLines()).containsExactly(
                "2026-11-03T15:00:00Z WITHDRAWAL -200.00 300.00",
                "2026-11-03T15:00:00Z DEPOSIT 500.00 500.00",
                "2026-11-03T15:00:00Z WITHDRAWAL -1000.00 0.00",
                "2026-11-03T15:00:00Z DEPOSIT 1000.00 1000.00");
    }

    @Test
    @DisplayName("AC7 空库先录出金被拒；之后同一时间入金也不会让它“排到后面”")
    void ac7_withdrawalFirstIsRejectedAndNotRetroactivelyAccepted() {
        assertThat(rejectedWithdrawal("1000.00", "2026-11-03T10:00"))
                .isEqualTo("可用现金不足：需要 1,000.00，可用 0.00");
        deposit("1000.00", "2026-11-03T10:00");
        assertThat(ledgerLines()).containsExactly("2026-11-03T15:00:00Z DEPOSIT 1000.00 1000.00");
        assertThat(transferRowCount()).isEqualTo(1);
        assertSummary("1000.00", "0.00", "1000.00");
    }

    @Test
    @DisplayName("同一分钟但不同秒：按时间（秒）排序，而非录入顺序")
    void secondsPrecisionOrdersBeforeEntryOrder() {
        deposit("100.00", "2026-11-03T10:00:30");
        deposit("50.00", "2026-11-03T10:00:10");
        assertThat(ledgerLines()).containsExactly(
                "2026-11-03T15:00:30Z DEPOSIT 100.00 150.00",
                "2026-11-03T15:00:10Z DEPOSIT 50.00 50.00");
        // a withdrawal at :20 sees only the :10 deposit
        assertThat(rejectedWithdrawal("60.00", "2026-11-03T10:00:20"))
                .isEqualTo("可用现金不足：需要 60.00，可用 50.00");
    }

    // ------------------------------------------------------------------ AC 8

    @Test
    @DisplayName("AC8 美东时间换算：夏令时、标准时间、DST 切换当天、等于当前时间")
    void ac8_newYorkTimeConversion() {
        assertThat(deposit("1.00", "2026-07-15T10:30").get("occurredAt").asText()).isEqualTo("2026-07-15T14:30:00Z");
        assertThat(deposit("1.00", "2026-11-02T10:00").get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00Z");
        // DST start gap: 02:30 does not exist -> 03:30 EDT
        assertThat(deposit("1.00", "2026-03-08T02:30").get("occurredAt").asText()).isEqualTo("2026-03-08T07:30:00Z");
        // DST end overlap: 01:30 happens twice -> earlier (EDT)
        assertThat(deposit("1.00", "2026-11-01T01:30").get("occurredAt").asText()).isEqualTo("2026-11-01T05:30:00Z");
        // seconds are accepted
        assertThat(deposit("1.00", "2026-11-02T10:00:15").get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:15Z");
        // BUG-001 regression: a real leap day and month-end days are accepted unchanged
        assertThat(deposit("1.00", "2024-02-29T10:00").get("occurredAt").asText()).isEqualTo("2024-02-29T15:00:00Z");
        assertThat(deposit("1.00", "2026-04-30T23:59:59").get("occurredAt").asText()).isEqualTo("2026-05-01T03:59:59Z");
        // exactly "now" (09:45 NY) is allowed
        assertThat(deposit("1.00", "2026-11-10T09:45").get("occurredAt").asText()).isEqualTo("2026-11-10T14:45:00Z");
        assertThat(deposit("1.00", "2026-11-10T09:45:00").get("occurredAt").asText()).isEqualTo("2026-11-10T14:45:00Z");
        assertSummary("9.00", "0.00", "9.00");
    }

    @Test
    @DisplayName("周末 / 美股假日：入金出金不受交易日限制")
    void weekendsAndHolidaysAreAllowed() {
        deposit("100.00", "2026-11-08T12:00");   // Sunday
        deposit("100.00", "2026-01-01T12:00");   // New Year's Day (NYSE holiday)
        withdraw("50.00", "2026-11-07T12:00");   // Saturday
        withdraw("50.00", "2026-09-07T12:00");   // Labor Day
        assertSummary("100.00", "0.00", "100.00");
    }

    // ------------------------------------------------------------------ AC 10, 11

    @Test
    @DisplayName("AC10 精确十进制：0.10 + 0.20 = 0.30；1,234,567.89 原样")
    void ac10_exactDecimals() {
        deposit("0.10", "2026-11-02T10:00");
        deposit("0.20", "2026-11-02T10:01");
        assertSummary("0.30", "0.00", "0.30");
        assertThat(ledger().get("items").get(0).get("balanceAfter").asText()).isEqualTo("0.30");

        emptyLedger();
        deposit("1234567.89", "2026-11-02T10:00");
        assertSummary("1234567.89", "0.00", "1234567.89");
        assertThat(ledgerLines()).containsExactly("2026-11-02T15:00:00Z DEPOSIT 1234567.89 1234567.89");
    }

    @Test
    @DisplayName("金额规范化：整数、1 位小数、JSON 数字都输出 scale 2 字符串；最大值边界")
    void amountNormalisationAndMaximum() {
        assertThat(deposit("30000", "2026-11-02T10:00").get("amount").asText()).isEqualTo("30000.00");
        assertThat(deposit("0.5", "2026-11-02T10:00").get("amount").asText()).isEqualTo("0.50");
        assertThat(deposit("0.01", "2026-11-02T10:00").get("amount").asText()).isEqualTo("0.01");
        Resp numeric = postRaw("/api/cash/transfers",
                "{\"type\":\"DEPOSIT\",\"amount\":0.1,\"occurredAtLocal\":\"2026-11-02T10:00\"}",
                org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(numeric.status()).isEqualTo(201);
        assertThat(numeric.body().get("amount").asText()).isEqualTo("0.10");
        assertSummary("30000.61", "0.00", "30000.61");

        emptyLedger();
        assertThat(deposit("9999999999999.99", "2026-11-02T10:00").get("amount").asText())
                .isEqualTo("9999999999999.99");
        assertSummary("9999999999999.99", "0.00", "9999999999999.99");
        withdraw("9999999999999.99", "2026-11-03T10:00");
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("AC11 冻结始终 0.00，可用 = 总现金（每一步都检查）")
    void ac11_frozenAlwaysZero() {
        assertSummary("0.00", "0.00", "0.00");
        deposit("30000.00", "2026-11-02T10:00");
        assertSummary("30000.00", "0.00", "30000.00");
        withdraw("2500.50", "2026-11-03T09:30");
        assertSummary("27499.50", "0.00", "27499.50");
        rejectedWithdrawal("99999.00", "2026-11-04T09:30");
        assertSummary("27499.50", "0.00", "27499.50");
        deposit("0.50", "2026-11-01T09:30");
        assertSummary("27500.00", "0.00", "27500.00");
    }

    // ------------------------------------------------------------------ AC 13

    @Test
    @DisplayName("AC13 60 条入金分页：倒序、首条余额 60.00、末条 2026-09-01 余额 1.00、翻页可见全部")
    void ac13_pagination() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 9, 1);
        for (int i = 0; i < 60; i++) {
            deposit("1.00", day.plusDays(i) + "T10:00");
        }
        JsonNode p1 = ledger(1, 20);
        assertThat(p1.get("totalItems").asLong()).isEqualTo(60);
        assertThat(p1.get("totalPages").asInt()).isEqualTo(3);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("items").get(0).get("occurredAt").asText()).isEqualTo("2026-10-30T14:00:00Z");
        assertThat(p1.get("items").get(0).get("balanceAfter").asText()).isEqualTo("60.00");

        java.util.Set<Long> ids = new java.util.HashSet<>();
        String previous = null;
        int expectedBalance = 60;
        for (int p = 1; p <= 3; p++) {
            JsonNode items = ledger(p, 20).get("items");
            assertThat(items).hasSize(20);
            for (JsonNode row : items) {
                ids.add(row.get("id").asLong());
                String at = row.get("occurredAt").asText();
                if (previous != null) {
                    assertThat(at).as("descending time").isLessThan(previous);
                }
                previous = at;
                assertThat(row.get("balanceAfter").asText()).isEqualTo(expectedBalance + ".00");
                expectedBalance--;
            }
        }
        assertThat(ids).hasSize(60);
        JsonNode last = ledger(3, 20).get("items").get(19);
        assertThat(last.get("occurredAt").asText()).isEqualTo("2026-09-01T14:00:00Z");
        assertThat(last.get("balanceAfter").asText()).isEqualTo("1.00");

        // beyond the last page: empty items, totals still reported
        JsonNode p4 = ledger(4, 20);
        assertThat(p4.get("items")).isEmpty();
        assertThat(p4.get("totalItems").asLong()).isEqualTo(60);
        // other page sizes
        assertThat(ledger(1, 100).get("items")).hasSize(60);
        JsonNode size7 = ledger(9, 7);
        assertThat(size7.get("totalPages").asInt()).isEqualTo(9);
        assertThat(size7.get("items")).hasSize(4);
        assertThat(size7.get("items").get(3).get("balanceAfter").asText()).isEqualTo("1.00");
        assertSummary("60.00", "0.00", "60.00");
    }

    // ------------------------------------------------------------------ remark storage

    @Test
    @DisplayName("备注：空串/全空白存为 null；500 个字符（含中文、emoji 计 2）原样保存")
    void remarkStorage() {
        assertThat(ok("DEPOSIT", "1.00", "2026-11-02T10:00", "").get("remark").isNull()).isTrue();
        assertThat(ok("DEPOSIT", "1.00", "2026-11-02T10:00", "   ").get("remark").isNull()).isTrue();
        String chinese500 = "冲".repeat(500);
        assertThat(ok("DEPOSIT", "1.00", "2026-11-02T10:00", chinese500).get("remark").asText())
                .isEqualTo(chinese500);
        String emoji = "😀".repeat(250); // 500 UTF-16 units
        assertThat(ok("DEPOSIT", "1.00", "2026-11-02T10:00", emoji).get("remark").asText()).isEqualTo(emoji);
        String padded = "  冲正 ";
        assertThat(ok("DEPOSIT", "1.00", "2026-11-02T10:00", padded).get("remark").asText()).isEqualTo(padded);

        List<String> remarks = new java.util.ArrayList<>();
        ledger().get("items").forEach(r -> remarks.add(r.get("remark").isNull() ? null : r.get("remark").asText()));
        assertThat(remarks).containsExactly(padded, emoji, chinese500, null, null);
    }

    @Test
    @DisplayName("录错冲正：误入金后录一笔同额出金，备注“冲正”")
    void reversalWithOppositeEntry() {
        deposit("5000.00", "2026-11-02T10:00");
        deposit("1000.00", "2026-11-03T10:00");
        ok("WITHDRAWAL", "1000.00", "2026-11-03T10:05", "冲正");
        assertSummary("5000.00", "0.00", "5000.00");
        assertThat(ledger().get("items").get(0).get("remark").asText()).isEqualTo("冲正");
    }
}
