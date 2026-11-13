package com.optionmgr.cash.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * FEAT-007 acceptance criteria that the backend owns, through the real HTTP API and database.
 * "Now" is fixed at 2026-11-10T14:45:00Z (09:45 New York). Each test starts from an empty ledger.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class CashControllerTest {

    static final Instant NOW = Instant.parse("2026-11-10T14:45:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        emptyLedger();
    }

    @AfterEach
    void tearDown() {
        emptyLedger();
    }

    void emptyLedger() {
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode body(String type, String amount, String occurredAtLocal, String remark) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", occurredAtLocal);
        n.put("remark", remark);
        return n;
    }

    private MvcResult postTransfer(ObjectNode body) throws Exception {
        return mvc.perform(post("/api/cash/transfers").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private JsonNode transfer(String type, String amount, String nyTime, String remark, int expectedStatus)
            throws Exception {
        MvcResult r = postTransfer(body(type, amount, nyTime, remark));
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode deposit(String amount, String nyTime) throws Exception {
        return transfer("DEPOSIT", amount, nyTime, null, 201);
    }

    private JsonNode withdraw(String amount, String nyTime) throws Exception {
        return transfer("WITHDRAWAL", amount, nyTime, null, 201);
    }

    private JsonNode rejectedWithdrawal(String amount, String nyTime) throws Exception {
        JsonNode err = transfer("WITHDRAWAL", amount, nyTime, null, 422);
        assertThat(err.get("code").asText()).isEqualTo("CASH_INSUFFICIENT");
        assertThat(err.get("details")).isEmpty();
        return err;
    }

    private JsonNode getJson(String url) throws Exception {
        String raw = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(raw);
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        assertThat(s.get("totalCash").asText()).isEqualTo(total);
        assertThat(s.get("frozenCash").asText()).isEqualTo(frozen);
        assertThat(s.get("availableCash").asText()).isEqualTo(available);
        assertThat(s.get("totalCash").isTextual()).isTrue();
    }

    private JsonNode ledger() throws Exception {
        return getJson("/api/cash/ledger");
    }

    private void assertRow(JsonNode row, String occurredAt, String sourceType, String amount, String balanceAfter) {
        assertThat(row.get("occurredAt").asText()).isEqualTo(occurredAt);
        assertThat(row.get("sourceType").asText()).isEqualTo(sourceType);
        assertThat(row.get("amount").asText()).isEqualTo(amount);
        assertThat(row.get("balanceAfter").asText()).isEqualTo(balanceAfter);
    }

    private long ledgerCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_entry", Long.class);
    }

    private long transferCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cash_transfer", Long.class);
    }

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_emptyAccount() throws Exception {
        assertSummary("0.00", "0.00", "0.00");
        JsonNode page = ledger();
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalItems").asLong()).isZero();
        assertThat(page.get("totalPages").asInt()).isZero();
    }

    @Test
    void ac2_deposit() throws Exception {
        JsonNode t = transfer("DEPOSIT", "30000.00", "2026-11-02T10:00", "初始资金", 201);

        assertThat(t.get("type").asText()).isEqualTo("DEPOSIT");
        assertThat(t.get("amount").asText()).isEqualTo("30000.00");
        assertThat(t.get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00Z");
        assertThat(t.get("remark").asText()).isEqualTo("初始资金");
        assertThat(t.get("createdAt").asText()).isEqualTo("2026-11-10T14:45:00Z");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM cash_transfer", Instant.class))
                .isEqualTo(Instant.parse("2026-11-02T15:00:00Z"));

        assertSummary("30000.00", "0.00", "30000.00");
        JsonNode rows = ledger().get("items");
        assertThat(rows).hasSize(1);
        assertRow(rows.get(0), "2026-11-02T15:00:00Z", "DEPOSIT", "30000.00", "30000.00");
        assertThat(rows.get(0).get("remark").asText()).isEqualTo("初始资金");
        assertThat(rows.get(0).get("description").isNull()).isTrue();
        assertThat(rows.get(0).get("sourceId").asLong()).isEqualTo(t.get("id").asLong());
        assertThat(rows.get(0).get("id").asLong()).isEqualTo(t.get("ledgerEntryId").asLong());
    }

    @Test
    void ac3_withdrawal() throws Exception {
        deposit("30000.00", "2026-11-02T10:00");
        withdraw("2500.50", "2026-11-03T09:30");

        assertSummary("27499.50", "0.00", "27499.50");
        JsonNode rows = ledger().get("items");
        assertThat(rows).hasSize(2);
        assertRow(rows.get(0), "2026-11-03T14:30:00Z", "WITHDRAWAL", "-2500.50", "27499.50");
        assertRow(rows.get(1), "2026-11-02T15:00:00Z", "DEPOSIT", "30000.00", "30000.00");
    }

    @Test
    void ac4_withdrawalOverAvailableIsRejectedThenExactAmountSucceeds() throws Exception {
        deposit("30000.00", "2026-11-02T10:00");
        withdraw("2500.50", "2026-11-03T09:30");

        JsonNode err = rejectedWithdrawal("27499.51", "2026-11-10T09:00");
        assertThat(err.get("status").asInt()).isEqualTo(422);
        assertThat(err.get("message").asText()).isEqualTo("可用现金不足：需要 27,499.51，可用 27,499.50");
        assertSummary("27499.50", "0.00", "27499.50");
        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(transferCount()).isEqualTo(2);

        withdraw("27499.50", "2026-11-10T09:00");
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    void ac5_backdatedWithdrawalIsReplayed() throws Exception {
        deposit("10000.00", "2026-11-02T10:00");
        withdraw("9000.00", "2026-11-05T10:00");
        assertSummary("1000.00", "0.00", "1000.00");

        JsonNode err = rejectedWithdrawal("1500.00", "2026-11-03T10:00");
        assertThat(err.get("message").asText())
                .isEqualTo("可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00");
        assertThat(ledgerCount()).isEqualTo(2);

        JsonNode beforeFirst = rejectedWithdrawal("100.00", "2026-11-01T10:00");
        assertThat(beforeFirst.get("message").asText()).isEqualTo("可用现金不足：需要 100.00，可用 0.00");

        withdraw("1000.00", "2026-11-03T10:00");
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    void ac6_backdatedDepositRecomputesBalances() throws Exception {
        deposit("10000.00", "2026-11-05T10:00");
        deposit("5000.00", "2026-11-02T10:00");

        assertSummary("15000.00", "0.00", "15000.00");
        JsonNode rows = ledger().get("items");
        assertRow(rows.get(0), "2026-11-05T15:00:00Z", "DEPOSIT", "10000.00", "15000.00");
        assertRow(rows.get(1), "2026-11-02T15:00:00Z", "DEPOSIT", "5000.00", "5000.00");
    }

    @Test
    void ac7_sameTimeOrderedByEntryOrder() throws Exception {
        deposit("1000.00", "2026-11-03T10:00");
        withdraw("1000.00", "2026-11-03T10:00");

        JsonNode rows = ledger().get("items");
        assertRow(rows.get(0), "2026-11-03T15:00:00Z", "WITHDRAWAL", "-1000.00", "0.00");
        assertRow(rows.get(1), "2026-11-03T15:00:00Z", "DEPOSIT", "1000.00", "1000.00");
        assertThat(rows.get(0).get("id").asLong()).isGreaterThan(rows.get(1).get("id").asLong());
    }

    @Test
    void ac7_withdrawalFirstOnEmptyLedgerIsRejected() throws Exception {
        JsonNode err = rejectedWithdrawal("1000.00", "2026-11-03T10:00");
        assertThat(err.get("message").asText()).isEqualTo("可用现金不足：需要 1,000.00，可用 0.00");
        assertThat(ledgerCount()).isZero();

        // A deposit at the same instant entered afterwards sorts after the (rejected) withdrawal anyway.
        deposit("1000.00", "2026-11-03T10:00");
        assertSummary("1000.00", "0.00", "1000.00");
    }

    @Test
    void ac8_newYorkTimeIsConvertedToUtcIncludingDst() throws Exception {
        assertThat(deposit("1.00", "2026-07-15T10:30").get("occurredAt").asText()).isEqualTo("2026-07-15T14:30:00Z");
        assertThat(deposit("1.00", "2026-11-02T10:00").get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00Z");
        // DST edge cases follow java.time defaults: gap shifted +1h, overlap takes the earlier (EDT) offset.
        assertThat(deposit("1.00", "2026-03-08T02:30").get("occurredAt").asText()).isEqualTo("2026-03-08T07:30:00Z");
        assertThat(deposit("1.00", "2026-11-01T01:30").get("occurredAt").asText()).isEqualTo("2026-11-01T05:30:00Z");
        // Seconds are accepted too.
        assertThat(deposit("1.00", "2026-11-02T10:00:30").get("occurredAt").asText())
                .isEqualTo("2026-11-02T15:00:30Z");
        assertThat(ledger().get("items").get(0).get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:30Z");
    }

    @Test
    void ac8_currentTimeIsAllowed() throws Exception {
        // NOW = 2026-11-10 09:45 New York.
        assertThat(deposit("1.00", "2026-11-10T09:45").get("occurredAt").asText()).isEqualTo("2026-11-10T14:45:00Z");
    }

    @Test
    void ac9_fieldValidation() throws Exception {
        assertValidationError(body("DEPOSIT", null, "2026-11-02T10:00", null), "amount");
        assertValidationError(body("DEPOSIT", "", "2026-11-02T10:00", null), "amount");
        assertValidationError(body("DEPOSIT", "0", "2026-11-02T10:00", null), "amount");
        assertValidationError(body("DEPOSIT", "0.00", "2026-11-02T10:00", null), "amount");
        assertValidationError(body("DEPOSIT", "-100.00", "2026-11-02T10:00", null), "amount");
        assertValidationError(body("DEPOSIT", "100.001", "2026-11-02T10:00", null), "amount");
        assertValidationError(body("DEPOSIT", "10000000000000.00", "2026-11-02T10:00", null), "amount");
        assertValidationError(body("DEPOSIT", "100.00", "2026-11-10T10:00", null), "occurredAtLocal");
        assertValidationError(body("DEPOSIT", "100.00", null, null), "occurredAtLocal");
        assertValidationError(body(null, "100.00", "2026-11-02T10:00", null), "type");
        assertValidationError(body("DEPOSIT", "100.00", "2026-11-02T10:00", "x".repeat(501)), "remark");
        assertThat(transferCount()).isZero();
        assertThat(ledgerCount()).isZero();

        JsonNode ok = transfer("DEPOSIT", "100.00", "2026-11-02T10:00", "备".repeat(500), 201);
        assertThat(ok.get("remark").asText()).hasSize(500);
    }

    @Test
    void ac9_validationAlsoAppliesToWithdrawalsBeforeCashCheck() throws Exception {
        assertValidationError(body("WITHDRAWAL", "100.001", "2026-11-02T10:00", null), "amount");
        assertValidationError(body("WITHDRAWAL", "100.00", "2026-11-10T10:00", null), "occurredAtLocal");
    }

    @Test
    void unparseableValuesAreBadRequestNamingTheField() throws Exception {
        assertBadRequest(body("INTEREST", "100.00", "2026-11-02T10:00", null), "type");
        assertBadRequest(body("DEPOSIT", "abc", "2026-11-02T10:00", null), "amount");
        assertBadRequest(body("DEPOSIT", "100.00", "2026-11-02 10:00", null), "occurredAtLocal");
        assertBadRequest(body("DEPOSIT", "100.00", "2026-11-02T10:00Z", null), "occurredAtLocal");
        assertBadRequest(body("DEPOSIT", "100.00", "2026-11-02T10:00:00.5", null), "occurredAtLocal");
        // BUG-001 regression: non-existent calendar dates must not be adjusted to the month end.
        assertBadRequest(body("DEPOSIT", "100.00", "2026-02-30T10:00", null), "occurredAtLocal");
        assertBadRequest(body("DEPOSIT", "100.00", "2026-04-31T10:00", null), "occurredAtLocal");
        assertBadRequest(body("DEPOSIT", "100.00", "2026-11-02T24:00", null), "occurredAtLocal");
        assertThat(transferCount()).isZero();
    }

    @Test
    void blankRemarkIsStoredAsNull() throws Exception {
        assertThat(transfer("DEPOSIT", "1.00", "2026-11-02T10:00", "   ", 201).get("remark").isNull()).isTrue();
    }

    @Test
    void ac10_amountsAreExactDecimals() throws Exception {
        deposit("0.10", "2026-11-02T10:00");
        deposit("0.20", "2026-11-02T10:01");
        assertSummary("0.30", "0.00", "0.30");

        emptyLedger();
        deposit("1234567.89", "2026-11-02T10:00");
        assertSummary("1234567.89", "0.00", "1234567.89");
        assertRow(ledger().get("items").get(0), "2026-11-02T15:00:00Z", "DEPOSIT", "1234567.89", "1234567.89");
    }

    @Test
    void amountWithoutDecimalsIsNormalizedToTwo() throws Exception {
        assertThat(deposit("30000", "2026-11-02T10:00").get("amount").asText()).isEqualTo("30000.00");
    }

    @Test
    void ac11_frozenIsAlwaysZeroAndAvailableEqualsTotal() throws Exception {
        deposit("30000.00", "2026-11-02T10:00");
        assertSummary("30000.00", "0.00", "30000.00");
        withdraw("2500.50", "2026-11-03T09:30");
        assertSummary("27499.50", "0.00", "27499.50");
    }

    @Test
    void ac13_paginationNewestFirst() throws Exception {
        LocalDate day = LocalDate.of(2026, 9, 1);
        for (int i = 0; i < 60; i++) {
            deposit("1.00", day.plusDays(i) + "T10:00");
        }

        JsonNode first = getJson("/api/cash/ledger?page=1&size=20");
        assertThat(first.get("totalItems").asLong()).isEqualTo(60);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("items")).hasSize(20);
        assertThat(first.get("items").get(0).get("balanceAfter").asText()).isEqualTo("60.00");
        assertThat(first.get("items").get(0).get("occurredAt").asText()).isEqualTo("2026-10-30T14:00:00Z");

        JsonNode last = getJson("/api/cash/ledger?page=3&size=20");
        assertThat(last.get("items")).hasSize(20);
        JsonNode lastRow = last.get("items").get(19);
        assertThat(lastRow.get("balanceAfter").asText()).isEqualTo("1.00");
        assertThat(lastRow.get("occurredAt").asText()).isEqualTo("2026-09-01T14:00:00Z");

        // Every balance 60..1 appears exactly once across the pages.
        StringBuilder balances = new StringBuilder();
        for (int p = 1; p <= 3; p++) {
            getJson("/api/cash/ledger?page=" + p + "&size=20").get("items")
                    .forEach(r -> balances.append(r.get("balanceAfter").asText()).append(','));
        }
        StringBuilder expected = new StringBuilder();
        for (int b = 60; b >= 1; b--) {
            expected.append(b).append(".00,");
        }
        assertThat(balances.toString()).isEqualTo(expected.toString());

        assertThat(getJson("/api/cash/ledger?page=4&size=20").get("items")).isEmpty();
        assertThat(getJson("/api/cash/ledger?size=100").get("items")).hasSize(60);
    }

    @Test
    void invalidPagingParameters() throws Exception {
        assertParamError("/api/cash/ledger?page=0", 400, "VALIDATION_ERROR", "page");
        assertParamError("/api/cash/ledger?size=0", 400, "VALIDATION_ERROR", "size");
        assertParamError("/api/cash/ledger?size=101", 400, "VALIDATION_ERROR", "size");
        assertParamError("/api/cash/ledger?page=x", 400, "BAD_REQUEST", "page");
    }

    @Test
    void ledgerIdIsEntryOrderNotTimeOrder() throws Exception {
        JsonNode a = deposit("1.00", "2026-11-03T10:00");
        JsonNode b = deposit("1.00", "2026-11-02T10:00");
        assertThat(b.get("ledgerEntryId").asLong()).isGreaterThan(a.get("ledgerEntryId").asLong());
    }

    // ---------------------------------------------------------------- error assertions

    private void assertValidationError(ObjectNode body, String field) throws Exception {
        MvcResult r = postTransfer(body);
        JsonNode err = json.readTree(r.getResponse().getContentAsString());
        assertThat(r.getResponse().getStatus()).as(body + " -> " + err).isEqualTo(400);
        assertThat(err.get("code").asText()).as(body.toString()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").findValuesAsText("field")).as(body.toString()).containsExactly(field);
        assertThat(err.get("details").get(0).get("message").asText()).isNotBlank();
    }

    private void assertBadRequest(ObjectNode body, String field) throws Exception {
        MvcResult r = postTransfer(body);
        JsonNode err = json.readTree(r.getResponse().getContentAsString());
        assertThat(r.getResponse().getStatus()).as(body + " -> " + err).isEqualTo(400);
        assertThat(err.get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(err.get("details").findValuesAsText("field")).as(body.toString()).containsExactly(field);
    }

    private void assertParamError(String url, int status, String code, String field) throws Exception {
        MvcResult r = mvc.perform(get(url)).andReturn();
        JsonNode err = json.readTree(r.getResponse().getContentAsString());
        assertThat(r.getResponse().getStatus()).as(url + " -> " + err).isEqualTo(status);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("details").findValuesAsText("field")).containsExactly(field);
    }
}
