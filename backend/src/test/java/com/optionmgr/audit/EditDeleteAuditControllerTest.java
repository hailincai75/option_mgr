package com.optionmgr.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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

/**
 * FEAT-005 acceptance criteria (backend part): edit / delete of trades and transfers, replay checks and audit.
 * "Now" is 2026-11-11 10:00 New York (15:00Z).
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class EditDeleteAuditControllerTest {

    static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");
    static final String TRADES = "/api/option-trades";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock editTestClock() {
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
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
    }

    // ---------------------------------------------------------------- HTTP helpers

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

    private long deposit(String amount, String nyTime) throws Exception {
        return transfer("DEPOSIT", amount, nyTime);
    }

    private long transfer(String type, String amount, String nyTime) throws Exception {
        return send(post("/api/cash/transfers"), transferBody(type, amount, nyTime, null), 201).get("id").asLong();
    }

    private ObjectNode transferBody(String type, String amount, String nyTime, String remark) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        n.put("remark", remark);
        return n;
    }

    private long open(String symbol, String strike, int contracts, int multiplier, String net, String nyTime)
            throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", "2026-12-18");
        n.put("strike", strike);
        n.put("optionType", "PUT");
        n.put("action", "STO");
        n.put("contracts", contracts);
        n.put("multiplier", multiplier);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
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

    /** Edit body pre-filled from the trade's current values. */
    private ObjectNode editBody(long id) throws Exception {
        JsonNode t = getJson(TRADES + "/" + id);
        ObjectNode n = json.createObjectNode();
        n.put("contracts", t.get("contracts").asInt());
        n.put("netAmount", t.get("netAmount").asText());
        n.put("tradedAtLocal", t.get("tradedAtLocal").asText());
        if (t.get("action").asText().endsWith("O")) {
            n.put("multiplier", t.get("multiplier").asInt());
        }
        n.set("note", t.get("note"));
        return n;
    }

    private JsonNode editOk(long id, Consumer<ObjectNode> change) throws Exception {
        ObjectNode body = editBody(id);
        change.accept(body);
        return send(put(TRADES + "/" + id), body, 200);
    }

    /** Asserts a rejected edit (422 code) that changed nothing; returns the message. */
    private String editRejected(long id, Consumer<ObjectNode> change, String code) throws Exception {
        ObjectNode body = editBody(id);
        change.accept(body);
        return unchangedAfter(() -> send(put(TRADES + "/" + id), body, 422), code);
    }

    private String deleteRejected(String url, String code) throws Exception {
        return unchangedAfter(() -> call(delete(url), 422), code);
    }

    interface Action {
        JsonNode run() throws Exception;
    }

    private String unchangedAfter(Action action, String code) throws Exception {
        List<Object> before = state();
        JsonNode err = action.run();
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("details")).isEmpty();
        assertThat(state()).isEqualTo(before);
        return err.get("message").asText();
    }

    /** Everything an edit/delete can change. */
    private List<Object> state() throws Exception {
        return List.of(getJson("/api/cash/summary"), getJson("/api/cash/ledger?size=100"),
                getJson(TRADES + "?size=100"), getJson("/api/option-positions"), auditCount());
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class);
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    private JsonNode audit(String query) throws Exception {
        return getJson("/api/audit-log" + (query.isEmpty() ? "" : "?" + query));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("text").asText()));
        return out;
    }

    private JsonNode aaplPosition() throws Exception {
        for (JsonNode p : getJson("/api/option-positions").get("positions")) {
            if (p.get("contractLabel").asText().equals("AAPL 2026-12-18 200 P")) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- scenario A

    private long d1;
    private long l1;
    private long l2;
    private long c1;

    private void scenarioA() throws Exception {
        d1 = deposit("80000.00", "2026-10-26T10:00");
        l1 = open("AAPL", "200", 2, 100, "698.70", "2026-11-03T10:30");
        l2 = open("AAPL", "200", 1, 100, "360.00", "2026-11-04T11:00");
        c1 = btc("AAPL", "200", 2, "241.30", "2026-11-05T10:00");
        assertSummary("80817.40", "20000.00", "60817.40");
    }

    @Test
    void ac1_editOpenNetAmount() throws Exception {
        scenarioA();
        long ledgerIdBefore = getJson(TRADES + "/" + l2).get("ledgerEntryId").asLong();
        JsonNode t = editOk(l2, n -> n.put("netAmount", "365.00"));
        assertThat(t.get("netAmount").asText()).isEqualTo("365.00");
        assertThat(t.get("cashChange").asText()).isEqualTo("365.00");
        assertThat(t.get("ledgerEntryId").asLong()).isEqualTo(ledgerIdBefore);
        assertSummary("80822.40", "20000.00", "60822.40");
        JsonNode p = aaplPosition();
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("365.00");
        assertThat(p.get("averageNetPerContract").asText()).isEqualTo("365.00");
        JsonNode ledger = getJson("/api/cash/ledger");
        assertThat(ledger.get("totalItems").asLong()).isEqualTo(4);

        JsonNode a = audit("").get("items").get(0);
        assertThat(a.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(a.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(a.get("objectId").asLong()).isEqualTo(l2);
        assertThat(a.get("summary").asText()).isEqualTo("STO AAPL 2026-12-18 200 P");
        assertThat(texts(a.get("changes"))).containsExactly("净额：360.00 → 365.00");
        assertThat(a.get("sideEffects")).isEmpty();
        assertThat(a.get("before").get("netAmount").asText()).isEqualTo("360.00");
        assertThat(a.get("before").get("cashChange").asText()).isEqualTo("360.00");
        assertThat(a.get("before").get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(a.get("after").get("netAmount").asText()).isEqualTo("365.00");
        assertThat(a.get("operatedAt").asText()).isEqualTo("2026-11-11T15:00:00Z");
    }

    @Test
    void ac2_editCloseNetAmountChangesPnl() throws Exception {
        scenarioA();
        JsonNode t = editOk(c1, n -> n.put("netAmount", "250.00"));
        assertThat(t.get("realizedPnl").asText()).isEqualTo("448.70");
        assertSummary("80808.70", "20000.00", "60808.70");
        assertThat(getJson(TRADES + "/" + c1).get("realizedPnl").asText()).isEqualTo("448.70");
    }

    @Test
    void ac3_editCloseContracts() throws Exception {
        scenarioA();
        // FEAT-051: a close's contracts follow its selected lots; changing only the contracts is rejected
        assertThat(editRejected(c1, n -> n.put("contracts", 1), "CLOSE_LOTS_INVALID"))
                .isEqualTo("平仓张数 1 张与所选批次张数之和 2 张不一致");
        assertThat(editRejected(c1, n -> n.put("contracts", 3), "CLOSE_LOTS_INVALID"))
                .isEqualTo("平仓张数 3 张与所选批次张数之和 2 张不一致");

        JsonNode t = editOk(c1, n -> {
            n.remove("contracts");
            n.putArray("lotTradeIds").add(l2).add(l1);
        });
        assertThat(t.get("contracts").asInt()).isEqualTo(3);
        assertThat(t.get("frozenChange").asText()).isEqualTo("-60000.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("817.40");
        assertThat(t.get("coveredLots")).hasSize(2);
        assertSummary("80817.40", "0.00", "80817.40");
        assertThat(aaplPosition()).isNull();
        JsonNode row = getJson("/api/cash/ledger").get("items").get(0);
        assertThat(row.get("description").asText()).isEqualTo("BTC 3 张 AAPL 2026-12-18 200 P");
        JsonNode a = audit("").get("items").get(0);
        assertThat(texts(a.get("changes"))).containsExactly(
                "被平批次：2026-11-03 10:30 2 张 → 2026-11-03 10:30 2 张、2026-11-04 11:00 1 张",
                "张数：2 → 3", "冻结变动：−40,000.00 → −60,000.00");
        assertThat(a.get("before").get("selectedLots")).hasSize(1);
        assertThat(a.get("before").get("selectedLots").get(0).get("tradeId").asLong()).isEqualTo(l1);
        assertThat(a.get("after").get("selectedLots")).hasSize(2);
        assertThat(a.get("after").get("selectedLots").get(1).get("tradedAtLocal").asText())
                .isEqualTo("2026-11-04T11:00:00");
    }

    @Test
    void ac4_editOpenSelectedByLaterClose() throws Exception {
        scenarioA();
        // FEAT-051 (Q255): the close keeps its selected lot L1; changing L1's size is rejected (no re-matching)
        assertThat(editRejected(l1, n -> n.put("contracts", 3), "CLOSE_LOTS_INVALID"))
                .isEqualTo("修改后，2026-11-05 10:00 BTC 2 张 AAPL 2026-12-18 200 P 选中的批次 2026-11-03 10:30 STO 3 张 "
                        + "张数与平仓不一致（所选批次共 3 张，平仓 2 张）");
        assertThat(editRejected(l1, n -> n.put("contracts", 1), "CLOSE_LOTS_INVALID"))
                .isEqualTo("修改后，2026-11-05 10:00 BTC 2 张 AAPL 2026-12-18 200 P 选中的批次 2026-11-03 10:30 STO 1 张 "
                        + "张数与平仓不一致（所选批次共 1 张，平仓 2 张）");

        // only the net amount: allowed, the close's P&L follows
        editOk(l1, n -> n.put("netAmount", "700.00"));
        JsonNode close = getJson(TRADES + "/" + c1);
        assertThat(close.get("frozenChange").asText()).isEqualTo("-40000.00");
        assertThat(close.get("realizedPnl").asText()).isEqualTo("458.70");
        assertThat(close.get("coveredLots")).hasSize(1);
        assertThat(close.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(l1);
        assertSummary("80818.70", "20000.00", "60818.70");
        assertThat(audit("").get("items").get(0).get("sideEffects")).isEmpty();
    }

    @Test
    void ac5_editTime() throws Exception {
        scenarioA();
        assertThat(editRejected(l1, n -> n.put("tradedAtLocal", "2026-11-06T10:00"), "CLOSE_LOTS_INVALID"))
                .isEqualTo("修改后，2026-11-05 10:00 BTC 2 张 AAPL 2026-12-18 200 P 选中的批次 2026-11-06 10:00 STO 2 张 "
                        + "在该平仓成交时还未开仓");
        assertThat(editRejected(c1, n -> n.put("tradedAtLocal", "2026-11-03T10:00"), "NO_POSITION_TO_CLOSE"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时没有持仓，无法平仓");

        ObjectNode future = editBody(c1);
        future.put("tradedAtLocal", "2026-11-11T10:01");
        JsonNode err = send(put(TRADES + "/" + c1), future, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").get(0).get("field").asText()).isEqualTo("tradedAtLocal");
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("成交时间不能晚于当前时间");
    }

    @Test
    void ac6_deleteOpen() throws Exception {
        scenarioA();
        assertThat(deleteRejected(TRADES + "/" + l1, "CLOSE_LOTS_INVALID"))
                .isEqualTo("该开仓被 2026-11-05 10:00 的 BTC 2 张平掉（所选批次 2026-11-03 10:30 STO 2 张），"
                        + "请先修改或删除那笔平仓");

        call(delete(TRADES + "/" + l2), 204);
        assertSummary("80457.40", "0.00", "80457.40");
        assertThat(aaplPosition()).isNull();
        JsonNode list = getJson(TRADES);
        List<Long> ids = new ArrayList<>();
        list.get("items").forEach(i -> ids.add(i.get("id").asLong()));
        assertThat(ids).containsExactly(c1, l1);
        assertThat(getJson("/api/cash/ledger").get("totalItems").asLong()).isEqualTo(3);
        assertThat(call(get(TRADES + "/" + l2), 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(delete(TRADES + "/" + l2), 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(send(put(TRADES + "/" + l2), editBody(l1), 404).get("code").asText()).isEqualTo("NOT_FOUND");

        JsonNode a = audit("").get("items").get(0);
        assertThat(a.get("operation").asText()).isEqualTo("DELETE");
        assertThat(a.get("objectId").asLong()).isEqualTo(l2);
        assertThat(a.get("after").isNull()).isTrue();
        assertThat(a.get("changes")).isEmpty();
        JsonNode b = a.get("before");
        assertThat(b.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 200 P");
        assertThat(b.get("action").asText()).isEqualTo("STO");
        assertThat(b.get("contracts").asInt()).isEqualTo(1);
        assertThat(b.get("netAmount").asText()).isEqualTo("360.00");
        assertThat(b.get("tradedAtLocal").asText()).isEqualTo("2026-11-04T11:00:00");
        assertThat(b.get("cashChange").asText()).isEqualTo("360.00");
        assertThat(b.get("frozenChange").asText()).isEqualTo("20000.00");
    }

    @Test
    void ac7_deleteCloseRestoresFrozen() throws Exception {
        scenarioA();
        call(delete(TRADES + "/" + c1), 204);
        assertSummary("81058.70", "60000.00", "21058.70");
        JsonNode p = aaplPosition();
        assertThat(p.get("contracts").asInt()).isEqualTo(3);
        assertThat(p.get("lotCount").asInt()).isEqualTo(2);
        assertThat(p.get("allowedCloseContracts").toString()).isEqualTo("[2,3]");

        emptyDatabase();
        scenarioA();
        transfer("WITHDRAWAL", "50000.00", "2026-11-06T10:00");
        assertSummary("30817.40", "20000.00", "10817.40");
        assertThat(deleteRejected(TRADES + "/" + c1, "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：删除后 2026-11-06 10:00 时可用现金将变为 −28,941.30");
    }

    @Test
    void ac8_editDeposit() throws Exception {
        scenarioA();
        JsonNode t = send(put("/api/cash/transfers/" + d1),
                transferBody("DEPOSIT", "70000.00", "2026-10-26T10:00", null), 200);
        assertThat(t.get("amount").asText()).isEqualTo("70000.00");
        assertSummary("70817.40", "20000.00", "50817.40");
        JsonNode rows = getJson("/api/cash/ledger").get("items");
        assertThat(rows.get(3).get("amount").asText()).isEqualTo("70000.00");
        assertThat(rows.get(0).get("balanceAfter").asText()).isEqualTo("70817.40");

        // Q41: a later STO put is not re-checked against Q18, only Q19
        send(put("/api/cash/transfers/" + d1), transferBody("DEPOSIT", "59000.00", "2026-10-26T10:00", null), 200);
        assertSummary("59817.40", "20000.00", "39817.40");

        assertThat(unchangedAfter(() -> send(put("/api/cash/transfers/" + d1),
                transferBody("DEPOSIT", "58900.00", "2026-10-26T10:00", null), 422), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：修改后 2026-11-04 11:00 时可用现金将变为 −41.30");
        assertThat(unchangedAfter(() -> send(put("/api/cash/transfers/" + d1),
                transferBody("DEPOSIT", "59000.00", "2026-11-04T12:00", null), 422), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：修改后 2026-11-03 10:30 时可用现金将变为 −39,301.30");
    }

    @Test
    void ac9_transferTypeChangeAndDelete() throws Exception {
        deposit("1000.00", "2026-11-02T10:00");
        long second = deposit("500.00", "2026-11-03T10:00");
        send(put("/api/cash/transfers/" + second), transferBody("WITHDRAWAL", "500.00", "2026-11-03T10:00", null), 200);
        assertSummary("500.00", "0.00", "500.00");
        JsonNode row = getJson("/api/cash/ledger").get("items").get(0);
        assertThat(row.get("amount").asText()).isEqualTo("-500.00");
        assertThat(row.get("sourceType").asText()).isEqualTo("WITHDRAWAL");
        JsonNode a = audit("").get("items").get(0);
        assertThat(a.get("objectType").asText()).isEqualTo("CASH_TRANSFER");
        assertThat(a.get("summary").asText()).isEqualTo("入金 500.00");
        assertThat(texts(a.get("changes"))).containsExactly("类型：入金 → 出金");

        emptyDatabase();
        deposit("1000.00", "2026-11-02T10:00");
        long dup = deposit("1000.00", "2026-11-02T10:00");
        call(delete("/api/cash/transfers/" + dup), 204);
        assertSummary("1000.00", "0.00", "1000.00");
        assertThat(getJson("/api/cash/ledger").get("totalItems").asLong()).isEqualTo(1);
        JsonNode del = audit("").get("items").get(0);
        assertThat(del.get("operation").asText()).isEqualTo("DELETE");
        assertThat(del.get("objectType").asText()).isEqualTo("CASH_TRANSFER");
        assertThat(del.get("objectId").asLong()).isEqualTo(dup);
        assertThat(call(delete("/api/cash/transfers/" + dup), 404).get("code").asText()).isEqualTo("NOT_FOUND");

        emptyDatabase();
        scenarioA();
        assertThat(deleteRejected("/api/cash/transfers/" + d1, "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：删除后 2026-11-03 10:30 时可用现金将变为 −39,301.30");
    }

    @Test
    void ac10_selectedLotsKeepTheCloseFrozenRelease() throws Exception {
        // FEAT-051 (Q255, cancels Q39): a BTC's released frozen cash follows its stored lots; the edit that used to
        // re-match it (and correct its frozen change as a side effect) is now rejected
        deposit("100.00", "2026-10-26T10:00");
        long f1 = open("F", "12.345", 1, 1, "0.50", "2026-11-02T10:00");
        open("F", "12.345", 1, 1, "0.50", "2026-11-03T10:00");
        long fc = btc("F", "12.345", 2, "0.20", "2026-11-05T10:00");
        assertSummary("100.80", "0.00", "100.80");
        assertThat(getJson(TRADES + "/" + fc).get("frozenChange").asText()).isEqualTo("-24.70");

        assertThat(editRejected(f1, n -> n.put("contracts", 2), "CLOSE_LOTS_INVALID"))
                .isEqualTo("修改后，2026-11-05 10:00 BTC 2 张 F 2026-12-18 12.345 P 选中的批次 2026-11-02 10:00 STO 2 张 "
                        + "张数与平仓不一致（所选批次共 3 张，平仓 2 张）");

        editOk(f1, n -> n.put("netAmount", "0.60"));
        JsonNode close = getJson(TRADES + "/" + fc);
        assertThat(close.get("frozenChange").asText()).isEqualTo("-24.70");
        assertThat(close.get("coveredLots")).hasSize(2);
        assertThat(close.get("realizedPnl").asText()).isEqualTo("0.90");
        assertSummary("100.90", "0.00", "100.90");
        JsonNode a = audit("").get("items").get(0);
        assertThat(texts(a.get("changes"))).containsExactly("净额：0.50 → 0.60");
        assertThat(a.get("sideEffects")).isEmpty();
    }

    @Test
    void ac11_validationAndNoChanges() throws Exception {
        scenarioA();
        for (var change : List.<Consumer<ObjectNode>>of(n -> n.put("contracts", 0),
                n -> n.put("contracts", new BigDecimal("1.5")), n -> n.put("netAmount", "0"),
                n -> n.put("netAmount", "1.005"), n -> n.put("note", "x".repeat(501)))) {
            ObjectNode body = editBody(l2);
            change.accept(body);
            assertThat(send(put(TRADES + "/" + l2), body, 400).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        JsonNode amountZero = send(put("/api/cash/transfers/" + d1),
                transferBody("DEPOSIT", "0", "2026-10-26T10:00", null), 400);
        assertThat(amountZero.get("details").get(0).get("field").asText()).isEqualTo("amount");

        assertThat(editRejected(l2, n -> { }, "NO_CHANGES")).isEqualTo("没有修改");
        assertThat(editRejected(l2, n -> n.put("netAmount", "360"), "NO_CHANGES")).isEqualTo("没有修改");
        assertThat(unchangedAfter(() -> send(put("/api/cash/transfers/" + d1),
                transferBody("DEPOSIT", "80000", "2026-10-26T10:00", "  "), 422), "NO_CHANGES")).isEqualTo("没有修改");
        assertThat(auditCount()).isZero();

        // contract / action properties in the body are ignored; multiplier of a close is ignored
        JsonNode t = editOk(c1, n -> {
            n.put("symbol", "MSFT");
            n.put("action", "STC");
            n.put("multiplier", 10);
            n.put("note", "改备注");
        });
        assertThat(t.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 200 P");
        assertThat(t.get("action").asText()).isEqualTo("BTC");
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("note").asText()).isEqualTo("改备注");
    }

    @Test
    void editKeepsEntryOrderAndRechecksEditedStoPutQ18() throws Exception {
        deposit("50000.00", "2026-10-26T10:00");
        long a = open("AAPL", "200", 1, 100, "1.00", "2026-11-03T10:00");
        long b = open("MSFT", "100", 1, 100, "1.00", "2026-11-04T10:00");
        // moving b to the same instant as a: a (entered first) stays first
        editOk(b, n -> n.put("tradedAtLocal", "2026-11-03T10:00:00"));
        JsonNode items = getJson(TRADES).get("items");
        assertThat(items.get(0).get("id").asLong()).isEqualTo(b);
        assertThat(items.get(1).get("id").asLong()).isEqualTo(a);
        JsonNode ledger = getJson("/api/cash/ledger").get("items");
        assertThat(ledger.get(0).get("sourceId").asLong()).isEqualTo(b);

        // Q18 for the edited STO put itself: before it 50,000 + 1 - 20,000 (a) = 30,001 is available
        assertThat(editRejected(b, n -> n.put("contracts", 4), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足以担保卖出 put：需要 40,000.00，可用 30,001.00");
        editOk(b, n -> n.put("contracts", 3));
        assertSummary("50002.00", "50000.00", "2.00");
    }

    @Test
    void ac12_auditListOrderAndFilter() throws Exception {
        scenarioA();
        editOk(l2, n -> n.put("netAmount", "365.00"));
        editOk(l2, n -> n.put("note", "加仓"));
        call(delete(TRADES + "/" + l2), 204);

        JsonNode all = audit("");
        assertThat(all.get("totalItems").asLong()).isEqualTo(3);
        JsonNode items = all.get("items");
        assertThat(items.get(0).get("operation").asText()).isEqualTo("DELETE");
        assertThat(texts(items.get(1).get("changes"))).containsExactly("备注：— → 加仓");
        assertThat(texts(items.get(2).get("changes"))).containsExactly("净额：360.00 → 365.00");
        assertThat(items.get(0).get("id").asLong()).isGreaterThan(items.get(1).get("id").asLong());

        JsonNode none = audit("objectType=OPTION_TRADE&objectId=" + c1);
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("totalItems").asLong()).isZero();
        assertThat(none.get("totalPages").asInt()).isZero();
        assertThat(audit("objectType=OPTION_TRADE&objectId=" + l2).get("totalItems").asLong()).isEqualTo(3);
        assertThat(audit("objectType=CASH_TRANSFER").get("totalItems").asLong()).isZero();
        assertThat(audit("page=2&size=2").get("items")).hasSize(1);

        assertThat(call(get(URI.create("/api/audit-log?objectId=1")), 400).get("details").get(0).get("field").asText())
                .isEqualTo("objectType");
        assertThat(call(get(URI.create("/api/audit-log?objectType=FOO")), 400).get("code").asText())
                .isEqualTo("BAD_REQUEST");
        assertThat(call(get(URI.create("/api/audit-log?size=101")), 400).get("code").asText())
                .isEqualTo("VALIDATION_ERROR");
        // audit records cannot be changed
        assertThat(call(delete("/api/audit-log"), 405).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
