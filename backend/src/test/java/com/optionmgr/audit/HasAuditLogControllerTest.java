package com.optionmgr.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.audit.domain.AuditObjectType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code hasAuditLog} on the rows that show a "历史" link: trade list (and every single-trade response), closed
 * trades (the closing trade), cash ledger deposit / withdrawal rows. The value is a flag stored on the trade / cash
 * transfer, set by the edit (PUT) path in the transaction that writes the audit record; only the edited record
 * gets it. "Now" is 2026-11-11 10:00 New York.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class HasAuditLogControllerTest {

    static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");
    static final String TRADES = "/api/option-trades";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock hasAuditLogTestClock() {
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
        emptyDatabase();
    }

    @AfterEach
    void tearDown() {
        emptyDatabase();
    }

    void emptyDatabase() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
    }

    // ---------------------------------------------------------------- tests

    @Test
    void tradeListNeverEditedFalseEditedTrue() throws Exception {
        transfer("DEPOSIT", "50000.00", "2026-11-02T10:00:00");
        long a = open("200", "300.00", "2026-11-03T10:00:00");
        long b = open("190", "250.00", "2026-11-03T11:00:00");

        assertThat(tradeRow(a).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(tradeRow(b).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(storedTradeFlag(a)).isFalse();

        editNote(a, "改过");

        assertThat(tradeRow(a).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(tradeRow(b).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(tradeRow(a).get("hasAuditLog").isBoolean()).isTrue(); // a real JSON boolean
        assertThat(storedTradeFlag(a)).isTrue();
        assertThat(storedTradeFlag(b)).isFalse();
    }

    @Test
    void singleTradeResponsesCarryTheFlag() throws Exception {
        transfer("DEPOSIT", "50000.00", "2026-11-02T10:00:00");
        JsonNode created = send(post(TRADES), openBody("200", "300.00", "2026-11-03T10:00:00"), 201);
        long id = created.get("id").asLong();
        assertThat(created.get("hasAuditLog").asBoolean()).isFalse(); // creation is not audited (Q42)
        assertThat(getJson(TRADES + "/" + id).get("hasAuditLog").asBoolean()).isFalse();
        long close = btc("IXQZ", "200", 1, "100.00", "2026-11-04T10:00:00");
        assertThat(getJson(TRADES + "/" + close).get("hasAuditLog").asBoolean()).isFalse();

        JsonNode edited = editNote(id, "改过");
        assertThat(edited.get("hasAuditLog").asBoolean()).isTrue(); // set in the edit's transaction
        assertThat(getJson(TRADES + "/" + id).get("hasAuditLog").asBoolean()).isTrue();
        // a second edit keeps it
        assertThat(editNote(id, "再改").get("hasAuditLog").asBoolean()).isTrue();
    }

    @Test
    void closedTradeRowFollowsTheClosingTradeOnly() throws Exception {
        transfer("DEPOSIT", "50000.00", "2026-11-02T10:00:00");
        long open1 = open("200", "300.00", "2026-11-03T10:00:00");
        long close1 = btc("IXQZ", "200", 1, "100.00", "2026-11-04T10:00:00");
        open("190", "250.00", "2026-11-05T10:00:00");
        long close2 = btc("IXQZ", "190", 1, "50.00", "2026-11-06T10:00:00");

        assertThat(closedRow(close1).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(closedRow(close2).get("hasAuditLog").asBoolean()).isFalse();

        // editing the covered opening trade does not count: the row's 历史 link targets the closing trade
        editNote(open1, "开仓改过");
        assertThat(closedRow(close1).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(tradeRow(open1).get("hasAuditLog").asBoolean()).isTrue();

        editNote(close1, "平仓改过");
        assertThat(closedRow(close1).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(closedRow(close2).get("hasAuditLog").asBoolean()).isFalse();
    }

    @Test
    void recordsCorrectedAsSideEffectsStayFalse() throws Exception {
        // editing F1's net amount corrects the stock cost of the assignment that covers F1 (连带变化). FEAT-051: a
        // BTC's lots are stored (Q255), so a BTC is no longer corrected - an assignment (FIFO, Q260) still is.
        transfer("DEPOSIT", "100.00", "2026-10-26T10:00:00");
        long f1 = openF("2026-11-02T10:00:00");
        long f2 = openF("2026-11-03T10:00:00");
        ObjectNode asg = json.createObjectNode();
        asg.put("symbol", "F");
        asg.put("expirationDate", "2026-12-18");
        asg.put("strike", "12.345");
        asg.put("optionType", "PUT");
        asg.put("contracts", 1);
        asg.put("assignmentDate", "2026-11-04");
        long fa = send(post(TRADES + "/assignments"), asg, 201).get("id").asLong();

        JsonNode t = getJson(TRADES + "/" + f1);
        ObjectNode n = json.createObjectNode();
        n.put("contracts", 1);
        n.put("netAmount", "0.60");
        n.put("tradedAtLocal", t.get("tradedAtLocal").asText());
        n.put("multiplier", 1);
        assertThat(send(put(TRADES + "/" + f1), n, 200).get("hasAuditLog").asBoolean()).isTrue();

        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(audit.get("objectId").asLong()).isEqualTo(f1);
        assertThat(audit.get("sideEffects").get(0).get("tradeId").asLong()).isEqualTo(fa);

        assertThat(tradeRow(f1).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(tradeRow(f2).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(tradeRow(fa).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(getJson(TRADES + "/" + fa).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(storedTradeFlag(fa)).isFalse();
    }

    @Test
    void ledgerTransferRowsFollowTheTransferOptionRowsAreFalse() throws Exception {
        long dep = transfer("DEPOSIT", "50000.00", "2026-11-02T10:00:00");
        long wd = transfer("WITHDRAWAL", "1000.00", "2026-11-02T11:00:00");
        long trade = open("200", "300.00", "2026-11-03T10:00:00");

        assertThat(ledgerRow("DEPOSIT", dep).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(ledgerRow("WITHDRAWAL", wd).get("hasAuditLog").asBoolean()).isFalse();

        send(put("/api/cash/transfers/" + wd), transferBody("WITHDRAWAL", "1200.00", "2026-11-02T11:00:00"), 200);
        editNote(trade, "改过");

        assertThat(ledgerRow("DEPOSIT", dep).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(ledgerRow("WITHDRAWAL", wd).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(storedTransferFlag(wd)).isTrue();
        assertThat(storedTransferFlag(dep)).isFalse();
        // option ledger rows have no 历史 link: always false, even when the trade was edited
        assertThat(ledgerRow("OPTION_OPEN", trade).get("hasAuditLog").asBoolean()).isFalse();
    }

    @Test
    void depositEditTurnsItsLedgerRowTrue() throws Exception {
        long dep = transfer("DEPOSIT", "1000.00", "2026-11-02T10:00:00");
        assertThat(ledgerRow("DEPOSIT", dep).get("hasAuditLog").asBoolean()).isFalse();
        send(put("/api/cash/transfers/" + dep), transferBody("DEPOSIT", "1500.00", "2026-11-02T10:00:00"), 200);
        assertThat(ledgerRow("DEPOSIT", dep).get("hasAuditLog").asBoolean()).isTrue();
    }

    @Test
    void rejectedEditsLeaveTheFlagFalse() throws Exception {
        long dep = transfer("DEPOSIT", "1000.00", "2026-11-02T10:00:00");
        long trade = open("5", "30.00", "2026-11-03T10:00:00"); // freezes 500.00

        // the deposit cannot shrink below what the put's collateral needs
        assertThat(send(put("/api/cash/transfers/" + dep), transferBody("DEPOSIT", "100.00", "2026-11-02T10:00:00"),
                422).get("code").asText()).isEqualTo("CASH_INSUFFICIENT");
        assertThat(send(put("/api/cash/transfers/" + dep), transferBody("DEPOSIT", "1000.00", "2026-11-02T10:00:00"),
                422).get("code").asText()).isEqualTo("NO_CHANGES");
        JsonNode t = getJson(TRADES + "/" + trade);
        ObjectNode n = json.createObjectNode();
        n.put("contracts", t.get("contracts").asInt());
        n.put("netAmount", t.get("netAmount").asText());
        n.put("tradedAtLocal", t.get("tradedAtLocal").asText());
        assertThat(send(put(TRADES + "/" + trade), n, 422).get("code").asText()).isEqualTo("NO_CHANGES");
        n.put("contracts", 3); // needs 1,500.00 collateral, only 1,000.00 available before it
        assertThat(send(put(TRADES + "/" + trade), n, 422).get("code").asText()).isEqualTo("CASH_INSUFFICIENT");

        assertThat(getJson("/api/audit-log").get("items")).isEmpty();
        assertThat(ledgerRow("DEPOSIT", dep).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(tradeRow(trade).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(getJson(TRADES + "/" + trade).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(storedTransferFlag(dep)).isFalse();
        assertThat(storedTradeFlag(trade)).isFalse();
    }

    @Test
    void responsesReadTheStoredFlagNotTheAuditLog() throws Exception {
        long dep = transfer("DEPOSIT", "50000.00", "2026-11-02T10:00:00");
        long trade = open("200", "300.00", "2026-11-03T10:00:00");

        // audit records alone (no edit through the API) do not change what the rows show
        plantAudit(AuditObjectType.OPTION_TRADE, trade);
        plantAudit(AuditObjectType.CASH_TRANSFER, dep);
        assertThat(tradeRow(trade).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(getJson(TRADES + "/" + trade).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(ledgerRow("DEPOSIT", dep).get("hasAuditLog").asBoolean()).isFalse();

        // the stored flag alone is what they show
        jdbc.update("UPDATE option_trade SET has_audit_log = TRUE WHERE id = ?", trade);
        jdbc.update("UPDATE cash_transfer SET has_audit_log = TRUE WHERE id = ?", dep);
        assertThat(tradeRow(trade).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(getJson(TRADES + "/" + trade).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(ledgerRow("DEPOSIT", dep).get("hasAuditLog").asBoolean()).isTrue();
    }

    @Test
    void emptyPagesStillWork() throws Exception {
        assertThat(getJson(TRADES).get("items")).isEmpty();
        assertThat(getJson("/api/closed-trades").get("items")).isEmpty();
        assertThat(getJson("/api/cash/ledger").get("items")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expectedStatus);
        return raw.isEmpty() ? null : json.readTree(raw);
    }

    private JsonNode send(MockHttpServletRequestBuilder req, ObjectNode body, int expectedStatus) throws Exception {
        return call(req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), expectedStatus);
    }

    private JsonNode getJson(String url) throws Exception {
        return call(get(URI.create(url)), 200);
    }

    private ObjectNode transferBody(String type, String amount, String nyTime) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        return n;
    }

    private long transfer(String type, String amount, String nyTime) throws Exception {
        return send(post("/api/cash/transfers"), transferBody(type, amount, nyTime), 201).get("id").asLong();
    }

    private ObjectNode openBody(String strike, String net, String nyTime) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "IXQZ");
        n.put("expirationDate", "2026-12-18");
        n.put("strike", strike);
        n.put("optionType", "PUT");
        n.put("action", "STO");
        n.put("contracts", 1);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        return n;
    }

    private long open(String strike, String net, String nyTime) throws Exception {
        return send(post(TRADES), openBody(strike, net, nyTime), 201).get("id").asLong();
    }

    /** STO 1 F 2026-12-18 12.345 P, multiplier 1, net 0.50. */
    private long openF(String nyTime) throws Exception {
        ObjectNode n = openBody("12.345", "0.50", nyTime);
        n.put("symbol", "F");
        n.put("multiplier", 1);
        return send(post(TRADES), n, 201).get("id").asLong();
    }

    private long btc(String symbol, String strike, int contracts, String net, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", "2026-12-18");
        n.put("strike", strike);
        n.put("optionType", "PUT");
        n.put("action", "BTC");
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        return send(post(TRADES + "/closes"), n, 201).get("id").asLong();
    }

    private JsonNode editNote(long id, String note) throws Exception {
        JsonNode t = getJson(TRADES + "/" + id);
        ObjectNode n = json.createObjectNode();
        n.put("contracts", t.get("contracts").asInt());
        n.put("netAmount", t.get("netAmount").asText());
        n.put("tradedAtLocal", t.get("tradedAtLocal").asText());
        n.put("note", note);
        return send(put(TRADES + "/" + id), n, 200);
    }

    private void plantAudit(AuditObjectType type, long objectId) {
        jdbc.update("INSERT INTO audit_record (account_id, operated_at, operation, object_type, object_id, summary,"
                        + " before_json, after_json, changes_json, side_effects_json)"
                        + " VALUES (1, ?, 'UPDATE', ?, ?, 'planted', '{}', '{}', '[]', '[]')",
                Timestamp.from(NOW), type.name(), objectId);
    }

    private boolean storedTradeFlag(long id) {
        return jdbc.queryForObject("SELECT has_audit_log FROM option_trade WHERE id = ?", Boolean.class, id);
    }

    private boolean storedTransferFlag(long id) {
        return jdbc.queryForObject("SELECT has_audit_log FROM cash_transfer WHERE id = ?", Boolean.class, id);
    }

    private JsonNode tradeRow(long id) throws Exception {
        return find(getJson(TRADES + "?size=100").get("items"), id);
    }

    private JsonNode closedRow(long id) throws Exception {
        return find(getJson("/api/closed-trades?size=100").get("items"), id);
    }

    private JsonNode ledgerRow(String sourceType, long sourceId) throws Exception {
        for (JsonNode n : getJson("/api/cash/ledger?size=100").get("items")) {
            if (n.get("sourceType").asText().equals(sourceType) && n.get("sourceId").asLong() == sourceId) {
                return n;
            }
        }
        throw new AssertionError("no ledger row " + sourceType + " " + sourceId);
    }

    private static JsonNode find(JsonNode items, long id) {
        for (JsonNode n : items) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("no row with id = " + id);
    }
}
