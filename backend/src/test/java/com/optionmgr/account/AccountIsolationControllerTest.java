package com.optionmgr.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** FEAT-033 acceptance 3-10 (backend part). "Now" is 2026-11-12 10:00 New York (15:00Z). */
@SpringBootTest
@AutoConfigureMockMvc
class AccountIsolationControllerTest {

    static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock accountTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private ScenarioM main;
    private ScenarioM small;

    @BeforeEach
    void setUp() {
        cleanUp();
        main = new ScenarioM(mvc, json, 1);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /** Back to a fresh database: only the default account, no data. */
    void cleanUp() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.update("UPDATE account SET name = '默认账户', name_key = '默认账户', broker_note = NULL WHERE id = 1");
        jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 2");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode raw(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(expectedStatus);
        return body.isEmpty() ? null : json.readTree(body);
    }

    private JsonNode account(String name, String brokerNote, int expectedStatus) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        n.put("brokerNote", brokerNote);
        return raw(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(n)),
                expectedStatus);
    }

    private JsonNode rename(long id, String name, String brokerNote, int expectedStatus) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        n.put("brokerNote", brokerNote);
        return raw(put("/api/accounts/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(n)), expectedStatus);
    }

    private ScenarioM createSmall() throws Exception {
        long id = account(" 小账户 ", "券商 B", 201).get("id").asLong();
        assertThat(id).isEqualTo(2);
        small = new ScenarioM(mvc, json, id);
        return small;
    }

    private static List<String> summary(ScenarioM a) throws Exception {
        JsonNode s = a.get("/api/cash/summary");
        return List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(), s.get("availableCash").asText());
    }

    private static List<String> positions(ScenarioM a) throws Exception {
        List<String> out = new ArrayList<>();
        a.get("/api/option-positions").get("positions").forEach(p -> out.add(p.get("contractLabel").asText() + " "
                + p.get("direction").asText()));
        return out;
    }

    private static List<String> auditTexts(ScenarioM a) throws Exception {
        List<String> out = new ArrayList<>();
        a.get("/api/audit-log").get("items").forEach(i -> out.add(i.get("operation").asText() + " "
                + i.get("objectType").asText() + " #" + i.get("objectId").asText()));
        return out;
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    void ac3_freshDatabaseHasOnlyTheDefaultAccount() throws Exception {
        JsonNode accounts = raw(get("/api/accounts"), 200);
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).get("id").asLong()).isEqualTo(1);
        assertThat(accounts.get(0).get("name").asText()).isEqualTo("默认账户");
        assertThat(summary(main)).containsExactly("0.00", "0.00", "0.00");
        assertThat(main.get("/api/option-trades").get("totalItems").asInt()).isZero();
        assertThat(raw(get("/api/accounts/1"), 200).get("name").asText()).isEqualTo("默认账户");
    }

    // ---------------------------------------------------------------- AC 4

    @Test
    void ac4_accountManagement() throws Exception {
        JsonNode created = account(" 小账户 ", "券商 B", 201);
        assertThat(created.get("id").asLong()).isEqualTo(2);
        assertThat(created.get("name").asText()).isEqualTo("小账户");
        assertThat(created.get("brokerNote").asText()).isEqualTo("券商 B");
        assertThat(created.get("createdAt").asText()).isEqualTo("2026-11-12T15:00:00Z");
        assertThat(raw(get("/api/accounts"), 200)).hasSize(2);

        for (String name : new String[] {"默认账户", "小账户", " 小账户 ", "", "   ", "x".repeat(31)}) {
            JsonNode err = account(name, null, 400);
            assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(err.get("details").get(0).get("field").asText()).isEqualTo("name");
        }
        assertThat(account("默认账户", null, 400).get("details").get(0).get("message").asText())
                .isEqualTo("账户名称已存在");
        assertThat(account("", null, 400).get("details").get(0).get("message").asText()).isEqualTo("账户名称不能为空");
        assertThat(account("x".repeat(31), null, 400).get("details").get(0).get("message").asText())
                .isEqualTo("账户名称最多 30 个字符");
        assertThat(account("y", "z".repeat(51), 400).get("details").get(0).get("field").asText())
                .isEqualTo("brokerNote");
        account("Main", null, 201);
        assertThat(account("main", null, 400).get("details").get(0).get("message").asText()).isEqualTo("账户名称已存在");
        assertThat(raw(get("/api/accounts"), 200)).hasSize(3);

        JsonNode renamed = rename(1, "主账户", null, 200);
        assertThat(renamed.get("name").asText()).isEqualTo("主账户");
        assertThat(raw(get("/api/accounts"), 200).get(0).get("name").asText()).isEqualTo("主账户");
        assertThat(rename(1, "主账户", null, 422).get("code").asText()).isEqualTo("NO_CHANGES");
        assertThat(rename(1, "小账户", null, 400).get("details").get(0).get("message").asText())
                .isEqualTo("账户名称已存在");
        assertThat(rename(99, "x", null, 404).get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        // renaming to a different letter case of its own name is allowed
        rename(1, "主账户 ", "IBKR", 200);

        JsonNode audit = main.get("/api/audit-log").get("items");
        assertThat(audit).hasSize(2);
        JsonNode first = audit.get(1);
        assertThat(first.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(first.get("objectType").asText()).isEqualTo("ACCOUNT");
        assertThat(first.get("objectId").asLong()).isEqualTo(1);
        assertThat(first.get("summary").asText()).isEqualTo("账户 默认账户");
        assertThat(first.get("changes").get(0).get("text").asText()).isEqualTo("名称：默认账户 → 主账户");
        assertThat(audit.get(0).get("changes").get(0).get("text").asText()).isEqualTo("券商备注：— → IBKR");
        // no delete (Q58)
        assertThat(raw(delete("/api/accounts/2"), 405).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void ac4_renameKeepsTheData() throws Exception {
        main.build();
        Map<String, JsonNode> before = main.snapshot();
        rename(1, "主账户", null, 200);
        Map<String, JsonNode> after = main.snapshot();
        for (String key : before.keySet()) {
            if (!key.startsWith("/api/audit-log")) {
                // FEAT-035: rows carry the account's current name; everything else is unchanged
                assertThat(after.get(key).toString().replace("\"accountName\":\"主账户\"", "\"accountName\":\"默认账户\""))
                        .as(key).isEqualTo(before.get(key).toString());
            }
        }
        assertThat(after.get("/api/cash/summary").at("/accounts/0/accountName").asText()).isEqualTo("主账户");
        assertThat(after.get("/api/audit-log?size=100").get("totalItems").asInt()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- AC 5-8

    @Test
    void ac5to8_accountsAreIndependent() throws Exception {
        main.build();
        Map<String, JsonNode> mainBefore = main.snapshot();
        createSmall();

        // AC 5: the new account starts from zero
        assertThat(summary(small)).containsExactly("0.00", "0.00", "0.00");
        assertThat(small.get("/api/option-trades").get("totalItems").asInt()).isZero();
        assertThat(small.get("/api/option-positions").get("positionCount").asInt()).isZero();
        assertThat(small.get("/api/closed-trades").get("filteredCount").asInt()).isZero();
        assertThat(small.get("/api/dashboard").get("realizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(auditTexts(small)).containsExactly("CREATE ACCOUNT #2");
        JsonNode createAudit = small.get("/api/audit-log").get("items").get(0);
        assertThat(createAudit.get("before").isNull()).isTrue();
        assertThat(createAudit.get("after").get("name").asText()).isEqualTo("小账户");
        assertThat(createAudit.get("changes")).isEmpty();
        assertThat(main.snapshot()).isEqualTo(mainBefore);

        // AC 6: cash and Q18 per account
        long smallDeposit = small.transfer("DEPOSIT", "30000.00", "2026-11-02T10:00");
        small.trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30");
        assertThat(summary(small)).containsExactly("30349.35", "20000.00", "10349.35");
        assertThat(summary(main)).containsExactly("99603.05", "44000.00", "55603.05");
        ObjectNode bto = json.createObjectNode();
        bto.put("symbol", "MSFT");
        bto.put("expirationDate", "2026-12-18");
        bto.put("strike", "450");
        bto.put("optionType", "CALL");
        bto.put("action", "BTO");
        bto.put("contracts", 1);
        bto.put("netAmount", "20000.00");
        bto.put("tradedAtLocal", "2026-11-04T10:00");
        assertThat(small.send(post("/api/option-trades"), bto, 422).get("message").asText())
                .isEqualTo("可用现金不足：需要 20,000.00，可用 10,349.35");

        // AC 7: long/short and FIFO per account
        small.trade("BTO", "AAPL", "2026-11-20", "190", "PUT", 1, 100, "300.00", "2026-11-04T10:00");
        assertThat(summary(small)).containsExactly("30049.35", "20000.00", "10049.35");
        assertThat(positions(small)).containsExactly("AAPL 2026-11-20 190 P LONG", "AAPL 2026-12-18 200 P SHORT");
        assertThat(positions(main)).contains("AAPL 2026-11-20 190 P SHORT").hasSize(3);
        long smallBtc = small.trade("BTC", "AAPL", "2026-12-18", "200", "PUT", 1, null, "100.00", "2026-11-05T10:00");
        assertThat(small.get("/api/option-trades/" + smallBtc).get("realizedPnl").asText()).isEqualTo("249.35");
        assertThat(summary(small)).containsExactly("29949.35", "0.00", "29949.35");
        assertThat(small.get("/api/closed-trades").get("filteredRealizedPnlTotal").asText()).isEqualTo("249.35");
        assertThat(small.get("/api/dashboard").get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(main.get("/api/closed-trades").get("filteredRealizedPnlTotal").asText()).isEqualTo("763.05");
        ObjectNode mainBtc = json.createObjectNode();
        mainBtc.put("symbol", "AAPL");
        mainBtc.put("expirationDate", "2026-12-18");
        mainBtc.put("strike", "200");
        mainBtc.put("optionType", "PUT");
        mainBtc.put("action", "BTC");
        mainBtc.put("contracts", 1);
        mainBtc.put("netAmount", "100.00");
        mainBtc.put("tradedAtLocal", "2026-11-10T10:00");
        assertThat(main.send(post("/api/option-trades/closes"), mainBtc, 422).get("code").asText())
                .isEqualTo("NO_POSITION_TO_CLOSE");

        // AC 8: edits, deletes and audit per account
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "110.00");
        edit.put("tradedAtLocal", "2026-11-05T10:00");
        small.send(put("/api/option-trades/" + smallBtc), edit, 200);
        assertThat(small.get("/api/option-trades/" + smallBtc).get("realizedPnl").asText()).isEqualTo("239.35");
        assertThat(auditTexts(small)).containsExactly("UPDATE OPTION_TRADE #" + smallBtc, "CREATE ACCOUNT #2");
        assertThat(main.get("/api/audit-log").get("totalItems").asInt()).isEqualTo(2);

        // records of another account are "not found" (no change)
        assertThat(main.call(get("/api/option-trades/" + smallBtc), 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(main.send(put("/api/option-trades/" + smallBtc), edit, 404).get("code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(main.call(delete("/api/option-trades/" + smallBtc), 404).get("code").asText())
                .isEqualTo("NOT_FOUND");
        ObjectNode t = json.createObjectNode();
        t.put("type", "DEPOSIT");
        t.put("amount", "1.00");
        t.put("occurredAtLocal", "2026-11-02T10:00");
        assertThat(main.send(put("/api/cash/transfers/" + smallDeposit), t, 404).get("code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(main.call(delete("/api/cash/transfers/" + smallDeposit), 404).get("code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(small.get("/api/option-trades/" + smallBtc).get("netAmount").asText()).isEqualTo("110.00");

        // Q19 in the small account only
        assertThat(small.call(delete("/api/cash/transfers/" + smallDeposit), 422).get("code").asText())
                .isEqualTo("CASH_INSUFFICIENT");

        // the default account never changed
        assertThat(main.snapshot()).isEqualTo(mainBefore);
    }

    // ---------------------------------------------------------------- AC 10

    @Test
    void ac10_accountHeaderIsRequiredAndChecked() throws Exception {
        for (String url : new String[] {"/api/cash/summary", "/api/cash/ledger", "/api/option-trades",
                "/api/option-trades/1", "/api/option-positions", "/api/closed-trades", "/api/pnl-summary",
                "/api/dashboard", "/api/audit-log"}) {
            JsonNode missing = raw(get(URI.create(url)), 400);
            assertThat(missing.get("code").asText()).as(url).isEqualTo("ACCOUNT_REQUIRED");
            assertThat(missing.get("details").get(0).get("field").asText()).isEqualTo("X-Account-Id");
            assertThat(missing.get("details").get(0).get("message").asText()).isEqualTo("请指定账户");

            JsonNode unknown = raw(get(URI.create(url)).header("X-Account-Id", "99"), 404);
            assertThat(unknown.get("code").asText()).as(url).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(unknown.get("message").asText()).isEqualTo("Account 99 not found");

            JsonNode bad = raw(get(URI.create(url)).header("X-Account-Id", "abc"), 400);
            assertThat(bad.get("code").asText()).as(url).isEqualTo("BAD_REQUEST");
            assertThat(bad.get("details").get(0).get("field").asText()).isEqualTo("X-Account-Id");
        }
        // writes without an account write nothing, and the account check comes before body validation
        JsonNode write = raw(post("/api/cash/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"), 400);
        assertThat(write.get("code").asText()).isEqualTo("ACCOUNT_REQUIRED");
        JsonNode writeUnknown = raw(post("/api/option-trades").header("X-Account-Id", "99")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 404);
        assertThat(writeUnknown.get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transfer", Long.class)).isZero();

        // not account-scoped
        raw(get("/api/health"), 200);
        raw(get("/api/accounts"), 200);
        assertThat(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
