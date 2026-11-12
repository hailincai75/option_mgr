package com.optionmgr.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.testsupport.service.DatabaseResetService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** POST /api/test-support/reset with the e2e profile (in-memory H2 "e2e" from application-e2e.yml). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "e2e"})
class TestSupportResetE2eProfileTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DatabaseResetService resetService;

    @Autowired
    CashLedgerService cashLedger;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        reset();
    }

    @Test
    void runsOnTheInMemoryE2eDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getURL());
        assertThat(url).startsWith("jdbc:h2:mem:e2e");
    }

    @Test
    void coversEveryTableFromTheSchemaExceptFlywayHistory() {
        assertThat(resetService.tables())
                .contains("cash_account", "cash_transfer", "cash_ledger_entry", "option_trade", "audit_record")
                .doesNotContain("flyway_schema_history");
    }

    @Test
    void resetAfterDepositsAndWithdrawalGivesEmptySummaryAndLedger() throws Exception {
        Map<String, Object> account = jdbc.queryForMap("SELECT * FROM cash_account");
        transfer("DEPOSIT", "30000.00", "2026-01-05T10:00");
        transfer("DEPOSIT", "100.00", "2026-01-06T10:00");
        transfer("WITHDRAWAL", "2500.50", "2026-01-07T10:00");
        assertThat(getJson("/api/cash/summary").get("totalCash").asText()).isEqualTo("27599.50");

        reset();

        JsonNode summary = getJson("/api/cash/summary");
        assertThat(summary.get("totalCash").asText()).isEqualTo("0.00");
        assertThat(summary.get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(summary.get("availableCash").asText()).isEqualTo("0.00");
        JsonNode ledger = getJson("/api/cash/ledger");
        assertThat(ledger.get("items")).isEmpty();
        assertThat(ledger.get("totalItems").asInt()).isZero();
        assertThat(ledger.get("totalPages").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transfer", Integer.class)).isZero();
        // The seeded single account row is still exactly what the migration created.
        assertThat(jdbc.queryForList("SELECT * FROM cash_account")).containsExactly(account);
    }

    @Test
    void writesAfterResetBehaveLikeAFreshDatabase() throws Exception {
        transfer("DEPOSIT", "500.00", "2026-01-05T10:00");
        transfer("DEPOSIT", "600.00", "2026-01-05T10:00");
        reset();

        JsonNode first = transfer("DEPOSIT", "1000.00", "2026-02-02T10:00");
        JsonNode second = transfer("WITHDRAWAL", "400.00", "2026-02-02T10:00");
        assertThat(first.get("id").asLong()).isEqualTo(1);
        assertThat(first.get("ledgerEntryId").asLong()).isEqualTo(1);
        assertThat(second.get("id").asLong()).isEqualTo(2);
        assertThat(second.get("ledgerEntryId").asLong()).isEqualTo(2);

        // Same instant: entry order (= id) decides, later entry first.
        JsonNode items = getJson("/api/cash/ledger").get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id").asLong()).isEqualTo(2);
        assertThat(items.get(0).get("balanceAfter").asText()).isEqualTo("600.00");
        assertThat(items.get(1).get("id").asLong()).isEqualTo(1);
        assertThat(items.get(1).get("balanceAfter").asText()).isEqualTo("1000.00");
    }

    @Test
    void resetIsIdempotent() throws Exception {
        reset();
        reset();
        assertThat(getJson("/api/cash/summary").get("totalCash").asText()).isEqualTo("0.00");
        assertThat(transfer("DEPOSIT", "1.00", "2026-01-05T10:00").get("id").asLong()).isEqualTo(1);
    }

    @Test
    void restoresChangedSeedRows() throws Exception {
        Map<String, Object> account = jdbc.queryForMap("SELECT * FROM cash_account");
        jdbc.update("UPDATE cash_account SET currency = 'EUR'");

        reset();

        assertThat(jdbc.queryForList("SELECT * FROM cash_account")).containsExactly(account);
    }

    @Test
    void waitsForTheCashAccountLockHeldByAWrite() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            cashLedger.lockAccount();
            locked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> reset = CompletableFuture.runAsync(resetService::reset);
        Thread.sleep(300);
        assertThat(reset).isNotDone();

        release.countDown();
        writer.get(5, TimeUnit.SECONDS);
        reset.get(5, TimeUnit.SECONDS);
    }

    private void reset() throws Exception {
        mvc.perform(post("/api/test-support/reset")).andExpect(status().isNoContent());
    }

    private JsonNode transfer(String type, String amount, String occurredAtLocal) throws Exception {
        String body = json.writeValueAsString(Map.of("type", type, "amount", amount,
                "occurredAtLocal", occurredAtLocal));
        String response = mvc.perform(post("/api/cash/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
