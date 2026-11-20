package com.optionmgr.stock.web;

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
 * FEAT-011 acceptance criteria (backend part). "Now" is 2026-11-13 10:00 New York (15:00Z); account 1 unless stated.
 * Scenario S: D1 deposit 10,000.00 (11-02 10:00); S1 transfer-in IXQZ 100 / 750.00 (2026-10-01); S2 buy IXQZ 200 /
 * 1,500.65 (11-03 10:00); S3 buy XYZ 300 / 1,000.00 (11-04 10:00); S4 sell IXQZ 150 / 1,200.00 (11-05 10:00); S5 sell
 * XYZ 100 / 400.00 (11-06 10:00).
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockTradeControllerTest {

    static final Instant NOW = Instant.parse("2026-11-13T15:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock stockTradeTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private String account = "1";

    /** Scenario S record ids. */
    private long s1;
    private long s2;
    private long s3;
    private long s4;
    private long s5;

    @BeforeEach
    void setUp() {
        cleanUp();
        account = "1";
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    void cleanUp() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 2");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req.header("X-Account-Id", account)).andReturn();
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

    private long transfer(String type, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        return send(post("/api/cash/transfers"), n, 201).get("id").asLong();
    }

    private ObjectNode stock(String type, String symbol, Object shares, String amount, String time) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        if (shares instanceof Integer i) {
            n.put("shares", i);
        } else if (shares != null) {
            n.put("shares", shares.toString());
        }
        n.put("amount", amount);
        if ("TRANSFER_IN".equals(type)) {
            n.put("tradeDate", time);
        } else {
            n.put("tradedAtLocal", time);
        }
        return n;
    }

    private JsonNode create(String type, String symbol, int shares, String amount, String time) throws Exception {
        return send(post("/api/stock-trades"), stock(type, symbol, shares, amount, time), 201);
    }

    private JsonNode reject(ObjectNode body, int status) throws Exception {
        return send(post("/api/stock-trades"), body, status);
    }

    private ObjectNode edit(int shares, String amount, String time, boolean transfer) {
        ObjectNode n = json.createObjectNode();
        n.put("shares", shares);
        n.put("amount", amount);
        n.put(transfer ? "tradeDate" : "tradedAtLocal", time);
        return n;
    }

    private void scenarioS() throws Exception {
        transfer("DEPOSIT", "10000.00", "2026-11-02T10:00");
        s1 = create("TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01").get("id").asLong();
        s2 = create("BUY", "IXQZ", 200, "1500.65", "2026-11-03T10:00").get("id").asLong();
        s3 = create("BUY", "XYZ", 300, "1000.00", "2026-11-04T10:00").get("id").asLong();
        s4 = create("SELL", "IXQZ", 150, "1200.00", "2026-11-05T10:00").get("id").asLong();
        s5 = create("SELL", "XYZ", 100, "400.00", "2026-11-06T10:00").get("id").asLong();
    }

    private List<String> summary() throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        return List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(), s.get("availableCash").asText());
    }

    private JsonNode position(String symbol) throws Exception {
        for (JsonNode p : getJson("/api/stock-positions").get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    private List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asLong()));
        return out;
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE object_type <> 'ACCOUNT'", Long.class);
    }

    private String newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        return send(post("/api/accounts"), n, 201).get("id").asText();
    }

    private void assertError(JsonNode err, String code, String message) {
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("message").asText()).isEqualTo(message);
        assertThat(err.get("details")).isEmpty();
    }

    private void assertField(JsonNode err, String field, String message) {
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        List<String> got = new ArrayList<>();
        err.get("details").forEach(d -> got.add(d.get("field").asText() + ": " + d.get("message").asText()));
        assertThat(got).contains(field + ": " + message);
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_recordsAndStockPositions() throws Exception {
        transfer("DEPOSIT", "10000.00", "2026-11-02T10:00");
        JsonNode t = create("TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01");
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-10-01T04:00:00Z");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2026-10-01T00:00:00");
        assertThat(t.get("tradeDate").asText()).isEqualTo("2026-10-01");
        assertThat(t.get("cashChange").asText()).isEqualTo("0.00");
        assertThat(t.get("pricePerShare").asText()).isEqualTo("7.5000");
        assertThat(t.get("ledgerEntryId").isNull()).isTrue();
        assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(t.get("soldLots").isNull()).isTrue();
        assertThat(summary()).containsExactly("10000.00", "0.00", "10000.00");

        s2 = create("BUY", "IXQZ", 200, "1500.65", "2026-11-03T10:00").get("id").asLong();
        create("BUY", "XYZ", 300, "1000.00", "2026-11-04T10:00");
        create("SELL", "IXQZ", 150, "1200.00", "2026-11-05T10:00");
        create("SELL", "XYZ", 100, "400.00", "2026-11-06T10:00");
        assertThat(summary()).containsExactly("9099.35", "0.00", "9099.35");

        assertThat(getJson("/api/option-positions").get("positionCount").asInt()).isZero();
        JsonNode positions = getJson("/api/stock-positions");
        assertThat(positions.get("positionCount").asInt()).isEqualTo(2);
        JsonNode ixqz = positions.get("positions").get(0);
        assertThat(ixqz.get("symbol").asText()).isEqualTo("IXQZ");
        assertThat(ixqz.get("shares").asLong()).isEqualTo(150);
        assertThat(ixqz.get("totalCost").asText()).isEqualTo("1125.49");
        assertThat(ixqz.get("averageCost").asText()).isEqualTo("7.5033");
        assertThat(ixqz.get("lotCount").asInt()).isEqualTo(1);
        JsonNode lot = ixqz.get("lots").get(0);
        assertThat(lot.get("tradeId").asLong()).isEqualTo(s2);
        assertThat(lot.get("type").asText()).isEqualTo("BUY");
        assertThat(lot.get("tradedAtLocal").asText()).isEqualTo("2026-11-03T10:00:00");
        assertThat(lot.get("tradeDate").isNull()).isTrue();
        assertThat(lot.get("originalShares").asInt()).isEqualTo(200);
        assertThat(lot.get("remainingShares").asInt()).isEqualTo(150);
        assertThat(lot.get("originalCost").asText()).isEqualTo("1500.65");
        assertThat(lot.get("remainingCost").asText()).isEqualTo("1125.49");
        JsonNode xyz = positions.get("positions").get(1);
        assertThat(xyz.get("symbol").asText()).isEqualTo("XYZ");
        assertThat(xyz.get("shares").asLong()).isEqualTo(200);
        assertThat(xyz.get("totalCost").asText()).isEqualTo("666.67");
        assertThat(xyz.get("averageCost").asText()).isEqualTo("3.3334");
        assertThat(xyz.get("lots").get(0).get("remainingShares").asInt()).isEqualTo(200);
        assertThat(xyz.get("lots").get(0).get("remainingCost").asText()).isEqualTo("666.67");
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    void ac2_listRealizedPnlSoldLotsAndLedger() throws Exception {
        scenarioS();
        JsonNode page = getJson("/api/stock-trades");
        assertThat(ids(page)).containsExactly(s5, s4, s3, s2, s1);
        assertThat(page.get("totalItems").asInt()).isEqualTo(5);
        JsonNode items = page.get("items");
        List<String> rows = new ArrayList<>();
        items.forEach(i -> rows.add(i.get("type").asText() + " " + i.get("symbol").asText() + " "
                + i.get("shares").asInt() + " " + i.get("cashChange").asText() + " " + i.get("pricePerShare").asText()
                + " " + i.get("realizedPnl").asText() + " " + i.get("hasAuditLog").asBoolean()));
        assertThat(rows).containsExactly(
                "SELL XYZ 100 400.00 4.0000 66.67 false",
                "SELL IXQZ 150 1200.00 8.0000 74.84 false",
                "BUY XYZ 300 -1000.00 3.3333 null false",
                "BUY IXQZ 200 -1500.65 7.5033 null false",
                "TRANSFER_IN IXQZ 100 0.00 7.5000 null false");

        JsonNode sale = getJson("/api/stock-trades/" + s4);
        assertThat(sale.get("soldCost").asText()).isEqualTo("1125.16");
        assertThat(sale.get("realizedPnl").asText()).isEqualTo("74.84");
        JsonNode lots = sale.get("soldLots");
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0).get("tradeId").asLong()).isEqualTo(s1);
        assertThat(lots.get(0).get("type").asText()).isEqualTo("TRANSFER_IN");
        assertThat(lots.get(0).get("tradeDate").asText()).isEqualTo("2026-10-01");
        assertThat(lots.get(0).get("shares").asInt()).isEqualTo(100);
        assertThat(lots.get(0).get("cost").asText()).isEqualTo("750.00");
        assertThat(lots.get(1).get("tradeId").asLong()).isEqualTo(s2);
        assertThat(lots.get(1).get("tradedAtLocal").asText()).isEqualTo("2026-11-03T10:00:00");
        assertThat(lots.get(1).get("shares").asInt()).isEqualTo(50);
        assertThat(lots.get(1).get("cost").asText()).isEqualTo("375.16");
        JsonNode s5Lots = getJson("/api/stock-trades/" + s5).get("soldLots");
        assertThat(s5Lots).hasSize(1);
        assertThat(s5Lots.get(0).get("tradeId").asLong()).isEqualTo(s3);
        assertThat(s5Lots.get(0).get("cost").asText()).isEqualTo("333.33");

        JsonNode ledger = getJson("/api/cash/ledger").get("items");
        List<String> lines = new ArrayList<>();
        ledger.forEach(l -> lines.add(l.get("sourceType").asText() + " " + l.get("amount").asText() + " "
                + l.get("balanceAfter").asText() + " " + l.get("description").asText() + " "
                + l.get("frozenChange").asText() + " " + l.get("hasAuditLog").asBoolean()));
        assertThat(lines).containsExactly(
                "STOCK_SELL 400.00 9099.35 卖出 100 股 XYZ 0.00 false",
                "STOCK_SELL 1200.00 8699.35 卖出 150 股 IXQZ 0.00 false",
                "STOCK_BUY -1000.00 7499.35 买入 300 股 XYZ 0.00 false",
                "STOCK_BUY -1500.65 8499.35 买入 200 股 IXQZ 0.00 false",
                "DEPOSIT 10000.00 10000.00 null 0.00 false");
        assertThat(ledger.get(3).get("sourceId").asLong()).isEqualTo(s2);
        assertThat(getJson("/api/stock-trades/" + s2).get("ledgerEntryId").asLong())
                .isEqualTo(ledger.get(3).get("id").asLong());
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    void ac3_listFilters() throws Exception {
        scenarioS();
        assertThat(ids(getJson("/api/stock-trades?symbol=ixqz"))).containsExactly(s4, s2, s1);
        assertThat(ids(getJson("/api/stock-trades?type=SELL"))).containsExactly(s5, s4);
        assertThat(ids(getJson("/api/stock-trades?type=TRANSFER_IN"))).containsExactly(s1);
        assertThat(ids(getJson("/api/stock-trades?type=BUY&type=TRANSFER_IN"))).containsExactly(s3, s2, s1);
        assertThat(ids(getJson("/api/stock-trades?type=BUY,TRANSFER_IN"))).containsExactly(s3, s2, s1);
        assertThat(ids(getJson("/api/stock-trades?startDate=2026-11-04&endDate=2026-11-05"))).containsExactly(s4, s3);
        assertThat(ids(getJson("/api/stock-trades?endDate=2026-10-31"))).containsExactly(s1);
        assertThat(ids(getJson("/api/stock-trades?page=2&size=2"))).containsExactly(s3, s2);
        assertThat(ids(getJson("/api/stock-trades?page=9"))).isEmpty();

        assertField(call(get("/api/stock-trades?symbol=$IXQZ"), 400), "symbol", "标的只能包含字母、数字和点");
        JsonNode bad = call(get("/api/stock-trades?type=FOO"), 400);
        assertThat(bad.get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(bad.get("details").get(0).get("message").asText()).isEqualTo("类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL、EXERCISE_BUY、EXERCISE_SELL");
        assertField(call(get("/api/stock-trades?startDate=2026-11-05&endDate=2026-11-04"), 400), "endDate",
                "开始日期不能晚于结束日期");
        assertField(call(get("/api/stock-trades?size=101"), 400), "size", "size 必须在 1 到 100 之间");
        assertThat(call(get("/api/stock-trades?startDate=2026-02-30"), 400).get("code").asText())
                .isEqualTo("BAD_REQUEST");
    }

    // ---------------------------------------------------------------- AC 4

    @Test
    void ac4_partialSaleThenPartialSaleUsesTheRemainder() throws Exception {
        scenarioS();
        JsonNode s6 = create("SELL", "XYZ", 100, "420.00", "2026-11-09T10:00");
        assertThat(s6.get("soldCost").asText()).isEqualTo("333.34");
        assertThat(s6.get("realizedPnl").asText()).isEqualTo("86.66");
        JsonNode xyz = position("XYZ");
        assertThat(xyz.get("shares").asLong()).isEqualTo(100);
        assertThat(xyz.get("totalCost").asText()).isEqualTo("333.33");
        assertThat(xyz.get("averageCost").asText()).isEqualTo("3.3333");

        JsonNode s7 = create("SELL", "XYZ", 100, "300.00", "2026-11-10T10:00");
        assertThat(s7.get("soldCost").asText()).isEqualTo("333.33");
        assertThat(s7.get("realizedPnl").asText()).isEqualTo("-33.33");
        assertThat(position("XYZ")).isNull();
        assertThat(getJson("/api/stock-positions").get("positionCount").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void ac5_buyNeedsAvailableCashFrozenCashCannotBuyStock() throws Exception {
        account = newAccount("小账户");
        long deposit = transfer("DEPOSIT", "1000.00", "2026-11-02T10:00");
        ObjectNode sto = json.createObjectNode();
        sto.put("symbol", "QFZ");
        sto.put("expirationDate", "2026-12-18");
        sto.put("strike", "5");
        sto.put("optionType", "PUT");
        sto.put("action", "STO");
        sto.put("contracts", 1);
        sto.put("netAmount", "20.00");
        sto.put("tradedAtLocal", "2026-11-03T09:00");
        send(post(com.optionmgr.testsupport.LegacyShortCallTestController.openUrl(sto)), sto, 201);
        assertThat(summary()).containsExactly("1020.00", "500.00", "520.00");

        assertError(reject(stock("BUY", "QRTV", 100, "600.00", "2026-11-03T10:00"), 422), "CASH_INSUFFICIENT",
                "可用现金不足：需要 600.00；2026-11-03 10:00 时可用现金将变为 −80.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_trade", Long.class)).isZero();
        assertThat(summary()).containsExactly("1020.00", "500.00", "520.00");

        create("BUY", "QRTV", 100, "520.00", "2026-11-03T10:00");
        assertThat(summary()).containsExactly("500.00", "500.00", "0.00");

        ObjectNode w = json.createObjectNode();
        w.put("type", "WITHDRAWAL");
        w.put("amount", "1.00");
        w.put("occurredAtLocal", "2026-11-04T10:00");
        assertThat(send(post("/api/cash/transfers"), w, 422).get("code").asText()).isEqualTo("CASH_INSUFFICIENT");

        ObjectNode d = json.createObjectNode();
        d.put("type", "DEPOSIT");
        d.put("amount", "999.00");
        d.put("occurredAtLocal", "2026-11-02T10:00");
        assertError(send(put("/api/cash/transfers/" + deposit), d, 422), "CASH_INSUFFICIENT",
                "可用现金不足：修改后 2026-11-03 10:00 时可用现金将变为 −1.00");
        assertThat(auditCount()).isZero();
    }

    @Test
    void ac5_backFilledBuyMustKeepLaterPointsNonNegative() throws Exception {
        scenarioS();
        transfer("WITHDRAWAL", "8000.00", "2026-11-07T10:00");
        assertThat(summary()).containsExactly("1099.35", "0.00", "1099.35");
        assertError(reject(stock("BUY", "XYZ", 100, "1200.00", "2026-11-04T11:00"), 422), "CASH_INSUFFICIENT",
                "可用现金不足：需要 1,200.00；2026-11-07 10:00 时可用现金将变为 −100.65");
        assertThat(summary()).containsExactly("1099.35", "0.00", "1099.35");
    }

    // ---------------------------------------------------------------- AC 6

    @Test
    void ac6_noShortSellingAndBackFilledSaleCannotStarveLaterSales() throws Exception {
        scenarioS();
        assertError(reject(stock("SELL", "IXQZ", 151, "1.00", "2026-11-13T09:00"), 422), "SHARES_INSUFFICIENT",
                "持股不足：2026-11-13 09:00 卖出 IXQZ 151 股，当时持有 150 股");
        assertError(reject(stock("SELL", "XYZ", 100, "1.00", "2026-11-03T10:00"), 422), "SHARES_INSUFFICIENT",
                "持股不足：2026-11-03 10:00 卖出 XYZ 100 股，当时持有 0 股");
        assertError(reject(stock("SELL", "QRTV", 1, "1.00", "2026-11-13T09:00"), 422), "SHARES_INSUFFICIENT",
                "持股不足：2026-11-13 09:00 卖出 QRTV 1 股，当时持有 0 股");
        assertError(reject(stock("SELL", "IXQZ", 200, "1600.00", "2026-11-04T12:00"), 422), "SHARES_INSUFFICIENT",
                "补录后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股");
        assertThat(getJson("/api/stock-trades").get("totalItems").asInt()).isEqualTo(5);
        assertThat(summary()).containsExactly("9099.35", "0.00", "9099.35");

        create("SELL", "IXQZ", 150, "1300.00", "2026-11-13T09:00");
        assertThat(position("IXQZ")).isNull();
    }

    // ---------------------------------------------------------------- AC 7

    @Test
    void ac7_transferInCountsFromMidnightAndDateRules() throws Exception {
        account = newAccount("转入账户");
        transfer("DEPOSIT", "1000.00", "2026-11-02T10:00");
        JsonNode t = create("TRANSFER_IN", "QRTV", 100, "500.00", "2026-11-10");
        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-10T05:00:00Z");
        JsonNode sale = create("SELL", "QRTV", 100, "550.00", "2026-11-10T09:30");
        assertThat(sale.get("realizedPnl").asText()).isEqualTo("50.00");
        assertThat(summary()).containsExactly("1550.00", "0.00", "1550.00");

        assertField(reject(stock("TRANSFER_IN", "QRTV", 1, "1.00", "2026-11-14"), 400), "tradeDate", "日期不能晚于今天");
        assertThat(create("TRANSFER_IN", "QRTV", 1, "1.00", "2026-11-13").get("tradeDate").asText())
                .isEqualTo("2026-11-13");
        assertField(reject(stock("BUY", "QRTV", 1, "1.00", "2026-11-13T10:01"), 400), "tradedAtLocal",
                "成交时间不能晚于当前时间");
        assertField(reject(stock("SELL", "QRTV", 1, "1.00", "2026-11-13T10:01"), 400), "tradedAtLocal",
                "成交时间不能晚于当前时间");
        create("BUY", "QRTV", 1, "1.00", "2026-11-13T10:00");
    }

    // ---------------------------------------------------------------- AC 8

    @Test
    void ac8_editAmountReMatchesFifoAndIsAudited() throws Exception {
        scenarioS();
        JsonNode r = send(put("/api/stock-trades/" + s2), edit(200, "1500.00", "2026-11-03T10:00", false), 200);
        assertThat(r.get("hasAuditLog").asBoolean()).isTrue();
        assertThat(r.get("amount").asText()).isEqualTo("1500.00");
        assertThat(summary()).containsExactly("9100.00", "0.00", "9100.00");
        JsonNode sale = getJson("/api/stock-trades/" + s4);
        assertThat(sale.get("soldCost").asText()).isEqualTo("1125.00");
        assertThat(sale.get("realizedPnl").asText()).isEqualTo("75.00");
        JsonNode ixqz = position("IXQZ");
        assertThat(ixqz.get("totalCost").asText()).isEqualTo("1125.00");
        assertThat(ixqz.get("averageCost").asText()).isEqualTo("7.5000");
        JsonNode ledger = getJson("/api/cash/ledger").get("items");
        assertThat(ledger).hasSize(5);
        assertThat(ledger.get(3).get("amount").asText()).isEqualTo("-1500.00");
        assertThat(ledger.get(3).get("sourceId").asLong()).isEqualTo(s2);

        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(audit.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(audit.get("objectType").asText()).isEqualTo("STOCK_TRADE");
        assertThat(audit.get("objectId").asLong()).isEqualTo(s2);
        assertThat(audit.get("summary").asText()).isEqualTo("买入 IXQZ 200 股");
        assertThat(audit.get("changes")).hasSize(1);
        assertThat(audit.get("changes").get(0).get("text").asText()).isEqualTo("金额：1,500.65 → 1,500.00");
        assertThat(audit.get("before").get("amount").asText()).isEqualTo("1500.65");
        assertThat(audit.get("before").get("cashChange").asText()).isEqualTo("-1500.65");
        assertThat(audit.get("after").get("amount").asText()).isEqualTo("1500.00");
        assertThat(audit.get("after").get("tradedAtLocal").asText()).isEqualTo("2026-11-03T10:00:00");
        assertThat(audit.get("sideEffects")).isEmpty();
        assertThat(getJson("/api/audit-log?objectType=STOCK_TRADE&objectId=" + s2).get("totalItems").asInt())
                .isEqualTo(1);

        getJson("/api/stock-trades").get("items").forEach(i -> assertThat(i.get("hasAuditLog").asBoolean())
                .as("record " + i.get("id")).isEqualTo(i.get("id").asLong() == s2));
    }

    @Test
    void ac8_editTransferDateMovesItInFifo() throws Exception {
        scenarioS();
        send(put("/api/stock-trades/" + s1), edit(100, "750.00", "2026-11-04", true), 200);
        JsonNode sale = getJson("/api/stock-trades/" + s4);
        assertThat(sale.get("soldCost").asText()).isEqualTo("1125.49");
        assertThat(sale.get("realizedPnl").asText()).isEqualTo("74.51");
        JsonNode ixqz = position("IXQZ");
        assertThat(ixqz.get("shares").asLong()).isEqualTo(150);
        assertThat(ixqz.get("totalCost").asText()).isEqualTo("1125.16");
        assertThat(ixqz.get("averageCost").asText()).isEqualTo("7.5011");
        JsonNode lots = ixqz.get("lots");
        assertThat(lots.get(0).get("type").asText()).isEqualTo("BUY");
        assertThat(lots.get(0).get("remainingShares").asInt()).isEqualTo(50);
        assertThat(lots.get(0).get("remainingCost").asText()).isEqualTo("375.16");
        assertThat(lots.get(1).get("type").asText()).isEqualTo("TRANSFER_IN");
        assertThat(lots.get(1).get("tradeDate").asText()).isEqualTo("2026-11-04");
        assertThat(lots.get(1).get("remainingShares").asInt()).isEqualTo(100);
        assertThat(lots.get(1).get("remainingCost").asText()).isEqualTo("750.00");
        assertThat(summary()).containsExactly("9099.35", "0.00", "9099.35");
        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(audit.get("summary").asText()).isEqualTo("转入 IXQZ 100 股");
        assertThat(audit.get("changes").get(0).get("text").asText()).isEqualTo("日期：2026-10-01 → 2026-11-04");
        assertThat(audit.get("before").get("tradeDate").asText()).isEqualTo("2026-10-01");
        assertThat(audit.get("after").get("tradeDate").asText()).isEqualTo("2026-11-04");
    }

    @Test
    void ac8_editTransferCost() throws Exception {
        scenarioS();
        send(put("/api/stock-trades/" + s1), edit(100, "800.00", "2026-10-01", true), 200);
        assertThat(getJson("/api/stock-trades/" + s4).get("realizedPnl").asText()).isEqualTo("24.84");
        assertThat(summary()).containsExactly("9099.35", "0.00", "9099.35");
        assertThat(getJson("/api/audit-log").get("items").get(0).get("changes").get(0).get("text").asText())
                .isEqualTo("总成本：750.00 → 800.00");
    }

    @Test
    void ac8_rejectedEdits() throws Exception {
        scenarioS();
        assertError(send(put("/api/stock-trades/" + s2), edit(40, "1500.65", "2026-11-03T10:00", false), 422),
                "SHARES_INSUFFICIENT", "修改后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 140 股");
        assertError(send(put("/api/stock-trades/" + s4), edit(150, "1200.00", "2026-11-02T10:00", false), 422),
                "SHARES_INSUFFICIENT", "持股不足：2026-11-02 10:00 卖出 IXQZ 150 股，当时持有 100 股");
        assertError(send(put("/api/stock-trades/" + s2), edit(200, "1500.65", "2026-11-03T10:00", false), 422),
                "NO_CHANGES", "没有修改");
        ObjectNode same = edit(100, "750.00", "2026-10-01", true);
        same.put("note", "  ");
        same.put("symbol", " ixqz ");
        same.put("type", "TRANSFER_IN");
        assertError(send(put("/api/stock-trades/" + s1), same, 422), "NO_CHANGES", "没有修改");

        ObjectNode symbol = edit(200, "1500.00", "2026-11-03T10:00", false);
        symbol.put("symbol", "XYZ");
        assertError(send(put("/api/stock-trades/" + s2), symbol, 422), "STOCK_TRADE_IDENTITY_NOT_EDITABLE",
                "标的或类型录错请删除后重新录入");
        ObjectNode type = edit(200, "1500.00", "2026-11-03T10:00", false);
        type.put("type", "SELL");
        assertError(send(put("/api/stock-trades/" + s2), type, 422), "STOCK_TRADE_IDENTITY_NOT_EDITABLE",
                "标的或类型录错请删除后重新录入");

        // time field required by the stored type
        assertField(send(put("/api/stock-trades/" + s1), edit(100, "750.00", "2026-11-03T10:00", false), 400),
                "tradeDate", "日期不能为空");
        assertField(send(put("/api/stock-trades/" + s2), edit(200, "1500.00", "2026-11-13T10:01", false), 400),
                "tradedAtLocal", "成交时间不能晚于当前时间");

        assertThat(auditCount()).isZero();
        assertThat(getJson("/api/stock-trades/" + s2).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(getJson("/api/stock-trades/" + s4).get("realizedPnl").asText()).isEqualTo("74.84");
        assertThat(summary()).containsExactly("9099.35", "0.00", "9099.35");
        assertThat(call(put("/api/stock-trades/999999").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(edit(1, "1.00", "2026-11-03T10:00", false))), 404)
                .get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void editBuyCashIsReplayed() throws Exception {
        scenarioS();
        transfer("WITHDRAWAL", "8000.00", "2026-11-07T10:00");
        assertError(send(put("/api/stock-trades/" + s2), edit(200, "2600.65", "2026-11-03T10:00", false), 422),
                "CASH_INSUFFICIENT", "可用现金不足：修改后 2026-11-07 10:00 时可用现金将变为 −0.65");
        assertThat(auditCount()).isZero();
    }

    // ---------------------------------------------------------------- AC 9

    @Test
    void ac9_deleteTransferIn() throws Exception {
        scenarioS();
        call(delete("/api/stock-trades/" + s1), 204);
        assertThat(getJson("/api/stock-trades/" + s4).get("realizedPnl").asText()).isEqualTo("74.51");
        JsonNode ixqz = position("IXQZ");
        assertThat(ixqz.get("shares").asLong()).isEqualTo(50);
        assertThat(ixqz.get("totalCost").asText()).isEqualTo("375.16");
        assertThat(ixqz.get("averageCost").asText()).isEqualTo("7.5032");
        assertThat(summary()).containsExactly("9099.35", "0.00", "9099.35");
        assertThat(getJson("/api/cash/ledger").get("totalItems").asInt()).isEqualTo(5);
        JsonNode audit = getJson("/api/audit-log?objectType=STOCK_TRADE").get("items").get(0);
        assertThat(audit.get("operation").asText()).isEqualTo("DELETE");
        assertThat(audit.get("objectId").asLong()).isEqualTo(s1);
        assertThat(audit.get("after").isNull()).isTrue();
        assertThat(audit.get("changes")).isEmpty();
        JsonNode before = audit.get("before");
        assertThat(before.get("type").asText()).isEqualTo("TRANSFER_IN");
        assertThat(before.get("symbol").asText()).isEqualTo("IXQZ");
        assertThat(before.get("shares").asInt()).isEqualTo(100);
        assertThat(before.get("amount").asText()).isEqualTo("750.00");
        assertThat(before.get("tradeDate").asText()).isEqualTo("2026-10-01");
        assertThat(before.get("cashChange").asText()).isEqualTo("0.00");
        assertThat(call(get("/api/stock-trades/" + s1), 404).get("message").asText())
                .isEqualTo("Stock trade " + s1 + " not found");
        call(delete("/api/stock-trades/" + s1), 404);
    }

    @Test
    void ac9_rejectedAndAllowedDeletes() throws Exception {
        scenarioS();
        assertError(call(delete("/api/stock-trades/" + s2), 422), "SHARES_INSUFFICIENT",
                "删除后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股");
        transfer("WITHDRAWAL", "8000.00", "2026-11-07T10:00");
        assertError(call(delete("/api/stock-trades/" + s4), 422), "CASH_INSUFFICIENT",
                "可用现金不足：删除后 2026-11-07 10:00 时可用现金将变为 −100.65");
        assertThat(auditCount()).isZero();
        assertThat(getJson("/api/stock-trades").get("totalItems").asInt()).isEqualTo(5);

        call(delete("/api/stock-trades/" + s5), 204);
        assertThat(summary()).containsExactly("699.35", "0.00", "699.35");
        JsonNode xyz = position("XYZ");
        assertThat(xyz.get("shares").asLong()).isEqualTo(300);
        assertThat(xyz.get("totalCost").asText()).isEqualTo("1000.00");
        assertThat(getJson("/api/cash/ledger").get("totalItems").asInt()).isEqualTo(5);
        JsonNode audit = getJson("/api/audit-log?objectType=STOCK_TRADE&objectId=" + s5).get("items");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("summary").asText()).isEqualTo("卖出 XYZ 100 股");
    }

    // ---------------------------------------------------------------- AC 10

    @Test
    void ac10_sameInstantFollowsEntryOrder() throws Exception {
        account = newAccount("同时账户");
        transfer("DEPOSIT", "1000.00", "2026-11-02T10:00");
        assertError(reject(stock("SELL", "QRTV", 100, "520.00", "2026-11-10T10:00"), 422), "SHARES_INSUFFICIENT",
                "持股不足：2026-11-10 10:00 卖出 QRTV 100 股，当时持有 0 股");
        long buy = create("BUY", "QRTV", 100, "500.00", "2026-11-10T10:00").get("id").asLong();
        JsonNode sale = create("SELL", "QRTV", 100, "520.00", "2026-11-10T10:00");
        assertThat(sale.get("realizedPnl").asText()).isEqualTo("20.00");
        assertThat(ids(getJson("/api/stock-trades"))).containsExactly(sale.get("id").asLong(), buy);
        assertError(send(put("/api/stock-trades/" + buy), edit(100, "500.00", "2026-11-10T10:01", false), 422),
                "SHARES_INSUFFICIENT", "修改后 2026-11-10 10:00 的卖出 QRTV 100 股将超过当时持股 0 股");
    }

    // ---------------------------------------------------------------- AC 11

    @Test
    void ac11_fieldValidation() throws Exception {
        scenarioS();
        assertField(reject(stock("BUY", "", 1, "1.00", "2026-11-12T10:00"), 400), "symbol", "标的不能为空");
        assertField(reject(stock("BUY", "IX QZ", 1, "1.00", "2026-11-12T10:00"), 400), "symbol", "标的只能包含字母、数字和点");
        assertField(reject(stock("BUY", "$IXQZ", 1, "1.00", "2026-11-12T10:00"), 400), "symbol", "标的只能包含字母、数字和点");
        assertField(reject(stock("BUY", "ABCDEFGHIJK", 1, "1.00", "2026-11-12T10:00"), 400), "symbol", "标的最多 10 个字符");
        assertField(reject(stock("BUY", "IXQZ", 0, "1.00", "2026-11-12T10:00"), 400), "shares", "股数必须是正整数");
        assertField(reject(stock("BUY", "IXQZ", -5, "1.00", "2026-11-12T10:00"), 400), "shares", "股数必须是正整数");
        assertField(reject(stock("BUY", "IXQZ", "1.5", "1.00", "2026-11-12T10:00"), 400), "shares", "股数必须是正整数");
        assertField(reject(stock("BUY", "IXQZ", null, "1.00", "2026-11-12T10:00"), 400), "shares", "股数不能为空");
        assertField(reject(stock("BUY", "IXQZ", 10000001, "1.00", "2026-11-12T10:00"), 400), "shares", "股数过大");
        assertField(reject(stock("BUY", "IXQZ", 1, "0", "2026-11-12T10:00"), 400), "amount", "金额必须大于 0");
        assertField(reject(stock("BUY", "IXQZ", 1, "-1.00", "2026-11-12T10:00"), 400), "amount", "金额必须大于 0");
        assertField(reject(stock("BUY", "IXQZ", 1, "1.234", "2026-11-12T10:00"), 400), "amount", "金额最多 2 位小数");
        ObjectNode noAmount = stock("BUY", "IXQZ", 1, "1.00", "2026-11-12T10:00");
        noAmount.remove("amount");
        assertField(reject(noAmount, 400), "amount", "金额不能为空");
        assertField(reject(stock("BUY", "IXQZ", 1, "10000000000000.00", "2026-11-12T10:00"), 400), "amount", "金额过大");
        ObjectNode longNote = stock("BUY", "IXQZ", 1, "1.00", "2026-11-12T10:00");
        longNote.put("note", "x".repeat(501));
        assertField(reject(longNote, 400), "note", "备注最多 500 个字符");
        ObjectNode noType = stock("BUY", "IXQZ", 1, "1.00", "2026-11-12T10:00");
        noType.remove("type");
        assertField(reject(noType, 400), "type", "请选择类型");
        ObjectNode noTime = stock("BUY", "IXQZ", 1, "1.00", "2026-11-12T10:00");
        noTime.remove("tradedAtLocal");
        assertField(reject(noTime, 400), "tradedAtLocal", "成交时间不能为空");
        ObjectNode noDate = stock("TRANSFER_IN", "IXQZ", 1, "1.00", "2026-11-12");
        noDate.remove("tradeDate");
        assertField(reject(noDate, 400), "tradeDate", "日期不能为空");
        assertThat(reject(stock("HOLD", "IXQZ", 1, "1.00", "2026-11-12T10:00"), 400).get("code").asText())
                .isEqualTo("BAD_REQUEST");
        assertThat(getJson("/api/stock-trades").get("totalItems").asInt()).isEqualTo(5);

        JsonNode merged = create("BUY", " ixqz ", 10, "80.00", "2026-11-12T10:00");
        assertThat(merged.get("symbol").asText()).isEqualTo("IXQZ");
        JsonNode ixqz = position("IXQZ");
        assertThat(ixqz.get("shares").asLong()).isEqualTo(160);
        assertThat(ixqz.get("totalCost").asText()).isEqualTo("1205.49");
        assertThat(create("BUY", "BRK.B", 1, "1.00", "2026-11-12T10:00").get("symbol").asText()).isEqualTo("BRK.B");
        assertThat(create("BUY", "QRTV", 10000000, "1.00", "2026-11-12T10:00").get("shares").asInt())
                .isEqualTo(10000000);
    }

    // ---------------------------------------------------------------- AC 12

    @Test
    void ac12_accountsAreIsolated() throws Exception {
        scenarioS();
        account = newAccount("小账户");
        assertThat(getJson("/api/stock-trades").get("totalItems").asInt()).isZero();
        assertThat(getJson("/api/stock-positions").get("positionCount").asInt()).isZero();
        transfer("DEPOSIT", "1000.00", "2026-11-02T10:00");
        assertError(reject(stock("SELL", "IXQZ", 1, "1.00", "2026-11-12T10:00"), 422), "SHARES_INSUFFICIENT",
                "持股不足：2026-11-12 10:00 卖出 IXQZ 1 股，当时持有 0 股");
        call(put("/api/stock-trades/" + s2).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(edit(200, "1.00", "2026-11-03T10:00", false))), 404);
        call(delete("/api/stock-trades/" + s2), 404);
        call(get("/api/stock-trades/" + s2), 404);
        create("TRANSFER_IN", "IXQZ", 100, "700.00", "2026-11-01");
        assertThat(position("IXQZ").get("shares").asLong()).isEqualTo(100);
        account = "1";
        assertThat(position("IXQZ").get("shares").asLong()).isEqualTo(150);
    }

    // ---------------------------------------------------------------- AC 13

    @Test
    void ac13_noInteractionWithOptions() throws Exception {
        scenarioS();
        ObjectNode sto = json.createObjectNode();
        sto.put("symbol", "IXQZ");
        sto.put("expirationDate", "2026-12-18");
        sto.put("strike", "9");
        sto.put("optionType", "CALL");
        sto.put("action", "STO");
        sto.put("contracts", 5);
        sto.put("netAmount", "150.00");
        sto.put("tradedAtLocal", "2026-11-12T10:00");
        assertThat(send(post(com.optionmgr.testsupport.LegacyShortCallTestController.openUrl(sto)), sto, 201).get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(summary()).containsExactly("9249.35", "0.00", "9249.35");
        create("SELL", "IXQZ", 150, "1300.00", "2026-11-12T11:00");

        // FEAT-040 superseded "closed trades / summary / dashboard exclude stocks": the three sales are included
        // (S4 +74.84, S5 +66.67, this sale of the remaining 150 IXQZ at cost 1,125.49 → +174.51).
        assertThat(getJson("/api/closed-trades").get("filteredCount").asInt()).isEqualTo(3);
        assertThat(getJson("/api/closed-trades").get("filteredRealizedPnlTotal").asText()).isEqualTo("316.02");
        assertThat(getJson("/api/pnl-summary").get("closedCount").asInt()).isEqualTo(3);
        JsonNode dashboard = getJson("/api/dashboard");
        assertThat(dashboard.get("realizedPnl").get("total").asText()).isEqualTo("316.02");
        assertThat(dashboard.get("realizedPnl").get("closedCount").asInt()).isEqualTo(3);
        assertThat(dashboard.get("positions").get("positionCount").asInt()).isEqualTo(1);
        assertThat(dashboard.get("stockPositions").get("positionCount").asInt()).isEqualTo(1);
        assertThat(getJson("/api/option-trades").get("totalItems").asInt()).isEqualTo(1);
        assertThat(getJson("/api/stock-trades").get("totalItems").asInt()).isEqualTo(6);
    }
}
