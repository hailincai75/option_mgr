package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-007 acceptance criterion 9 (field validation), error-body contract of every rejection path, ledger
 * paging parameters, and serialisation of concurrent withdrawals. Every rejection must leave the DB untouched.
 */
class Feat007CashValidationSystemTest extends CashSystemTestBase {

    private static final String PATH = "/api/cash/transfers";

    private void assertValidation(ObjectNode body, String field, String expectedMessage) {
        Resp r = postTransfer(body);
        assertErrorShape(r, 400, "VALIDATION_ERROR", PATH);
        assertThat(detailFields(r)).as("details fields for %s", body).containsExactly(field);
        if (expectedMessage != null) {
            assertThat(r.body().get("details").get(0).get("message").asText()).isEqualTo(expectedMessage);
        }
        assertNothingStored();
    }

    private void assertBadRequest(String rawBody, String field) {
        Resp r = postRaw(PATH, rawBody, MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "BAD_REQUEST", PATH);
        if (field == null) {
            assertThat(r.body().get("details")).isEmpty();
        } else {
            assertThat(detailFields(r)).as("details fields for %s", rawBody).containsExactly(field);
        }
        assertNothingStored();
    }

    private void assertNothingStored() {
        assertThat(transferRowCount()).isZero();
        assertThat(ledgerCount()).isZero();
        assertSummary("0.00", "0.00", "0.00");
    }

    private ObjectNode validDeposit() {
        return transferBody("DEPOSIT", "100.00", "2026-11-02T10:00", "备注");
    }

    // ------------------------------------------------------------------ AC 9: amount

    @Test
    @DisplayName("AC9 金额缺失 / null → VALIDATION_ERROR amount")
    void ac9_amountMissing() {
        ObjectNode b = validDeposit();
        b.remove("amount");
        assertValidation(b, "amount", null);
        ObjectNode n = validDeposit();
        n.putNull("amount");
        assertValidation(n, "amount", null);
    }

    @Test
    @DisplayName("AC9 金额空串 → 400，字段 amount")
    void ac9_amountEmptyString() {
        Resp r = postTransfer(transferBody("DEPOSIT", "", "2026-11-02T10:00", null));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isIn("VALIDATION_ERROR", "BAD_REQUEST");
        assertThat(detailFields(r)).containsExactly("amount");
        assertNothingStored();
    }

    @ParameterizedTest(name = "AC9 金额 {0} → VALIDATION_ERROR amount（入金）")
    @ValueSource(strings = {"0", "0.00", "-100.00", "-0.01", "100.001", "0.001", "10000000000000.00"})
    void ac9_invalidAmountsDeposit(String amount) {
        assertValidation(transferBody("DEPOSIT", amount, "2026-11-02T10:00", null), "amount", null);
    }

    @ParameterizedTest(name = "AC9 金额 {0} → VALIDATION_ERROR amount（出金，先于现金检查）")
    @ValueSource(strings = {"0", "-100.00", "100.001"})
    void ac9_invalidAmountsWithdrawal(String amount) {
        assertValidation(transferBody("WITHDRAWAL", amount, "2026-11-02T10:00", null), "amount", null);
    }

    @Test
    @DisplayName("AC9 3 位小数的提示文案与契约示例一致")
    void ac9_threeDecimalsMessage() {
        assertValidation(transferBody("DEPOSIT", "100.001", "2026-11-02T10:00", null), "amount", "金额最多 2 位小数");
    }

    @ParameterizedTest(name = "金额无法解析 {0} → BAD_REQUEST amount")
    @ValueSource(strings = {"abc", "1,000.00", "12.3.4"})
    void unparseableAmount(String amount) {
        assertBadRequest(transferBody("DEPOSIT", amount, "2026-11-02T10:00", null).toString(), "amount");
    }

    // ------------------------------------------------------------------ AC 9: time

    @Test
    @DisplayName("AC9 时间晚于当前（当前 09:45 美东，填 10:00 / 09:46 / 09:45:01）→ VALIDATION_ERROR")
    void ac9_futureTime() {
        for (String at : List.of("2026-11-10T10:00", "2026-11-10T09:46", "2026-11-10T09:45:01", "2027-01-01T00:00")) {
            assertValidation(transferBody("DEPOSIT", "100.00", at, null), "occurredAtLocal", "时间不能晚于当前时间");
            assertValidation(transferBody("WITHDRAWAL", "100.00", at, null), "occurredAtLocal", "时间不能晚于当前时间");
        }
    }

    @Test
    @DisplayName("AC9 时间缺失 → VALIDATION_ERROR occurredAtLocal")
    void ac9_timeMissing() {
        ObjectNode b = validDeposit();
        b.remove("occurredAtLocal");
        assertValidation(b, "occurredAtLocal", null);
    }

    @ParameterizedTest(name = "时间格式不对 {0} → BAD_REQUEST occurredAtLocal")
    @ValueSource(strings = {"2026-11-02T10:00Z", "2026-11-02T10:00:00-05:00", "2026-11-02T10:00:00.5",
            "2026-11-02 10:00", "2026-11-02", "2026-02-30T10:00", "2026-04-31T10:00", "2025-02-29T10:00", "2027-02-29T10:00",
            "2026-11-02T24:00", "2026-11-02T10:60", "2026-11-02T10:00:60", "2026-13-01T10:00",
            "11/02/2026 10:00 AM", "abc"})
    void badTimeFormat(String at) {
        assertBadRequest(transferBody("DEPOSIT", "100.00", at, null).toString(), "occurredAtLocal");
    }

    // ------------------------------------------------------------------ AC 9: type

    @Test
    @DisplayName("AC9 未选择类型 → VALIDATION_ERROR type")
    void ac9_typeMissing() {
        ObjectNode b = validDeposit();
        b.remove("type");
        assertValidation(b, "type", "请选择类型");
        ObjectNode n = validDeposit();
        n.putNull("type");
        assertValidation(n, "type", "请选择类型");
    }

    @ParameterizedTest(name = "类型非法 {0} → BAD_REQUEST type")
    @ValueSource(strings = {"FOO", "deposit", "BUY_TO_OPEN"})
    void invalidType(String type) {
        assertBadRequest(transferBody(type, "100.00", "2026-11-02T10:00", null).toString(), "type");
    }

    // ------------------------------------------------------------------ AC 9: remark

    @Test
    @DisplayName("AC9 备注 501 字符被拒；500 字符允许")
    void ac9_remarkLength() {
        assertValidation(transferBody("DEPOSIT", "100.00", "2026-11-02T10:00", "a".repeat(501)), "remark", null);
        assertValidation(transferBody("DEPOSIT", "100.00", "2026-11-02T10:00", "冲".repeat(501)), "remark", null);
        assertValidation(transferBody("DEPOSIT", "100.00", "2026-11-02T10:00", "😀".repeat(251)), "remark", null);
        ok("DEPOSIT", "100.00", "2026-11-02T10:00", "a".repeat(500));
        assertSummary("100.00", "0.00", "100.00");
    }

    // ------------------------------------------------------------------ combined / precedence

    @Test
    @DisplayName("多个字段同时出错：details 全部列出且按 field 排序")
    void multipleFieldErrorsSorted() {
        ObjectNode b = json.createObjectNode();
        b.put("amount", "-1");
        b.put("occurredAtLocal", "2026-11-10T10:00");
        b.put("remark", "x".repeat(501));
        Resp r = postTransfer(b);
        assertErrorShape(r, 400, "VALIDATION_ERROR", PATH);
        // occurredAtLocal "in the future" may be checked after bean validation; the rest must all be listed
        List<String> fields = detailFields(r);
        assertThat(fields).contains("amount", "remark", "type");
        assertThat(fields).isSorted();
        assertNothingStored();
    }

    @Test
    @DisplayName("出金：字段错误优先于现金不足（空库、未来时间 → 400 而非 422）")
    void validationBeforeCashCheck() {
        assertValidation(transferBody("WITHDRAWAL", "100.00", "2026-11-10T10:00", null), "occurredAtLocal", null);
    }

    @Test
    @DisplayName("JSON 语法错误 / 空请求体 → BAD_REQUEST，details 为空")
    void malformedJson() {
        assertBadRequest("{\"type\":\"DEPOSIT\",", null);
        assertBadRequest("", null);
    }

    @Test
    @DisplayName("Content-Type 不是 JSON → 415；错误的方法 → 405")
    void mediaTypeAndMethod() {
        Resp r = postRaw(PATH, validDeposit().toString(), MediaType.TEXT_PLAIN);
        assertErrorShape(r, 415, "UNSUPPORTED_MEDIA_TYPE", PATH);
        assertNothingStored();

        assertErrorShape(get(PATH), 405, "METHOD_NOT_ALLOWED", PATH);
        Resp postSummary = postRaw("/api/cash/summary", "{}", MediaType.APPLICATION_JSON);
        assertErrorShape(postSummary, 405, "METHOD_NOT_ALLOWED", "/api/cash/summary");
        Resp delLedger = exchange(HttpMethod.DELETE, "/api/cash/ledger", null, MediaType.APPLICATION_JSON);
        assertErrorShape(delLedger, 405, "METHOD_NOT_ALLOWED", "/api/cash/ledger");
    }

    // ------------------------------------------------------------------ ledger paging params

    @Test
    @DisplayName("流水分页参数：越界 → VALIDATION_ERROR，非整数 → BAD_REQUEST，边界 1 / 100 允许")
    void ledgerPagingParameters() {
        Resp p0 = get("/api/cash/ledger?page=0");
        assertErrorShape(p0, 400, "VALIDATION_ERROR", "/api/cash/ledger");
        assertThat(detailFields(p0)).containsExactly("page");

        for (String q : List.of("size=0", "size=101", "page=1&size=-5")) {
            Resp r = get("/api/cash/ledger?" + q);
            assertErrorShape(r, 400, "VALIDATION_ERROR", "/api/cash/ledger");
            assertThat(detailFields(r)).as(q).containsExactly("size");
        }
        Resp abc = get("/api/cash/ledger?page=abc");
        assertErrorShape(abc, 400, "BAD_REQUEST", "/api/cash/ledger");
        assertThat(detailFields(abc)).containsExactly("page");
        Resp sizeAbc = get("/api/cash/ledger?size=1.5");
        assertErrorShape(sizeAbc, 400, "BAD_REQUEST", "/api/cash/ledger");
        assertThat(detailFields(sizeAbc)).containsExactly("size");

        assertThat(get("/api/cash/ledger?page=1&size=1").status()).isEqualTo(200);
        assertThat(get("/api/cash/ledger?page=1&size=100").status()).isEqualTo(200);
        assertThat(get("/api/cash/ledger?page=999").body().get("items")).isEmpty();
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("并发出金：余额 1,000.00，10 个并发出金 200.00 → 恰好 5 笔成功、5 笔 422，不出现负数或 500")
    void concurrentWithdrawalsAreSerialised() throws Exception {
        deposit("1000.00", "2026-11-02T10:00");
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transfer("WITHDRAWAL", "200.00", "2026-11-03T10:00", null);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Resp> f : futures) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).as("statuses").containsOnly(201, 422);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertSummary("0.00", "0.00", "0.00");
        assertThat(ledgerCount()).isEqualTo(6);
        assertThat(ledger().get("items").get(0).get("balanceAfter").asText()).isEqualTo("0.00");
    }
}
