package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;

/**
 * Shared helpers of the QA system tests for FEAT-051 平仓时指定批次 (contract docs/api/FEAT-051.md): BTC / STC carry
 * {@code lotTradeIds} (chosen-lots mode; absent / null = the old FIFO mode, Q257), every close stores its lots
 * (table {@code option_close_lot}, Q255 A — Q39's automatic re-matching is cancelled), closes can change their lots via
 * {@code PUT} (Q256, audit {@code selectedLots}), {@code GET /api/option-trades/{id}/close-lot-choices}, positions
 * {@code lots[].closeDefault}, new error code {@code CLOSE_LOTS_INVALID}.
 *
 * <p>Reuses the FEAT-035 QA base (profiles {@code test} + {@code e2e}, random port — never 8080, in-memory H2, fake
 * CBOE / Finnhub only — no source is configured here, settable clock, reset before / after every test). Fictional
 * ticker ZZQ and fictional accounts 甲 (id 1, the default account renamed) / 乙 (id 2).
 *
 * <p>Requirement scenarios ("now" = 2026-11-20 10:00 New York, a Friday):
 * <pre>
 *  Z (甲, short call)  deposit 1,000.00 (11-01 10:00); 转入 200 ZZQ cost 1,600.00 (11-02);
 *                      A = STO 1 ZZQ 2026-12-18 9 C 18.34 (11-10 10:00); B = STO 1 same 37.34 (11-11 10:00)
 *                      → 1,055.68 / 0.00 / 1,055.68; ZZQ 200 shares, covered 200, available 0
 *  P (甲, short put)   deposit 30,000.00 (11-01 10:00); P1 = STO 2 ZZQ 2026-12-18 8 P 30.00 (11-10 10:00, frozen
 *                      1,600.00); P2 = STO 1 same 21.00 (11-11 10:00, frozen 800.00) → 30,051.00 / 2,400.00 / 27,651.00
 * </pre>
 * Record ids are taken from the responses and kept in {@link #ids} by label (A, B, P1, P2, C, ...).
 */
abstract class Feat051CloseLotsSystemTestBase extends Feat035AllAccountsSystemTestBase {

    static final String JIA = "甲";
    static final String YI = "乙";
    static final String EXP = "2026-12-18";
    static final String C9 = "ZZQ 2026-12-18 9 C";
    static final String P8 = "ZZQ 2026-12-18 8 P";
    static final String CLOSES = TRADES + "/closes";
    static final String AUDIT = "/api/audit-log";
    static final String CLOSED = "/api/closed-trades";
    static final String LOTS_INVALID = "CLOSE_LOTS_INVALID";
    static final String NOW = "2026-11-20T10:00";

    // =========================================================================================== scenarios

    /** Scenario Z in 甲; clock 2026-11-20 10:00 NY, API account 甲. */
    void scenarioZ() {
        setNow(NOW);
        renameAccount(A, JIA);
        accountId = A;
        ids.put("DEP", depositId("1000.00", "2026-11-01T10:00"));
        ids.put("ZZQ", transferInId("ZZQ", 200, "1600.00", "2026-11-02"));
        ids.put("A", openLot("STO", 1, "9", "CALL", "18.34", "2026-11-10T10:00"));
        ids.put("B", openLot("STO", 1, "9", "CALL", "37.34", "2026-11-11T10:00"));
        assertThat(cash(A)).isEqualTo("1055.68 / 0.00 / 1055.68");
        assertThat(stock(A, "ZZQ")).isEqualTo("200/200/0");
    }

    /** Scenario P in 甲; clock 2026-11-20 10:00 NY, API account 甲. */
    void scenarioP() {
        setNow(NOW);
        renameAccount(A, JIA);
        accountId = A;
        ids.put("DEP", depositId("30000.00", "2026-11-01T10:00"));
        ids.put("P1", openLot("STO", 2, "8", "PUT", "30.00", "2026-11-10T10:00"));
        ids.put("P2", openLot("STO", 1, "8", "PUT", "21.00", "2026-11-11T10:00"));
        assertThat(cash(A)).isEqualTo("30051.00 / 2400.00 / 27651.00");
    }

    /** AC5: 乙 (id 2) with 100 ZZQ and C = STO 1 ZZQ 2026-12-18 9 C; API account back to 甲. */
    void accountYiWithC() {
        String b = createAccount(YI);
        assertThat(b).as("乙 gets id 2 after the reset").isEqualTo(B);
        accountB = b;
        accountId = B;
        transferInId("ZZQ", 100, "800.00", "2026-11-02");
        ids.put("C", openLot("STO", 1, "9", "CALL", "20.00", "2026-11-12T10:00"));
        accountId = A;
    }

    // =========================================================================================== writes

    long openLot(String action, int contracts, String strike, String type, String net, String at) {
        ObjectNode body = trade(action, contracts, "ZZQ", EXP, strike, type, net, at);
        Resp r = post(TRADES, body.toString());
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** Close body (ZZQ, 2026-12-18) without contracts / lotTradeIds. */
    ObjectNode closeBody(String action, String strike, String type, String net, String at) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "ZZQ");
        n.put("expirationDate", EXP);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        n.putNull("note");
        return n;
    }

    static ArrayNode longs(ObjectNode parent, String field, List<Long> values) {
        ArrayNode arr = parent.putArray(field);
        values.forEach(arr::add);
        return arr;
    }

    /** Chosen-lots mode close (no contracts unless given). */
    Resp closeLotsResp(String action, String strike, String type, List<Long> lots, Integer contracts, String net,
                       String at) {
        ObjectNode b = closeBody(action, strike, type, net, at);
        longs(b, "lotTradeIds", lots);
        if (contracts != null) {
            b.put("contracts", contracts);
        }
        return post(CLOSES, b.toString());
    }

    JsonNode okCloseLots(String action, String strike, String type, List<Long> lots, String net, String at) {
        Resp r = closeLotsResp(action, strike, type, lots, null, net, at);
        assertThat(r.status()).as("POST closes %s %s -> %s", action, lots, r.body()).isEqualTo(201);
        assertFrozenConsistent(accountId);
        return r.body();
    }

    /** FIFO mode close (录入交易页, Q257): contracts, no lotTradeIds. */
    Resp closeFifoResp(String action, int contracts, String strike, String type, String net, String at) {
        ObjectNode b = closeBody(action, strike, type, net, at);
        b.put("contracts", contracts);
        return post(CLOSES, b.toString());
    }

    JsonNode okCloseFifo(String action, int contracts, String strike, String type, String net, String at) {
        Resp r = closeFifoResp(action, contracts, strike, type, net, at);
        assertThat(r.status()).as("POST closes FIFO %s %d -> %s", action, contracts, r.body()).isEqualTo(201);
        assertFrozenConsistent(accountId);
        return r.body();
    }

    /** PUT of a close; {@code lots} null = field absent (lots unchanged), {@code contracts} null = absent. */
    Resp putClose(long id, List<Long> lots, Integer contracts, String net, String at) {
        ObjectNode b = json.createObjectNode();
        if (lots != null) {
            longs(b, "lotTradeIds", lots);
        }
        if (contracts != null) {
            b.put("contracts", contracts);
        }
        b.put("netAmount", net);
        b.put("tradedAtLocal", at);
        b.putNull("note");
        return exchange(HttpMethod.PUT, TRADES + "/" + id, b.toString());
    }

    JsonNode okPutClose(long id, List<Long> lots, String net, String at) {
        Resp r = putClose(id, lots, null, net, at);
        assertThat(r.status()).as("PUT close %d %s -> %s", id, lots, r.body()).isEqualTo(200);
        assertFrozenConsistent(accountId);
        return r.body();
    }

    /** PUT of an opening trade (FEAT-005 body). */
    Resp putOpen(long id, int contracts, String net, String at) {
        ObjectNode b = json.createObjectNode();
        b.put("contracts", contracts);
        b.put("netAmount", net);
        b.put("tradedAtLocal", at);
        b.put("multiplier", 100);
        b.putNull("note");
        return exchange(HttpMethod.PUT, TRADES + "/" + id, b.toString());
    }

    Resp deleteTrade(long id) {
        return exchange(HttpMethod.DELETE, TRADES + "/" + id, null);
    }

    Resp assignResp(String strike, String type, int contracts, String date) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "ZZQ");
        n.put("expirationDate", EXP);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        n.put("assignmentDate", date);
        n.put("assignmentFee", "0.00");
        n.putNull("note");
        return post(TRADES + "/assignments", n.toString());
    }

    // =========================================================================================== reads

    JsonNode tradeById(long id) {
        Resp r = get(TRADES + "/" + id);
        assertThat(r.status()).as("GET trade %d -> %s", id, r.body()).isEqualTo(200);
        return r.body();
    }

    /** "total / frozen / available" of the cash summary of {@code account}. */
    String cash(String account) {
        JsonNode s = okAs(account, CASH_SUMMARY);
        return s.get("totalCash").asText() + " / " + s.get("frozenCash").asText() + " / "
                + s.get("availableCash").asText();
    }

    /** "shares/covered/available" of a stock row in {@code account}. */
    String stock(String account, String symbol) {
        JsonNode p = stockRowOf(okAs(account, STOCK_POSITIONS), Long.parseLong(account), symbol);
        return p.get("shares").asInt() + "/" + p.get("coveredShares").asInt() + "/" + p.get("availableShares").asInt();
    }

    /** Option row of {@code account} (single-account call) or null. */
    JsonNode optRow(String account, String label) {
        for (JsonNode p : okAs(account, OPTION_POSITIONS).get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    /** Labels in {@link #ids} that are not option trades (deposit / stock ids live in other id sequences). */
    static final java.util.Set<String> NON_OPTION_LABELS = java.util.Set.of("DEP", "ZZQ");

    /** Label of an option trade id (deposit / stock records share numbers with option trades, so they are skipped). */
    String label(long id) {
        for (Map.Entry<String, Long> e : ids.entrySet()) {
            if (!NON_OPTION_LABELS.contains(e.getKey()) && e.getValue() == id) {
                return e.getKey();
            }
        }
        return "#" + id;
    }

    /** "label tradedAtLocal remainingContracts remainingNetAmount remainingFrozen closeDefault" per position lot. */
    List<String> lots(JsonNode row) {
        List<String> out = new ArrayList<>();
        row.get("lots").forEach(l -> out.add(label(l.get("tradeId").asLong()) + " " + l.get("tradedAtLocal").asText()
                + " " + l.get("remainingContracts").asInt() + " " + l.get("remainingNetAmount").asText() + " "
                + l.get("remainingFrozen").asText() + " " + l.get("closeDefault").asText()));
        return out;
    }

    /** "label action contracts netAmount frozen" per covered lot of a trade / closed-trade item. */
    List<String> covered(JsonNode t) {
        List<String> out = new ArrayList<>();
        t.get("coveredLots").forEach(l -> out.add(label(l.get("tradeId").asLong()) + " " + l.get("action").asText()
                + " " + l.get("contracts").asInt() + " " + l.get("netAmount").asText() + " "
                + l.get("frozen").asText()));
        return out;
    }

    /** The closed-trades item of option trade {@code id} in {@code account}'s list (may be ALL). */
    JsonNode closedItem(String account, long id) {
        for (JsonNode i : okAs(account, CLOSED + "?type=OPTION&size=100").get("items")) {
            if (i.get("id").asLong() == id) {
                return i;
            }
        }
        throw new AssertionError("closed item " + id + " not in " + account);
    }

    JsonNode lotChoices(long closeId, String tradedAtLocal) {
        Resp r = lotChoicesResp(closeId, tradedAtLocal);
        assertThat(r.status()).as("GET close-lot-choices %d -> %s", closeId, r.body()).isEqualTo(200);
        return r.body();
    }

    Resp lotChoicesResp(long closeId, String tradedAtLocal) {
        return get(TRADES + "/" + closeId + "/close-lot-choices"
                + (tradedAtLocal == null ? "" : "?tradedAtLocal=" + tradedAtLocal));
    }

    /** "label tradedAtLocal contracts netAmount frozen selected" per choice. */
    List<String> choices(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("lots").forEach(l -> out.add(label(l.get("tradeId").asLong()) + " " + l.get("tradedAtLocal").asText()
                + " " + l.get("contracts").asInt() + " " + l.get("netAmount").asText() + " "
                + l.get("frozen").asText() + " " + l.get("selected").asBoolean()));
        return out;
    }

    /** Option-trade audit records (the scenario's account rename writes an ACCOUNT record too). */
    JsonNode auditItems() {
        return okAs(accountId, AUDIT + "?objectType=OPTION_TRADE&size=100").get("items");
    }

    static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.get("text").asText()));
        return out;
    }

    /** "label action tradedAtLocal contracts" per audit selectedLots entry. */
    List<String> selectedLots(JsonNode snapshot) {
        List<String> out = new ArrayList<>();
        snapshot.get("selectedLots").forEach(l -> out.add(label(l.get("tradeId").asLong()) + " "
                + l.get("action").asText() + " " + l.get("tradedAtLocal").asText() + " " + l.get("contracts").asInt()));
        return out;
    }

    List<Map<String, Object>> closeLotRows() {
        return jdbc.queryForList(
                "SELECT close_trade_id, lot_trade_id, account_id FROM option_close_lot ORDER BY close_trade_id, lot_trade_id");
    }

    /** Stored lot ids of a close, from the table (the contract's storage, Q255 A). */
    List<Long> storedLots(long closeId) {
        return jdbc.queryForList(
                "SELECT lot_trade_id FROM option_close_lot WHERE close_trade_id = ? ORDER BY lot_trade_id", Long.class,
                closeId);
    }

    void assertFrozenConsistent(String account) {
        JsonNode pos = okAs(account, OPTION_POSITIONS);
        JsonNode cash = okAs(account, CASH_SUMMARY);
        assertThat(pos.get("frozenTotal").asText()).as("positions frozenTotal == cash frozenCash")
                .isEqualTo(cash.get("frozenCash").asText());
    }

    // =========================================================================================== rejections

    /** Everything observable incl. closed trades and the stored lots; must not change on a rejected write. */
    Map<String, Object> snapshot() {
        Map<String, Object> w = new LinkedHashMap<>();
        for (String t : List.of("account", "cash_transfer", "cash_ledger_entry", "option_trade", "stock_trade",
                "audit_record")) {
            w.put(t, count(t));
        }
        List<String> accounts = accountB == null ? List.of(A) : List.of(A, B);
        for (String acc : accounts) {
            for (String p : List.of(CASH_SUMMARY, OPTION_POSITIONS, STOCK_POSITIONS, PNL)) {
                w.put(acc + " " + p, okAs(acc, p));
            }
            w.put(acc + " ledger", okAs(acc, "/api/cash/ledger?size=100"));
            w.put(acc + " trades", okAs(acc, TRADES + "?size=100"));
            w.put(acc + " stock-trades", okAs(acc, STOCK_TRADES + "?size=100"));
            w.put(acc + " audit", okAs(acc, AUDIT + "?size=100"));
            w.put(acc + " closed", okAs(acc, CLOSED + "?size=100"));
        }
        w.put("option_close_lot", closeLotRows());
        return w;
    }

    /** 422 with {@code code}, empty details, nothing changed; returns the message. */
    String rejected422(Resp r, Map<String, Object> before, String code, String path) {
        assertErrorShape(r, 422, code, path);
        assertThat(r.body().get("details")).as("details of %s", code).isEmpty();
        assertThat(snapshot()).as("nothing changed after rejected %s", code).isEqualTo(before);
        return r.body().get("message").asText();
    }

    String rejectedLots(Resp r, Map<String, Object> before, String path) {
        return rejected422(r, before, LOTS_INVALID, path);
    }

    /** 400 VALIDATION_ERROR with exactly these "field=message" details; nothing changed. */
    void rejected400(Resp r, Map<String, Object> before, String path, String... details) {
        assertErrorShape(r, 400, "VALIDATION_ERROR", path);
        assertThat(details(r)).containsExactly(details);
        assertThat(snapshot()).isEqualTo(before);
    }

    static List<Long> ls(Long... v) {
        return List.of(v);
    }
}
