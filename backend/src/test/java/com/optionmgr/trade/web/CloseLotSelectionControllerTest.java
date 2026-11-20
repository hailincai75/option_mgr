package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.account.web.AccountRef;
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
 * FEAT-051 acceptance criteria (backend part): choose the lots a close closes. ZZQ is a fictional symbol; 甲 is the
 * default account (1), 乙 a second account. "Now" is 2026-11-20 10:00 New York.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class CloseLotSelectionControllerTest {

    static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    static final String TRADES = "/api/option-trades";
    static final String CLOSES = TRADES + "/closes";
    static final String CALL = "ZZQ 2026-12-18 9 C";
    static final String PUT = "ZZQ 2026-12-18 8 P";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock closeLotTestClock() {
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

    // ---------------------------------------------------------------- helpers

    private JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expectedStatus);
        return raw.isEmpty() ? null : json.readTree(raw);
    }

    private JsonNode send(MockHttpServletRequestBuilder req, ObjectNode body, int expectedStatus) throws Exception {
        return call(req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)),
                expectedStatus);
    }

    private JsonNode getJson(String url) throws Exception {
        return call(get(URI.create(url)), 200);
    }

    private void deposit(String account, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        send(post("/api/cash/transfers").header(AccountRef.HEADER, account), n, 201);
    }

    private void transferIn(String account, int shares, String amount, String date) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", "ZZQ");
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradeDate", date);
        send(post("/api/stock-trades").header(AccountRef.HEADER, account), n, 201);
    }

    private ObjectNode contract(String strike, String type) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "ZZQ");
        n.put("expirationDate", "2026-12-18");
        n.put("strike", strike);
        n.put("optionType", type);
        return n;
    }

    private long sto(String account, String strike, String type, int contracts, String net, String nyTime)
            throws Exception {
        ObjectNode n = contract(strike, type);
        n.put("action", "STO");
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        return send(post(TRADES).header(AccountRef.HEADER, account), n, 201).get("id").asLong();
    }

    /** BTC of the call contract with the given lots (none: FIFO mode with {@code contracts}). */
    private ObjectNode btc(String strike, String type, String net, String nyTime, Long... lots) {
        ObjectNode n = contract(strike, type);
        n.put("action", "BTC");
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        n.putNull("note");
        if (lots.length > 0) {
            var arr = n.putArray("lotTradeIds");
            for (Long l : lots) {
                arr.add(l);
            }
        }
        return n;
    }

    private ObjectNode btcCall(String net, String nyTime, Long... lots) {
        return btc("9", "CALL", net, nyTime, lots);
    }

    private static ObjectNode with(ObjectNode n, Consumer<ObjectNode> change) {
        ObjectNode copy = n.deepCopy();
        change.accept(copy);
        return copy;
    }

    private JsonNode closeOk(ObjectNode body) throws Exception {
        return send(post(CLOSES), body, 201);
    }

    /** A rejected request (status / code) that changed nothing; returns the error body. */
    private JsonNode rejected(MockHttpServletRequestBuilder req, ObjectNode body, int status, String code)
            throws Exception {
        List<Object> before = state();
        JsonNode err = body == null ? call(req, status) : send(req, body, status);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(state()).isEqualTo(before);
        return err;
    }

    private String closeRejected(ObjectNode body) throws Exception {
        JsonNode err = rejected(post(CLOSES), body, 422, "CLOSE_LOTS_INVALID");
        assertThat(err.get("details")).isEmpty();
        return err.get("message").asText();
    }

    private List<Object> state() throws Exception {
        return List.of(getJson("/api/cash/summary"), getJson(TRADES + "?size=100"), getJson("/api/option-positions"),
                jdbc.queryForList("SELECT * FROM option_close_lot ORDER BY close_trade_id, lot_trade_id"),
                jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class));
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    private JsonNode position(String label) throws Exception {
        for (JsonNode p : getJson("/api/option-positions").get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    /** "tradeId tradedAtLocal contracts net" per lot. */
    private static List<String> lots(JsonNode lots, String netField) {
        List<String> out = new ArrayList<>();
        lots.forEach(l -> out.add(l.get("tradeId").asLong() + " " + l.get("tradedAtLocal").asText() + " "
                + l.get(l.has("remainingContracts") ? "remainingContracts" : "contracts").asInt() + " "
                + l.get(netField).asText()));
        return out;
    }

    private ObjectNode editBody(long id) throws Exception {
        JsonNode t = getJson(TRADES + "/" + id);
        ObjectNode n = json.createObjectNode();
        n.put("netAmount", t.get("netAmount").asText());
        n.put("tradedAtLocal", t.get("tradedAtLocal").asText());
        n.set("note", t.get("note"));
        if (t.get("action").asText().endsWith("O")) {
            n.put("contracts", t.get("contracts").asInt());
            n.put("multiplier", t.get("multiplier").asInt());
        }
        return n;
    }

    // ---------------------------------------------------------------- scenarios

    private long a;
    private long b;

    /** Scenario Z (甲, short call): A = STO 1 18.34 (11-10), B = STO 1 37.34 (11-11); 200 shares ZZQ. */
    private void scenarioZ() throws Exception {
        deposit("1", "1000.00", "2026-11-01T10:00");
        transferIn("1", 200, "1600.00", "2026-11-02");
        a = sto("1", "9", "CALL", 1, "18.34", "2026-11-10T10:00");
        b = sto("1", "9", "CALL", 1, "37.34", "2026-11-11T10:00");
        assertSummary("1055.68", "0.00", "1055.68");
    }

    private long p1;
    private long p2;

    /** Scenario P (甲, short put): P1 = STO 2 30.00 (frozen 1,600), P2 = STO 1 21.00 (frozen 800). */
    private void scenarioP() throws Exception {
        deposit("1", "30000.00", "2026-11-01T10:00");
        p1 = sto("1", "8", "PUT", 2, "30.00", "2026-11-10T10:00");
        p2 = sto("1", "8", "PUT", 1, "21.00", "2026-11-11T10:00");
        assertSummary("30051.00", "2400.00", "27651.00");
    }

    private void assertStock(long covered, long available) throws Exception {
        for (JsonNode p : getJson("/api/stock-positions").get("positions")) {
            if (p.get("symbol").asText().equals("ZZQ")) {
                assertThat(p.get("coveredShares").asLong()).isEqualTo(covered);
                assertThat(p.get("availableShares").asLong()).isEqualTo(available);
                return;
            }
        }
        throw new AssertionError("no ZZQ stock row");
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_lotsListedEarliestIsTheCloseDefault() throws Exception {
        scenarioZ();
        JsonNode p = position(CALL);
        assertThat(p.get("closeAction").asText()).isEqualTo("BTC");
        assertThat(lots(p.get("lots"), "remainingNetAmount"))
                .containsExactly(a + " 2026-11-10T10:00:00 1 18.34", b + " 2026-11-11T10:00:00 1 37.34");
        assertThat(p.get("lots").get(0).get("closeDefault").asBoolean()).isTrue();
        assertThat(p.get("lots").get(1).get("closeDefault").asBoolean()).isFalse();
        assertThat(p.get("lots").get(1).get("closeDefault").isBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- AC 2 / 3 / 9

    @Test
    void ac2_closeTheLaterLot() throws Exception {
        scenarioZ();
        JsonNode t = closeOk(btcCall("15.10", "2026-11-19T10:00", b));
        assertThat(t.get("contracts").asInt()).isEqualTo(1);
        assertThat(t.get("cashChange").asText()).isEqualTo("-15.10");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(lots(t.get("coveredLots"), "netAmount")).containsExactly(b + " 2026-11-11T10:00:00 1 37.34");
        assertThat(t.get("coveredLots").get(0).get("frozen").asText()).isEqualTo("0.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(getJson(TRADES + "/" + t.get("id").asLong())).isEqualTo(t);
        assertSummary("1040.58", "0.00", "1040.58");

        JsonNode p = position(CALL);
        assertThat(p.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(lots(p.get("lots"), "remainingNetAmount")).containsExactly(a + " 2026-11-10T10:00:00 1 18.34");
        assertThat(p.get("lots").get(0).get("closeDefault").asBoolean()).isTrue();
        assertStock(100, 100);

        JsonNode row = getJson("/api/closed-trades").get("items").get(0);
        assertThat(row.get("openingNetTotal").asText()).isEqualTo("37.34");
        assertThat(row.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(row.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(b);
        JsonNode all = call(get("/api/closed-trades").header(AccountRef.HEADER, "ALL"), 200).get("items").get(0);
        assertThat(all.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(b);
        assertThat(all.get("accountId").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT lot_trade_id FROM option_close_lot WHERE close_trade_id = ?",
                Long.class, t.get("id").asLong())).containsExactly(b);
    }

    @Test
    void ac3_defaultSelectionEqualsFifo_andAc9_fifoModeStoresTheMatchedLot() throws Exception {
        scenarioZ();
        JsonNode t = closeOk(btcCall("15.10", "2026-11-19T10:00", a));
        assertThat(t.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(a);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("3.24");
        assertThat(lots(position(CALL).get("lots"), "remainingNetAmount"))
                .containsExactly(b + " 2026-11-11T10:00:00 1 37.34");
        assertSummary("1040.58", "0.00", "1040.58");

        emptyDatabase();
        scenarioZ();
        // AC 9 (Q257): the trade entry page sends contracts only
        JsonNode fifo = closeOk(with(btcCall("15.10", "2026-11-19T10:00"), n -> n.put("contracts", 1)));
        assertThat(fifo.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(a);
        assertThat(fifo.get("realizedPnl").asText()).isEqualTo("3.24");
        assertThat(jdbc.queryForList("SELECT lot_trade_id FROM option_close_lot WHERE close_trade_id = ?",
                Long.class, fifo.get("id").asLong())).containsExactly(a);
        assertSummary("1040.58", "0.00", "1040.58");
    }

    // ---------------------------------------------------------------- AC 4

    @Test
    void ac4_shortPutLaterLotReleasesItsFrozen() throws Exception {
        scenarioP();
        JsonNode t = closeOk(btc("8", "PUT", "9.65", "2026-11-19T10:00", p2));
        assertThat(t.get("contracts").asInt()).isEqualTo(1);
        assertThat(t.get("frozenChange").asText()).isEqualTo("-800.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("11.35");
        assertSummary("30041.35", "1600.00", "28441.35");
        JsonNode p = position(PUT);
        assertThat(lots(p.get("lots"), "remainingNetAmount")).containsExactly(p1 + " 2026-11-10T10:00:00 2 30.00");
        assertThat(p.get("lots").get(0).get("remainingFrozen").asText()).isEqualTo("1600.00");

        emptyDatabase();
        scenarioP();
        JsonNode both = closeOk(with(btc("8", "PUT", "20.00", "2026-11-19T10:00", p2, p1),
                n -> n.put("contracts", 3)));
        assertThat(both.get("contracts").asInt()).isEqualTo(3);
        assertThat(both.get("frozenChange").asText()).isEqualTo("-2400.00");
        assertThat(both.get("realizedPnl").asText()).isEqualTo("31.00");
        assertThat(lots(both.get("coveredLots"), "netAmount"))
                .containsExactly(p1 + " 2026-11-10T10:00:00 2 30.00", p2 + " 2026-11-11T10:00:00 1 21.00");
        assertSummary("30031.00", "0.00", "30031.00");
        assertThat(position(PUT)).isNull();
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void ac5_selectedLotChecks() throws Exception {
        scenarioZ();
        long other = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"乙\"}"),
                201).get("id").asLong();
        transferIn(String.valueOf(other), 100, "800.00", "2026-11-02");
        long c = sto(String.valueOf(other), "9", "CALL", 1, "20.00", "2026-11-12T10:00");

        assertThat(closeRejected(btcCall("15.10", "2026-11-10T15:00", b)))
                .isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓");
        JsonNode empty = rejected(post(CLOSES), with(btcCall("15.10", "2026-11-19T10:00"),
                n -> n.putArray("lotTradeIds")), 400, "VALIDATION_ERROR");
        assertThat(empty.get("details").toString())
                .isEqualTo("[{\"field\":\"lotTradeIds\",\"message\":\"请至少选择一笔开仓批次\"}]");
        assertThat(closeRejected(btcCall("15.10", "2026-11-19T10:00", c)))
                .isEqualTo("所选批次 #" + c + " 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易");
        assertThat(closeRejected(btcCall("15.10", "2026-11-19T10:00", b, b)))
                .isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）重复选择");
        assertThat(closeRejected(with(btcCall("15.10", "2026-11-19T10:00", a), n -> n.put("contracts", 2))))
                .isEqualTo("平仓张数 2 张与所选批次张数之和 1 张不一致");
        // a (not this contract's open) comes before b (repeat) and c (not yet open)
        assertThat(closeRejected(btcCall("15.10", "2026-11-10T15:00", b, b, c)))
                .isEqualTo("所选批次 #" + c + " 不是该合约（ZZQ 2026-12-18 9 C）在本账户的开仓交易");
        // position first
        assertThat(rejected(post(CLOSES), btcCall("15.10", "2026-11-09T15:00", a), 422, "NO_POSITION_TO_CLOSE")
                .get("message").asText()).isEqualTo("该合约（ZZQ 2026-12-18 9 C）在成交时没有持仓，无法平仓");

        closeOk(btcCall("15.10", "2026-11-19T10:00", b));
        assertThat(closeRejected(btcCall("15.10", "2026-11-20T09:00", b)))
                .isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）已被 2026-11-19 10:00 的 BTC 1 张平掉");
    }

    @Test
    void lotTradeIdsFieldRules() throws Exception {
        scenarioZ();
        String base = json.writeValueAsString(btcCall("15.10", "2026-11-19T10:00"));
        JsonNode fraction = call(post(CLOSES).contentType(MediaType.APPLICATION_JSON)
                .content(base.replace("}", ",\"lotTradeIds\":[1.5]}")), 400);
        assertThat(fraction.get("code").asText()).isEqualTo("BAD_REQUEST");
        JsonNode text = call(post(CLOSES).contentType(MediaType.APPLICATION_JSON)
                .content(base.replace("}", ",\"lotTradeIds\":[\"2\"]}")), 400);
        assertThat(text.get("code").asText()).isEqualTo("BAD_REQUEST");
        JsonNode nullId = call(post(CLOSES).contentType(MediaType.APPLICATION_JSON)
                .content(base.replace("}", ",\"lotTradeIds\":[null]}")), 400);
        assertThat(nullId.get("details").toString())
                .isEqualTo("[{\"field\":\"lotTradeIds\",\"message\":\"批次必须是开仓交易 id\"}]");
        JsonNode zero = call(post(CLOSES).contentType(MediaType.APPLICATION_JSON)
                .content(base.replace("}", ",\"lotTradeIds\":[0]}")), 400);
        assertThat(zero.get("details").get(0).get("message").asText()).isEqualTo("批次必须是开仓交易 id");
        ObjectNode many = btcCall("15.10", "2026-11-19T10:00");
        var arr = many.putArray("lotTradeIds");
        for (int i = 1; i <= 1001; i++) {
            arr.add(i);
        }
        assertThat(send(post(CLOSES), many, 400).get("details").get(0).get("message").asText())
                .isEqualTo("最多选择 1000 笔批次");
        // FIFO mode still needs contracts
        JsonNode noContracts = send(post(CLOSES), btcCall("15.10", "2026-11-19T10:00"), 400);
        assertThat(noContracts.get("details").toString())
                .isEqualTo("[{\"field\":\"contracts\",\"message\":\"张数不能为空\"}]");
        JsonNode nullLots = send(post(CLOSES), with(btcCall("15.10", "2026-11-19T10:00"),
                n -> n.putNull("lotTradeIds")), 400);
        assertThat(nullLots.get("details").get(0).get("field").asText()).isEqualTo("contracts");
    }

    // ---------------------------------------------------------------- AC 6

    @Test
    void ac6_replayUsesTheStoredLots() throws Exception {
        scenarioZ();
        long c3 = closeOk(btcCall("15.10", "2026-11-19T10:00", b)).get("id").asLong();

        ObjectNode moveB = with(editBody(b), n -> n.put("tradedAtLocal", "2026-11-19T11:00"));
        assertThat(rejected(put(TRADES + "/" + b), moveB, 422, "CLOSE_LOTS_INVALID").get("message").asText())
                .isEqualTo("修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-19 11:00 STO 1 张 "
                        + "在该平仓成交时还未开仓");
        assertThat(rejected(delete(TRADES + "/" + b), null, 422, "CLOSE_LOTS_INVALID").get("message").asText())
                .isEqualTo("该开仓被 2026-11-19 10:00 的 BTC 1 张平掉（所选批次 2026-11-11 10:00 STO 1 张），"
                        + "请先修改或删除那笔平仓");
        assertThat(closeRejected(btcCall("15.10", "2026-11-15T10:00", b)))
                .isEqualTo("补录后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-11 10:00 STO 1 张 "
                        + "已被更早的平仓平掉");

        JsonNode backfill = closeOk(btcCall("10.00", "2026-11-15T10:00", a));
        assertThat(backfill.get("realizedPnl").asText()).isEqualTo("8.34");
        JsonNode later = getJson(TRADES + "/" + c3);
        assertThat(later.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(b);
        assertThat(later.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(position(CALL)).isNull();
        assertSummary("1030.58", "0.00", "1030.58");

        // only the net amount of a selected open may change: the close's P&L follows
        call(put(TRADES + "/" + b).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(with(editBody(b), n -> n.put("netAmount", "40.00")))), 200);
        assertThat(getJson(TRADES + "/" + c3).get("realizedPnl").asText()).isEqualTo("24.90");

        // deleting the close re-opens its lot
        call(delete(TRADES + "/" + c3), 204);
        assertThat(lots(position(CALL).get("lots"), "remainingNetAmount"))
                .containsExactly(b + " 2026-11-11T10:00:00 1 40.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_close_lot WHERE close_trade_id = ?", Long.class,
                c3)).isZero();
        JsonNode del = getJson("/api/audit-log").get("items").get(0);
        assertThat(del.get("operation").asText()).isEqualTo("DELETE");
        assertThat(del.get("before").get("selectedLots").get(0).get("tradeId").asLong()).isEqualTo(b);
    }

    // ---------------------------------------------------------------- AC 7

    @Test
    void ac7_editTheSelectedLots() throws Exception {
        scenarioZ();
        long c3 = closeOk(btcCall("15.10", "2026-11-19T10:00", a)).get("id").asLong();

        JsonNode choices = getJson(TRADES + "/" + c3 + "/close-lot-choices");
        assertThat(choices.get("closeTradeId").asLong()).isEqualTo(c3);
        assertThat(choices.get("tradedAtLocal").asText()).isEqualTo("2026-11-19T10:00:00");
        assertThat(lots(choices.get("lots"), "netAmount"))
                .containsExactly(a + " 2026-11-10T10:00:00 1 18.34", b + " 2026-11-11T10:00:00 1 37.34");
        assertThat(choices.get("lots").get(0).get("selected").asBoolean()).isTrue();
        assertThat(choices.get("lots").get(1).get("selected").asBoolean()).isFalse();
        assertThat(choices.get("lots").get(0).get("frozen").asText()).isEqualTo("0.00");
        JsonNode earlier = getJson(TRADES + "/" + c3 + "/close-lot-choices?tradedAtLocal=2026-11-10T15:00");
        assertThat(lots(earlier.get("lots"), "netAmount")).containsExactly(a + " 2026-11-10T10:00:00 1 18.34");
        assertThat(earlier.get("tradedAt").asText()).isEqualTo("2026-11-10T20:00:00Z");

        ObjectNode body = editBody(c3);
        body.putArray("lotTradeIds").add(b);
        JsonNode t = send(put(TRADES + "/" + c3), body, 200);
        assertThat(t.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(b);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("22.24");
        assertThat(t.get("hasAuditLog").asBoolean()).isTrue();
        assertThat(lots(position(CALL).get("lots"), "remainingNetAmount"))
                .containsExactly(a + " 2026-11-10T10:00:00 1 18.34");
        assertSummary("1040.58", "0.00", "1040.58");
        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(audit.get("operation").asText()).isEqualTo("UPDATE");
        JsonNode change = audit.get("changes").get(0);
        assertThat(change.get("field").asText()).isEqualTo("selectedLots");
        assertThat(change.get("label").asText()).isEqualTo("被平批次");
        assertThat(change.get("before").asText()).isEqualTo("2026-11-10 10:00 1 张");
        assertThat(change.get("after").asText()).isEqualTo("2026-11-11 10:00 1 张");
        assertThat(change.get("text").asText()).isEqualTo("被平批次：2026-11-10 10:00 1 张 → 2026-11-11 10:00 1 张");
        assertThat(audit.get("changes")).hasSize(1);
        JsonNode lot = audit.get("after").get("selectedLots").get(0);
        assertThat(lot.get("tradeId").asLong()).isEqualTo(b);
        assertThat(lot.get("action").asText()).isEqualTo("STO");
        assertThat(lot.get("tradedAt").asText()).isEqualTo("2026-11-11T15:00:00Z");
        assertThat(lot.get("tradedAtLocal").asText()).isEqualTo("2026-11-11T10:00:00");
        assertThat(lot.get("contracts").asInt()).isEqualTo(1);

        // same lots again (any order) = no change; contracts that do not match the lots are rejected
        ObjectNode same = editBody(c3);
        same.putArray("lotTradeIds").add(b);
        assertThat(rejected(put(TRADES + "/" + c3), same, 422, "NO_CHANGES").get("message").asText())
                .isEqualTo("没有修改");
        assertThat(rejected(put(TRADES + "/" + c3), with(editBody(c3), n -> n.put("contracts", 2)), 422,
                "CLOSE_LOTS_INVALID").get("message").asText()).isEqualTo("平仓张数 2 张与所选批次张数之和 1 张不一致");
        assertThat(rejected(put(TRADES + "/" + c3), with(editBody(c3),
                n -> n.put("tradedAtLocal", "2026-11-10T15:00")), 422, "CLOSE_LOTS_INVALID").get("message").asText())
                .isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓");

        // the endpoint's errors
        assertThat(call(get(TRADES + "/" + a + "/close-lot-choices"), 422).get("message").asText())
                .isEqualTo("只有平仓（BTC / STC）可以选择批次");
        assertThat(call(get(TRADES + "/999999/close-lot-choices"), 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(get(TRADES + "/x/close-lot-choices"), 400).get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(call(get(TRADES + "/" + c3 + "/close-lot-choices?tradedAtLocal=bad"), 400).get("code").asText())
                .isEqualTo("BAD_REQUEST");
        assertThat(call(get(TRADES + "/" + c3 + "/close-lot-choices").header(AccountRef.HEADER, "ALL"), 400)
                .get("code").asText()).isEqualTo("ALL_ACCOUNTS_NOT_SUPPORTED");
    }

    @Test
    void ac7_putChangeTheSelectedLot() throws Exception {
        scenarioP();
        long c3 = closeOk(btc("8", "PUT", "9.65", "2026-11-19T10:00", p2)).get("id").asLong();
        ObjectNode body = editBody(c3);
        body.putArray("lotTradeIds").add(p1);
        JsonNode t = send(put(TRADES + "/" + c3), body, 200);
        assertThat(t.get("contracts").asInt()).isEqualTo(2);
        assertThat(t.get("frozenChange").asText()).isEqualTo("-1600.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("20.35");
        assertSummary("30041.35", "800.00", "29241.35");
        assertThat(lots(position(PUT).get("lots"), "remainingNetAmount"))
                .containsExactly(p2 + " 2026-11-11T10:00:00 1 21.00");
        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        List<String> texts = new ArrayList<>();
        audit.get("changes").forEach(c -> texts.add(c.get("text").asText()));
        assertThat(texts).containsExactly("被平批次：2026-11-11 10:00 1 张 → 2026-11-10 10:00 2 张", "张数：1 → 2",
                "冻结变动：−800.00 → −1,600.00");

        // the lots other closes selected are not offered
        long c4 = closeOk(btc("8", "PUT", "5.00", "2026-11-19T11:00", p2)).get("id").asLong();
        JsonNode choices = getJson(TRADES + "/" + c3 + "/close-lot-choices");
        assertThat(lots(choices.get("lots"), "netAmount")).containsExactly(p1 + " 2026-11-10T10:00:00 2 30.00");
        assertThat(lots(getJson(TRADES + "/" + c4 + "/close-lot-choices").get("lots"), "netAmount"))
                .containsExactly(p2 + " 2026-11-11T10:00:00 1 21.00");
    }
}
