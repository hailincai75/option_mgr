package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * QA system tests for FEAT-007: the whole application on a real HTTP port (no MockMvc), in-memory H2
 * (profile "test"), "now" fixed at 2026-11-10T14:45:00Z = 2026-11-10 09:45 New York (the example of AC 8/9).
 * Every test starts and ends with an empty ledger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CashSystemTestBase.FixedClock.class)
abstract class CashSystemTestBase {

    static final Instant NOW = Instant.parse("2026-11-10T14:45:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MutableClock qaFixedClock() {
            return new MutableClock(NOW);
        }
    }

    /** "Now" used by the backend; reset to {@link #now()} before every test. */
    @Autowired
    MutableClock clock;

    /** The fixed "now" for this test class; subclasses may override. */
    Instant now() {
        return NOW;
    }

    /** Response as status + parsed JSON body (decoded as UTF-8, so Chinese messages are compared exactly). */
    record Resp(int status, JsonNode body, HttpHeaders headers) {
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanBefore() {
        accountId = "1";
        clock.set(now());
        emptyLedger();
    }

    @AfterEach
    void cleanAfter() {
        emptyLedger();
    }

    /**
     * Empties all business data of every account and removes every account except the default one (id 1, restored to
     * its migrated name), so each test starts from a fresh single-account database. Only the in-memory test DB.
     */
    void emptyLedger() {
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        // FEAT-011: stock_trade has an FK to account, so it must be emptied before non-default accounts are removed.
        // FEAT-013: ASSIGN_BUY rows reference option_trade (fk_stock_trade_option_trade), so stock_trade goes first.
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.update("UPDATE account SET name = '默认账户', name_key = '默认账户', broker_note = NULL WHERE id = 1");
    }

    /** Account sent as X-Account-Id on every request (FEAT-033); reset to 1 before each test, null = omit. */
    String accountId = "1";

    // ------------------------------------------------------------------ HTTP

    Resp get(String path) {
        return exchange(HttpMethod.GET, path, null, MediaType.APPLICATION_JSON);
    }

    Resp postRaw(String path, String rawBody, MediaType contentType) {
        return exchange(HttpMethod.POST, path, rawBody, contentType);
    }

    Resp exchange(HttpMethod method, String path, String rawBody, MediaType contentType) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (accountId != null) {
            h.set("X-Account-Id", accountId);
        }
        if (rawBody != null) {
            h.setContentType(contentType);
        }
        HttpEntity<byte[]> entity = new HttpEntity<>(
                rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8), h);
        ResponseEntity<byte[]> r = http.exchange(path, method, entity, byte[].class);
        JsonNode body = null;
        if (r.getBody() != null && r.getBody().length > 0) {
            try {
                body = json.readTree(new String(r.getBody(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new AssertionError("response is not JSON: " + new String(r.getBody(), StandardCharsets.UTF_8));
            }
        }
        return new Resp(r.getStatusCode().value(), body, r.getHeaders());
    }

    ObjectNode transferBody(String type, String amount, String occurredAtLocal, String remark) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", occurredAtLocal);
        n.put("remark", remark);
        return n;
    }

    Resp postTransfer(ObjectNode body) {
        return postRaw("/api/cash/transfers", body.toString(), MediaType.APPLICATION_JSON);
    }

    Resp transfer(String type, String amount, String occurredAtLocal, String remark) {
        return postTransfer(transferBody(type, amount, occurredAtLocal, remark));
    }

    /** Posts and asserts 201. */
    JsonNode ok(String type, String amount, String occurredAtLocal, String remark) {
        Resp r = transfer(type, amount, occurredAtLocal, remark);
        assertThat(r.status()).as("POST %s %s @%s -> %s", type, amount, occurredAtLocal, r.body()).isEqualTo(201);
        return r.body();
    }

    JsonNode deposit(String amount, String at) {
        return ok("DEPOSIT", amount, at, null);
    }

    JsonNode withdraw(String amount, String at) {
        return ok("WITHDRAWAL", amount, at, null);
    }

    /** Posts a withdrawal that must be rejected with CASH_INSUFFICIENT; returns the message. */
    String rejectedWithdrawal(String amount, String at) {
        Resp r = transfer("WITHDRAWAL", amount, at, null);
        assertErrorShape(r, 422, "CASH_INSUFFICIENT", "/api/cash/transfers");
        assertThat(r.body().get("details")).isEmpty();
        return r.body().get("message").asText();
    }

    // ------------------------------------------------------------------ reads

    JsonNode summary() {
        Resp r = get("/api/cash/summary");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    void assertSummary(String total, String frozen, String available) {
        JsonNode s = summary();
        assertThat(s.get("totalCash").isTextual()).as("totalCash is a JSON string").isTrue();
        assertThat(s.get("frozenCash").isTextual()).as("frozenCash is a JSON string").isTrue();
        assertThat(s.get("availableCash").isTextual()).as("availableCash is a JSON string").isTrue();
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText()))
                .as("summary total/frozen/available")
                .containsExactly(total, frozen, available);
    }

    JsonNode ledger(int page, int size) {
        Resp r = get("/api/cash/ledger?page=" + page + "&size=" + size);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode ledger() {
        Resp r = get("/api/cash/ledger");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    /** "occurredAt sourceType amount balanceAfter" per row, top to bottom, of the default first page. */
    List<String> ledgerLines() {
        List<String> out = new ArrayList<>();
        for (JsonNode row : ledger().get("items")) {
            out.add(row.get("occurredAt").asText() + " " + row.get("sourceType").asText() + " "
                    + row.get("amount").asText() + " " + row.get("balanceAfter").asText());
        }
        return out;
    }

    long ledgerCount() {
        return ledger().get("totalItems").asLong();
    }

    long transferRowCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cash_transfer", Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ error contract

    /** Unified error body of the contract: code, message, status, path, timestamp (UTC instant), details[]. */
    void assertErrorShape(Resp r, int status, String code, String path) {
        assertThat(r.status()).as("HTTP status, body=%s", r.body()).isEqualTo(status);
        JsonNode b = r.body();
        assertThat(b).as("error body").isNotNull();
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("path").asText()).isEqualTo(path);
        assertThat(b.get("message").isTextual()).isTrue();
        assertThat(b.get("message").asText()).isNotBlank();
        assertThat(b.get("timestamp").asText()).endsWith("Z");
        Instant.parse(b.get("timestamp").asText());
        assertThat(b.get("details").isArray()).as("details is never null").isTrue();
        for (JsonNode d : b.get("details")) {
            assertThat(d.get("field").isTextual()).isTrue();
            assertThat(d.get("message").isTextual()).isTrue();
        }
    }

    List<String> detailFields(Resp r) {
        List<String> fields = new ArrayList<>();
        r.body().get("details").forEach(d -> fields.add(d.get("field").asText()));
        return fields;
    }
}
