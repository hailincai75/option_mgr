package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-015 到期批量作废 ({@code POST /api/option-trades/expirations/batch}), end-to-end over HTTP against the whole
 * application (in-memory H2, profile "test"). The clock is a {@link MutableClock}; unless a test moves it, "now" =
 * 2026-11-23 10:00 New York (Monday after the Friday 2026-11-20 expiration). Fictional tickers only.
 *
 * <p>Scenario S of the requirement (default account): deposit 50,000.00; 转入 IXQZ 100 shares (cost 750.00);
 * S1 STO 2 XYZ 11-20 45 P 240.00; S2 STO 1 XYZ 11-20 42 P 80.00; S3 BTO 1 QRTV 11-20 30 C 60.00;
 * S4 STO 1 IXQZ 11-20 9 C 25.00; S5 STO 1 XYZ 11-27 40 P 70.00.
 *
 * <p>Every rejected batch must leave cash, ledger, trades, positions, closed trades and the audit log untouched (Q116).
 */
class Feat015BatchExpirationSystemTest extends OptionTradeSystemTestBase {

    static final Instant MON_1000 = Instant.parse("2026-11-23T15:00:00Z");
    static final String BATCH = "/api/option-trades/expirations/batch";
    static final String EXPIRATIONS = "/api/option-trades/expirations";
    static final String ASSIGNMENTS = "/api/option-trades/assignments";
    static final String CLOSES = "/api/option-trades/closes";
    static final String CLOSED = "/api/closed-trades";
    static final String AUDIT = "/api/audit-log";
    static final String STOCK_POS = "/api/stock-positions";

    static final String XYZ45 = "XYZ 2026-11-20 45 P";
    static final String XYZ42 = "XYZ 2026-11-20 42 P";
    static final String QRTV30 = "QRTV 2026-11-20 30 C";
    static final String IXQZ9 = "IXQZ 2026-11-20 9 C";
    static final String XYZ40 = "XYZ 2026-11-27 40 P";
    static final String CUTOFF = "2026-11-20T21:00:00Z";
    static final String CUTOFF_LOCAL = "2026-11-20T16:00:00";
    static final String TOO_EARLY = "到期日（2026-11-20）美东 17:30 起才能作废";

    long s1;
    long s2;
    long s3;
    long s4;
    long s5;

    @Override
    Instant now() {
        return MON_1000;
    }

    // ================================================================== helpers

    long open(String action, int contracts, String symbol, String exp, String strike, String type, String net,
              String at) {
        return okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, 100)).get("id").asLong();
    }

    /** Stock 转入 with the given total cost (no cash, no ledger row). */
    void stockTransferIn(String symbol, int shares, String amount, String tradeDate) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradeDate", tradeDate);
        n.putNull("note");
        Resp r = postRaw("/api/stock-trades", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("TRANSFER_IN -> %s", r.body()).isEqualTo(201);
    }

    /** Scenario S in the current account. */
    void scenarioS() {
        deposit("50000.00", "2026-11-02T10:00");
        stockTransferIn("IXQZ", 100, "750.00", "2026-10-01");
        s1 = open("STO", 2, "XYZ", "2026-11-20", "45", "PUT", "240.00", "2026-11-03T10:00");
        s2 = open("STO", 1, "XYZ", "2026-11-20", "42", "PUT", "80.00", "2026-11-03T11:00");
        s3 = open("BTO", 1, "QRTV", "2026-11-20", "30", "CALL", "60.00", "2026-11-04T10:00");
        s4 = open("STO", 1, "IXQZ", "2026-11-20", "9", "CALL", "25.00", "2026-11-05T10:00");
        s5 = open("STO", 1, "XYZ", "2026-11-27", "40", "PUT", "70.00", "2026-11-06T10:00");
        assertSummary("50355.00", "17200.00", "33155.00");
    }

    ObjectNode item(String symbol, String exp, String strike, String type, Integer contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        if (contracts != null) {
            n.put("contracts", contracts);
        }
        return n;
    }

    ObjectNode xyz45(int c) {
        return item("XYZ", "2026-11-20", "45", "PUT", c);
    }

    ObjectNode xyz42(int c) {
        return item("XYZ", "2026-11-20", "42", "PUT", c);
    }

    ObjectNode qrtv30(int c) {
        return item("QRTV", "2026-11-20", "30", "CALL", c);
    }

    ObjectNode ixqz9(int c) {
        return item("IXQZ", "2026-11-20", "9", "CALL", c);
    }

    ObjectNode xyz40(int c) {
        return item("XYZ", "2026-11-27", "40", "PUT", c);
    }

    ObjectNode batch(ObjectNode... items) {
        ObjectNode n = json.createObjectNode();
        ArrayNode arr = n.putArray("items");
        for (ObjectNode i : items) {
            arr.add(i);
        }
        return n;
    }

    /** The four 11-20 items of scenario S, in the (scrambled) order of the contract example. */
    ObjectNode batchS4() {
        return batch(xyz45(2), xyz42(1), qrtv30(1), ixqz9(1));
    }

    Resp postBatch(ObjectNode body) {
        return postRaw(BATCH, body.toString(), MediaType.APPLICATION_JSON);
    }

    /** POST a batch that must succeed; checks the contract shape of the 201 body. */
    JsonNode okBatch(ObjectNode body) {
        Resp r = postBatch(body);
        assertThat(r.status()).as("batch %s -> %s", body, r.body()).isEqualTo(201);
        JsonNode b = r.body();
        assertThat(b.get("count").isIntegralNumber()).as("count is a number").isTrue();
        assertThat(b.get("realizedPnlTotal").isTextual()).as("realizedPnlTotal is a string").isTrue();
        assertThat(b.get("frozenChangeTotal").isTextual()).as("frozenChangeTotal is a string").isTrue();
        assertThat(b.get("realizedPnlTotal").asText()).matches("-?\\d+\\.\\d{2}");
        assertThat(b.get("frozenChangeTotal").asText()).matches("(-\\d+|0)\\.\\d{2}");
        JsonNode exps = b.get("expirations");
        assertThat(exps.isArray()).isTrue();
        assertThat(b.get("count").asInt()).isEqualTo(exps.size()).isEqualTo(body.get("items").size());
        long prev = Long.MIN_VALUE;
        java.math.BigDecimal pnl = java.math.BigDecimal.ZERO;
        java.math.BigDecimal frozen = java.math.BigDecimal.ZERO;
        for (JsonNode t : exps) {
            assertThat(t.get("action").asText()).isEqualTo("EXP");
            assertThat(t.get("netAmount").isTextual()).isTrue();
            assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
            assertThat(t.get("cashChange").asText()).isEqualTo("0.00");
            assertThat(t.get("frozenChange").isTextual()).isTrue();
            assertThat(t.get("realizedPnl").isTextual()).isTrue();
            assertThat(t.get("note").isNull()).as("Q84: no note").isTrue();
            assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
            assertThat(t.get("expiredDirection").asText()).isIn("SHORT", "LONG");
            assertThat(t.get("coveredLots").isArray()).isTrue();
            Instant.parse(t.get("createdAt").asText());
            long id = t.get("id").asLong();
            assertThat(id).as("ids increase in processing order").isGreaterThan(prev);
            prev = id;
            pnl = pnl.add(new java.math.BigDecimal(t.get("realizedPnl").asText()));
            frozen = frozen.add(new java.math.BigDecimal(t.get("frozenChange").asText()));
            // each element is exactly the trade representation
            assertThat(getTrade(id)).as("GET /api/option-trades/%d equals the batch element", id).isEqualTo(t);
        }
        assertThat(new java.math.BigDecimal(b.get("realizedPnlTotal").asText())).isEqualByComparingTo(pnl);
        assertThat(new java.math.BigDecimal(b.get("frozenChangeTotal").asText())).isEqualByComparingTo(frozen);
        assertFrozenConsistent();
        return b;
    }

    /** "label realizedPnl frozenChange tradedAt direction" per expiration, in response order. */
    List<String> expLines(JsonNode batchResult) {
        List<String> out = new ArrayList<>();
        batchResult.get("expirations").forEach(t -> out.add(t.get("contractLabel").asText() + " "
                + t.get("contracts").asInt() + " " + t.get("realizedPnl").asText() + " "
                + t.get("frozenChange").asText() + " " + t.get("tradedAt").asText() + " "
                + t.get("expiredDirection").asText()));
        return out;
    }

    /** Must be rejected with 422 BATCH_EXPIRATION_REJECTED and n failures; nothing changes; returns failures. */
    JsonNode rejectedBatch(ObjectNode body, int n) {
        Full before = full();
        Resp r = postBatch(body);
        assertErrorShape(r, 422, "BATCH_EXPIRATION_REJECTED", BATCH);
        assertThat(r.body().get("details")).as("details is []").isEmpty();
        assertThat(r.body().get("message").asText()).isEqualTo("有 " + n + " 个持仓不能作废，整批未保存");
        JsonNode f = r.body().get("failures");
        assertThat(f).as("failures").isNotNull();
        assertThat(f.isArray()).isTrue();
        assertThat(f).hasSize(n);
        for (JsonNode x : f) {
            assertThat(x.get("index").isIntegralNumber()).isTrue();
            for (String k : List.of("symbol", "expirationDate", "strike", "optionType", "contractLabel", "code",
                    "message")) {
                assertThat(x.get(k).isTextual()).as("failures[].%s is a string", k).isTrue();
            }
            assertThat(x.get("code").asText()).isIn("EXPIRY_TOO_EARLY", "NO_POSITION_TO_EXPIRE", "POSITION_CHANGED",
                    "EXPIRY_BLOCKED_BY_LATE_TRADE");
        }
        assertThat(full()).as("nothing saved after a rejected batch").isEqualTo(before);
        return f;
    }

    /** "index symbol expirationDate strike optionType | label | code | message". */
    List<String> failureLines(JsonNode failures) {
        List<String> out = new ArrayList<>();
        failures.forEach(f -> out.add(f.get("index").asInt() + " " + f.get("symbol").asText() + " "
                + f.get("expirationDate").asText() + " " + f.get("strike").asText() + " "
                + f.get("optionType").asText() + " | " + f.get("contractLabel").asText() + " | "
                + f.get("code").asText() + " | " + f.get("message").asText()));
        return out;
    }

    /** 400 VALIDATION_ERROR whose details are exactly these (field=message) entries in this order; no failures. */
    void batchValidation(String rawBody, String... fieldEqMessage) {
        Full before = full();
        Resp r = postRaw(BATCH, rawBody, MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "VALIDATION_ERROR", BATCH);
        assertThat(r.body().has("failures")).as("only BATCH_EXPIRATION_REJECTED has failures").isFalse();
        List<String> details = new ArrayList<>();
        r.body().get("details").forEach(d -> details.add(d.get("field").asText() + "=" + d.get("message").asText()));
        assertThat(details).as("details for %s", rawBody).containsExactly(fieldEqMessage);
        assertThat(full()).isEqualTo(before);
    }

    void batchBadRequest(String rawBody) {
        Full before = full();
        Resp r = postRaw(BATCH, rawBody, MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "BAD_REQUEST", BATCH);
        assertThat(r.body().has("failures")).isFalse();
        assertThat(full()).isEqualTo(before);
    }

    record Full(State state, JsonNode positions, JsonNode ledger, JsonNode audit, JsonNode trades, JsonNode closed) {
    }

    Full full() {
        return new Full(state(), positionsBody(), ledger(1, 100), get(AUDIT + "?size=100").body(),
                get(TRADES + "?size=100").body(), get(CLOSED + "?size=100").body());
    }

    JsonNode positionsBody() {
        Resp r = get("/api/option-positions");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode position(String label) {
        for (JsonNode p : positionsBody().get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    List<String> positionLabels() {
        List<String> out = new ArrayList<>();
        positionsBody().get("positions").forEach(p -> out.add(p.get("contractLabel").asText()));
        return out;
    }

    /** Labels of the positions that are expirable (= rows counted by 批量作废（N）). */
    List<String> expirableLabels() {
        List<String> out = new ArrayList<>();
        positionsBody().get("positions").forEach(p -> {
            if (p.get("expirable").asBoolean()) {
                out.add(p.get("contractLabel").asText());
            }
        });
        return out;
    }

    void assertFrozenConsistent() {
        assertThat(positionsBody().get("frozenTotal").asText()).isEqualTo(summary().get("frozenCash").asText());
    }

    JsonNode closed(String query) {
        Resp r = get(CLOSED + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("closed %s -> %s", query, r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode tradeList(String query) {
        Resp r = get(TRADES + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("trades %s -> %s", query, r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode audit() {
        return get(AUDIT + "?size=100").body();
    }

    JsonNode pnl() {
        Resp r = get("/api/pnl-summary");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode dashboard() {
        Resp r = get("/api/dashboard");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    /** "IXQZ shares/covered/available" of the stock position. */
    String stock(String symbol) {
        Resp r = get(STOCK_POS);
        assertThat(r.status()).isEqualTo(200);
        for (JsonNode p : r.body().get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return symbol + " " + p.get("shares").asInt() + "/" + p.get("coveredShares").asInt() + "/"
                        + p.get("availableShares").asInt();
            }
        }
        return null;
    }

    long newAccount(String name) {
        String saved = accountId;
        accountId = null;
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        Resp r = postRaw("/api/accounts", n.toString(), MediaType.APPLICATION_JSON);
        accountId = saved;
        assertThat(r.status()).as("create account -> %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    List<Long> lotIds(JsonNode t) {
        List<Long> out = new ArrayList<>();
        t.get("coveredLots").forEach(l -> out.add(l.get("tradeId").asLong()));
        return out;
    }

    JsonNode expOf(JsonNode batchResult, String label) {
        for (JsonNode t : batchResult.get("expirations")) {
            if (t.get("contractLabel").asText().equals(label)) {
                return t;
            }
        }
        throw new AssertionError("no expiration " + label);
    }

    // ================================================================== AC 1

    @Test
    @DisplayName("AC1 11-23 10:00：S1–S4 可以作废（按钮 N = 4），S5 不可；持仓顺序 IXQZ 9 C、QRTV 30 C、XYZ 42 P、XYZ 45 P；确认框所需字段")
    void ac1_expirableRowsAndDialogData() {
        scenarioS();
        assertThat(positionLabels()).containsExactly(IXQZ9, QRTV30, XYZ42, XYZ45, XYZ40);
        assertThat(expirableLabels()).containsExactly(IXQZ9, QRTV30, XYZ42, XYZ45);
        JsonNode p45 = position(XYZ45);
        assertThat(List.of(p45.get("direction").asText(), p45.get("expiryContracts").asInt(),
                p45.get("openingNetTotal").asText(), p45.get("frozenCash").asText()))
                .containsExactly("SHORT", 2, "240.00", "9000.00");
        JsonNode q = position(QRTV30);
        assertThat(List.of(q.get("direction").asText(), q.get("expiryContracts").asInt(),
                q.get("openingNetTotal").asText(), q.get("frozenCash").asText()))
                .containsExactly("LONG", 1, "-60.00", "0.00");
        assertThat(position(XYZ40).get("expirable").asBoolean()).isFalse();
    }

    // ================================================================== AC 2

    @Test
    @DisplayName("AC2 全部作废：201，作废 4 个、+285.00、−13,200.00；按处理顺序返回、id 递增；4 条 EXP 记在 11-20 16:00；现金、流水、交易列表、已平仓、股票覆盖、不写审计")
    void ac2_expireAll() {
        scenarioS();
        long auditBefore = audit().get("totalItems").asLong();
        JsonNode b = okBatch(batchS4());
        assertThat(b.get("count").asInt()).isEqualTo(4);
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("285.00");
        assertThat(b.get("frozenChangeTotal").asText()).isEqualTo("-13200.00");
        assertThat(expLines(b)).containsExactly(
                IXQZ9 + " 1 25.00 0.00 " + CUTOFF + " SHORT",
                QRTV30 + " 1 -60.00 0.00 " + CUTOFF + " LONG",
                XYZ42 + " 1 80.00 -4200.00 " + CUTOFF + " SHORT",
                XYZ45 + " 2 240.00 -9000.00 " + CUTOFF + " SHORT");
        for (JsonNode t : b.get("expirations")) {
            assertThat(t.get("tradedAtLocal").asText()).isEqualTo(CUTOFF_LOCAL);
            assertThat(t.get("createdAt").asText()).isEqualTo("2026-11-23T15:00:00Z");
            assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        }
        assertThat(lotIds(expOf(b, XYZ45))).containsExactly(s1);
        assertThat(lotIds(expOf(b, XYZ42))).containsExactly(s2);
        assertThat(lotIds(expOf(b, QRTV30))).containsExactly(s3);
        assertThat(lotIds(expOf(b, IXQZ9))).containsExactly(s4);
        JsonNode lot = expOf(b, XYZ45).get("coveredLots").get(0);
        assertThat(List.of(lot.get("action").asText(), lot.get("tradedAtLocal").asText(), lot.get("contracts").asInt(),
                lot.get("netAmount").asText(), lot.get("frozen").asText()))
                .containsExactly("STO", "2026-11-03T10:00:00", 2, "240.00", "9000.00");
        // only short puts have a ledger row
        assertThat(expOf(b, IXQZ9).get("ledgerEntryId").isNull()).isTrue();
        assertThat(expOf(b, QRTV30).get("ledgerEntryId").isNull()).isTrue();
        long l42 = expOf(b, XYZ42).get("ledgerEntryId").asLong();
        long l45 = expOf(b, XYZ45).get("ledgerEntryId").asLong();
        assertThat(l42).as("ledger rows saved in processing order").isLessThan(l45);

        assertSummary("50355.00", "4000.00", "46355.00");
        assertThat(positionLabels()).containsExactly(XYZ40);
        assertThat(expirableLabels()).isEmpty();
        assertThat(dashboard().get("positions").get("expiredUnprocessedCount").asInt()).isZero();

        // ledger: 6 + 2 OPTION_EXPIRE rows at the cutoff, XYZ 45 P (later id) on top
        JsonNode led = ledger(1, 100);
        assertThat(led.get("totalItems").asInt()).isEqualTo(8);
        List<String> top = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            JsonNode row = led.get("items").get(i);
            top.add(row.get("occurredAt").asText() + " " + row.get("sourceType").asText() + " "
                    + row.get("sourceId").asLong() + " " + row.get("amount").asText() + " "
                    + row.get("frozenChange").asText() + " " + row.get("balanceAfter").asText() + " "
                    + row.get("description").asText());
        }
        assertThat(top).containsExactly(
                CUTOFF + " OPTION_EXPIRE " + expOf(b, XYZ45).get("id").asLong() + " 0.00 -9000.00 50355.00 到期作废 2 张 "
                        + XYZ45,
                CUTOFF + " OPTION_EXPIRE " + expOf(b, XYZ42).get("id").asLong() + " 0.00 -4200.00 50355.00 到期作废 1 张 "
                        + XYZ42);

        // trade list: 4 EXP, all at 16:00, net 0.00
        JsonNode exps = tradeList("action=EXP");
        assertThat(exps.get("totalItems").asInt()).isEqualTo(4);
        for (JsonNode t : exps.get("items")) {
            assertThat(t.get("tradedAtLocal").asText()).isEqualTo(CUTOFF_LOCAL);
            assertThat(t.get("netAmount").asText()).isEqualTo("0.00");
        }
        assertThat(tradeList("").get("totalItems").asInt()).isEqualTo(9);

        // closed trades: 4 rows
        JsonNode c = closed("");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(4);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("285.00");
        List<String> closedPnl = new ArrayList<>();
        c.get("items").forEach(i -> closedPnl.add(i.get("contractLabel").asText() + " " + i.get("action").asText()
                + " " + i.get("realizedPnl").asText()));
        assertThat(closedPnl).containsExactlyInAnyOrder(IXQZ9 + " EXP 25.00", QRTV30 + " EXP -60.00",
                XYZ42 + " EXP 80.00", XYZ45 + " EXP 240.00");

        // the covered call no longer locks shares
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/0/100");
        // Q119: no audit record for a batch
        assertThat(audit().get("totalItems").asLong()).isEqualTo(auditBefore).isZero();
    }

    @Test
    @DisplayName("AC2 处理顺序与请求顺序无关：同一批正序 / 倒序请求得到相同的 expirations 顺序")
    void ac2_orderIndependentOfRequest() {
        scenarioS();
        JsonNode b = okBatch(batch(ixqz9(1), xyz45(2), qrtv30(1), xyz42(1)));
        List<String> labels = new ArrayList<>();
        b.get("expirations").forEach(t -> labels.add(t.get("contractLabel").asText()));
        assertThat(labels).containsExactly(IXQZ9, QRTV30, XYZ42, XYZ45);
    }

    // ================================================================== AC 3

    @Test
    @DisplayName("AC3 只作废 3 个（XYZ 45 P 不选）：+45.00、−4,200.00；XYZ 45 P 仍可作废；再单独被指派 2 张 → 总现金、冻结各 −9,000.00")
    void ac3_partialThenAssign() {
        scenarioS();
        JsonNode b = okBatch(batch(xyz42(1), qrtv30(1), ixqz9(1)));
        assertThat(b.get("count").asInt()).isEqualTo(3);
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("45.00");
        assertThat(b.get("frozenChangeTotal").asText()).isEqualTo("-4200.00");
        assertThat(expirableLabels()).containsExactly(XYZ45);
        assertSummary("50355.00", "13000.00", "37355.00");

        ObjectNode asg = json.createObjectNode();
        asg.put("symbol", "XYZ");
        asg.put("expirationDate", "2026-11-20");
        asg.put("strike", "45");
        asg.put("optionType", "PUT");
        asg.put("contracts", 2);
        asg.put("assignmentDate", "2026-11-20");
        asg.putNull("note");
        Resp r = postRaw(ASSIGNMENTS, asg.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("assign -> %s", r.body()).isEqualTo(201);
        assertSummary("41355.00", "4000.00", "37355.00");
        assertThat(expirableLabels()).isEmpty();
        assertThat(positionLabels()).containsExactly(XYZ40);
    }

    // ================================================================== AC 4

    @Test
    @DisplayName("AC4 对话框打开期间补录 XYZ 42 P：整批 422，只 XYZ 42 P 失败「持仓已变化（当前 2 张）」，什么都不保存、冻结 21,400.00；按 2 张重试 → 4 个、+335.00、−17,400.00")
    void ac4_oneFailureRejectsAll() {
        scenarioS();
        open("STO", 1, "XYZ", "2026-11-20", "42", "PUT", "50.00", "2026-11-19T10:00");
        assertSummary("50405.00", "21400.00", "29005.00");
        JsonNode f = rejectedBatch(batchS4(), 1);
        assertThat(failureLines(f)).containsExactly(
                "1 XYZ 2026-11-20 42 PUT | " + XYZ42 + " | POSITION_CHANGED | 持仓已变化（当前 2 张），请刷新后重试");
        assertThat(tradeList("action=EXP").get("totalItems").asInt()).isZero();
        assertSummary("50405.00", "21400.00", "29005.00");

        JsonNode p = position(XYZ42);
        assertThat(p.get("expiryContracts").asInt()).isEqualTo(2);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("130.00");
        JsonNode b = okBatch(batch(xyz45(2), xyz42(2), qrtv30(1), ixqz9(1)));
        assertThat(b.get("count").asInt()).isEqualTo(4);
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("335.00");
        assertThat(b.get("frozenChangeTotal").asText()).isEqualTo("-17400.00");
        assertSummary("50405.00", "4000.00", "46405.00");
    }

    // ================================================================== AC 5

    /** Stores an STC 1 QRTV 11-20 30 C (20.00) at 2026-11-20 16:10 NY, bypassing the Q83 check (pre-FEAT-012 data). */
    long legacyLateQrtvStc() {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "QRTV");
        n.put("expirationDate", "2026-11-20");
        n.put("strike", "30");
        n.put("optionType", "CALL");
        n.put("action", "STC");
        n.put("contracts", 1);
        n.put("netAmount", "20.00");
        n.put("tradedAtLocal", "2026-11-20T15:50");
        Resp r = postRaw(CLOSES, n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("close -> %s", r.body()).isEqualTo(201);
        long id = r.body().get("id").asLong();
        java.time.OffsetDateTime late = java.time.OffsetDateTime.parse("2026-11-20T21:10:00Z");
        assertThat(jdbc.update("UPDATE option_trade SET traded_at = ? WHERE id = ?", late, id)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE cash_ledger_entry SET occurred_at = ? WHERE source_type = 'OPTION_CLOSE' "
                + "AND source_id = ?", late, id)).isEqualTo(1);
        assertThat(getTrade(id).get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:10:00");
        return id;
    }

    static final String QRTV_LATE_MSG = "2026-11-20 16:10 的 STC 1 张晚于到期日美东 16:00，与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它";

    @Test
    @DisplayName("AC5 多个失败项全部返回（按处理顺序，带请求下标）：QRTV 30 C 旧的 16:10 STC、XYZ 42 P 张数 2；IXQZ、XYZ 45 P 也未作废；原因与单个作废一字不差")
    void ac5_allFailuresReturned() {
        scenarioS();
        legacyLateQrtvStc();
        JsonNode f = rejectedBatch(batch(xyz45(2), xyz42(2), qrtv30(1), ixqz9(1)), 2);
        assertThat(failureLines(f)).containsExactly(
                "2 QRTV 2026-11-20 30 CALL | " + QRTV30 + " | EXPIRY_BLOCKED_BY_LATE_TRADE | " + QRTV_LATE_MSG,
                "1 XYZ 2026-11-20 42 PUT | " + XYZ42 + " | POSITION_CHANGED | 持仓已变化（当前 1 张），请刷新后重试");
        assertThat(tradeList("action=EXP").get("totalItems").asInt()).isZero();
        assertThat(expirableLabels()).contains(IXQZ9, XYZ45);

        // failures[].message is exactly what the single expiry returns for the same item
        for (ObjectNode single : List.of(qrtv30(1), xyz42(2))) {
            Resp r = postRaw(EXPIRATIONS, single.toString(), MediaType.APPLICATION_JSON);
            assertThat(r.status()).isEqualTo(422);
            assertThat(r.body().has("failures")).as("single expiry has no failures").isFalse();
            String label = single.get("symbol").asText().equals("QRTV") ? QRTV30 : XYZ42;
            JsonNode match = null;
            for (JsonNode x : f) {
                if (x.get("contractLabel").asText().equals(label)) {
                    match = x;
                }
            }
            assertThat(match).isNotNull();
            assertThat(r.body().get("code").asText()).isEqualTo(match.get("code").asText());
            assertThat(r.body().get("message").asText()).isEqualTo(match.get("message").asText());
        }
    }

    @Test
    @DisplayName("AC5 请求为空 / 缺 items / null → 400「请至少选择一个持仓」；同一合约出现两次（\"45\" 与 \"45.00\"、标的大小写）→ 400 第二次及以后每项一条；什么都不保存")
    void ac5_emptyAndDuplicate() {
        scenarioS();
        batchValidation("{\"items\":[]}", "items=请至少选择一个持仓");
        batchValidation("{}", "items=请至少选择一个持仓");
        batchValidation("{\"items\":null}", "items=请至少选择一个持仓");

        ObjectNode dup = batch(xyz45(2), xyz42(1), item(" xyz ", "2026-11-20", "45.00", "PUT", 2));
        batchValidation(dup.toString(), "items[2]=同一合约（XYZ 2026-11-20 45 P）不能出现两次");

        ObjectNode triple = batch(xyz45(2), item("XYZ", "2026-11-20", "45.0", "PUT", 2), xyz45(2));
        batchValidation(triple.toString(), "items[1]=同一合约（XYZ 2026-11-20 45 P）不能出现两次",
                "items[2]=同一合约（XYZ 2026-11-20 45 P）不能出现两次");

        // same symbol / strike / date but C vs P is not a duplicate; neither is a different strike
        Full before = full();
        Resp ok = postBatch(batch(xyz45(2), item("XYZ", "2026-11-20", "45", "CALL", 1)));
        assertThat(ok.status()).as("not a duplicate -> business check, %s", ok.body()).isEqualTo(422);
        assertThat(ok.body().get("code").asText()).isEqualTo("BATCH_EXPIRATION_REJECTED");
        assertThat(full()).isEqualTo(before);
        assertThat(tradeList("action=EXP").get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("请求校验：每项字段错误带下标（items[1].contracts、items[0].symbol）；超过 500 项；类型 / JSON 无法解析 → BAD_REQUEST；校验先于业务检查")
    void requestValidation() {
        scenarioS();
        batchValidation(batch(xyz45(2), xyz42(-1)).toString(), "items[1].contracts=张数必须是非负整数");
        batchValidation(batch(xyz45(2), item("XYZ", "2026-11-20", "42", "PUT", null)).toString(), "items[1].contracts=张数不能为空");
        ObjectNode noSymbol = xyz42(1);
        noSymbol.remove("symbol");
        batchValidation(batch(noSymbol, xyz45(2)).toString(), "items[0].symbol=标的不能为空");
        // a valid-format item that would fail the business check (S5 not expired yet) does not matter: 400 first
        batchValidation(batch(xyz40(1), qrtv30(-2)).toString(), "items[1].contracts=张数必须是非负整数");

        ObjectNode big = json.createObjectNode();
        ArrayNode arr = big.putArray("items");
        for (int i = 0; i < 501; i++) {
            arr.add(item("XYZ", "2026-11-20", String.valueOf(100 + i), "PUT", 1));
        }
        batchValidation(big.toString(), "items=一次最多作废 500 个持仓");

        batchBadRequest(batch(item("XYZ", "2026-11-20", "45", "P", 2)).toString());
        batchBadRequest(batch(item("XYZ", "2026-13-40", "45", "PUT", 2)).toString());
        batchBadRequest("{\"items\":[");
        batchBadRequest("{\"items\":\"XYZ\"}");
    }

    @Test
    @DisplayName("契约：失败项为不同原因的混合时都返回、按处理顺序（到期日升序）；合约按规范化后返回；index 是请求中的下标")
    void mixedFailuresInProcessingOrder() {
        scenarioS();
        // request order: S5 (11-27, not yet expirable), valid IXQZ, unknown XYZ 11-20 50 P (lower-case, "50.000")
        JsonNode f = rejectedBatch(batch(xyz40(1), ixqz9(1), item("xyz", "2026-11-20", "50.000", "PUT", 1)), 2);
        assertThat(failureLines(f)).containsExactly(
                "2 XYZ 2026-11-20 50 PUT | XYZ 2026-11-20 50 P | NO_POSITION_TO_EXPIRE | 该合约（XYZ 2026-11-20 50 P）没有持仓，无法作废",
                "0 XYZ 2026-11-27 40 PUT | " + XYZ40 + " | EXPIRY_TOO_EARLY | 到期日（2026-11-27）美东 17:30 起才能作废");
    }

    @Test
    @DisplayName("契约：处理顺序 = 到期日 → 标的 → 行权价数值（9 在 10 前）→ Call 在 Put 前；与请求顺序无关")
    void processingOrderTieBreakers() {
        accountId = String.valueOf(newAccount("排序账户"));
        deposit("10000.00", "2026-11-02T10:00");
        open("BTO", 1, "QRTV", "2026-11-20", "10", "PUT", "5.00", "2026-11-03T10:00");
        open("BTO", 1, "QRTV", "2026-11-20", "9", "PUT", "6.00", "2026-11-03T10:01");
        open("BTO", 1, "QRTV", "2026-11-20", "10", "CALL", "7.00", "2026-11-03T10:02");
        open("BTO", 1, "NOPQ", "2026-11-20", "50", "PUT", "8.00", "2026-11-03T10:03");
        open("BTO", 1, "AAQX", "2026-11-13", "50", "PUT", "9.00", "2026-11-03T10:04");
        JsonNode b = okBatch(batch(
                item("QRTV", "2026-11-20", "10", "PUT", 1),
                item("QRTV", "2026-11-20", "10", "CALL", 1),
                item("NOPQ", "2026-11-20", "50", "PUT", 1),
                item("QRTV", "2026-11-20", "9", "PUT", 1),
                item("AAQX", "2026-11-13", "50", "PUT", 1)));
        List<String> labels = new ArrayList<>();
        b.get("expirations").forEach(t -> labels.add(t.get("contractLabel").asText()));
        assertThat(labels).containsExactly("AAQX 2026-11-13 50 P", "NOPQ 2026-11-20 50 P", "QRTV 2026-11-20 9 P",
                "QRTV 2026-11-20 10 C", "QRTV 2026-11-20 10 P");
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("-35.00");
        assertThat(b.get("frozenChangeTotal").asText()).isEqualTo("0.00");
        assertThat(b.get("expirations").get(0).get("tradedAt").asText()).isEqualTo("2026-11-13T21:00:00Z");
    }

    @Test
    @DisplayName("契约：顶层与每项多带的字段（note、tradedAtLocal、netAmount、multiplier、action）一律忽略")
    void ignoredFields() {
        scenarioS();
        ObjectNode i = xyz45(2);
        i.put("note", "不能填备注");
        i.put("tradedAtLocal", "2026-11-23T09:00");
        i.put("netAmount", "999.00");
        i.put("multiplier", 10);
        i.put("action", "BTC");
        ObjectNode body = batch(i);
        body.put("note", "批次备注");
        body.put("batchId", "B-1");
        JsonNode b = okBatch(body);
        JsonNode t = b.get("expirations").get(0);
        assertThat(List.of(t.get("contractLabel").asText(), t.get("tradedAt").asText(), t.get("netAmount").asText(),
                t.get("multiplier").asInt(), t.get("action").asText(), t.get("realizedPnl").asText()))
                .containsExactly(XYZ45, CUTOFF, "0.00", 100, "EXP", "240.00");
        assertThat(t.get("note").isNull()).isTrue();
    }

    // ================================================================== AC 6

    @Test
    @DisplayName("AC6 到期日 16:30 / 17:29:59：S1–S4 到期待确认、不可作废；批量 → 422，每项 EXPIRY_TOO_EARLY；17:45 → 成功，结果同 AC2")
    void ac6_expiryDayBeforeAndAfter1730() {
        clock.set(Instant.parse("2026-11-12T15:00:00Z"));
        scenarioS();
        clock.set(Instant.parse("2026-11-20T21:30:00Z")); // 16:30 NY
        for (String label : List.of(IXQZ9, QRTV30, XYZ42, XYZ45)) {
            JsonNode p = position(label);
            assertThat(p.get("expiryPending").asBoolean()).as(label).isTrue();
            assertThat(p.get("expirable").asBoolean()).as(label).isFalse();
        }
        assertThat(expirableLabels()).isEmpty();
        JsonNode f = rejectedBatch(batchS4(), 4);
        assertThat(failureLines(f)).containsExactly(
                "3 IXQZ 2026-11-20 9 CALL | " + IXQZ9 + " | EXPIRY_TOO_EARLY | " + TOO_EARLY,
                "2 QRTV 2026-11-20 30 CALL | " + QRTV30 + " | EXPIRY_TOO_EARLY | " + TOO_EARLY,
                "1 XYZ 2026-11-20 42 PUT | " + XYZ42 + " | EXPIRY_TOO_EARLY | " + TOO_EARLY,
                "0 XYZ 2026-11-20 45 PUT | " + XYZ45 + " | EXPIRY_TOO_EARLY | " + TOO_EARLY);

        clock.set(Instant.parse("2026-11-20T22:29:59Z")); // 17:29:59
        rejectedBatch(batchS4(), 4);

        clock.set(Instant.parse("2026-11-20T22:45:00Z")); // 17:45
        assertThat(expirableLabels()).containsExactly(IXQZ9, QRTV30, XYZ42, XYZ45);
        JsonNode b = okBatch(batchS4());
        assertThat(b.get("count").asInt()).isEqualTo(4);
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("285.00");
        assertThat(b.get("frozenChangeTotal").asText()).isEqualTo("-13200.00");
        for (JsonNode t : b.get("expirations")) {
            assertThat(t.get("tradedAt").asText()).isEqualTo(CUTOFF);
            assertThat(t.get("createdAt").asText()).isEqualTo("2026-11-20T22:45:00Z");
        }
        assertSummary("50355.00", "4000.00", "46355.00");
    }

    // ================================================================== AC 7

    @Test
    @DisplayName("AC7 11-30 不同到期日一起作废：5 个、+355.00、−17,200.00；4 条记在 11-20 16:00、XYZ 40 P 记在 11-27 16:00 且排最后；盈亏汇总都归 2026-11")
    void ac7_differentExpirations() {
        scenarioS();
        clock.set(Instant.parse("2026-11-30T15:00:00Z"));
        assertThat(expirableLabels()).containsExactly(IXQZ9, QRTV30, XYZ42, XYZ45, XYZ40);
        JsonNode b = okBatch(batch(xyz40(1), xyz45(2), xyz42(1), qrtv30(1), ixqz9(1)));
        assertThat(b.get("count").asInt()).isEqualTo(5);
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("355.00");
        assertThat(b.get("frozenChangeTotal").asText()).isEqualTo("-17200.00");
        assertThat(expLines(b)).containsExactly(
                IXQZ9 + " 1 25.00 0.00 " + CUTOFF + " SHORT",
                QRTV30 + " 1 -60.00 0.00 " + CUTOFF + " LONG",
                XYZ42 + " 1 80.00 -4200.00 " + CUTOFF + " SHORT",
                XYZ45 + " 2 240.00 -9000.00 " + CUTOFF + " SHORT",
                XYZ40 + " 1 70.00 -4000.00 2026-11-27T21:00:00Z SHORT");
        assertThat(b.get("expirations").get(4).get("tradedAtLocal").asText()).isEqualTo("2026-11-27T16:00:00");
        assertSummary("50355.00", "0.00", "50355.00");
        assertThat(positionLabels()).isEmpty();
        JsonNode p = pnl();
        List<String> byMonth = new ArrayList<>();
        p.get("byMonth").forEach(s -> byMonth.add(s.get("month").asText() + " " + s.get("closedCount").asInt()
                + " " + s.get("realizedPnl").asText()));
        assertThat(byMonth).containsExactly("2026-11 5 355.00");
    }

    // ================================================================== AC 8

    @Test
    @DisplayName("AC8 逐条删除：删除 XYZ 42 P 的作废 → 写审计（DELETE，同 FEAT-012）、冻结恢复 4,200.00；其余 3 条不变；XYZ 42 P 重新可作废；没有批量审计")
    void ac8_deleteOneOfBatch() {
        scenarioS();
        JsonNode b = okBatch(batchS4());
        assertThat(audit().get("totalItems").asInt()).isZero();
        long id42 = expOf(b, XYZ42).get("id").asLong();
        Resp d = exchange(HttpMethod.DELETE, TRADES + "/" + id42, null, MediaType.APPLICATION_JSON);
        assertThat(d.status()).as("DELETE -> %s", d.body()).isEqualTo(204);
        assertFrozenConsistent();
        assertSummary("50355.00", "8200.00", "42155.00");
        for (String label : List.of(IXQZ9, QRTV30, XYZ45)) {
            JsonNode t = expOf(b, label);
            assertThat(getTrade(t.get("id").asLong())).as("%s unchanged", label).isEqualTo(t);
        }
        assertThat(tradeList("action=EXP").get("totalItems").asInt()).isEqualTo(3);
        assertThat(expirableLabels()).containsExactly(XYZ42);
        assertThat(position(XYZ42).get("expiryContracts").asInt()).isEqualTo(1);

        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).isEqualTo(1);
        JsonNode rec = a.get("items").get(0);
        assertThat(List.of(rec.get("operation").asText(), rec.get("objectType").asText(),
                rec.get("objectId").asLong(), rec.get("summary").asText()))
                .containsExactly("DELETE", "OPTION_TRADE", id42, "到期作废 XYZ 2026-11-20 42 P 1 张");
        assertThat(rec.get("before").get("frozenChange").asText()).isEqualTo("-4200.00");

        // it can be expired again in a new batch of 1
        JsonNode again = okBatch(batch(xyz42(1)));
        assertThat(again.get("realizedPnlTotal").asText()).isEqualTo("80.00");
        assertThat(again.get("expirations").get(0).get("id").asLong()).isGreaterThan(id42);
        assertSummary("50355.00", "4000.00", "46355.00");
    }

    // ================================================================== AC 9

    @Test
    @DisplayName("AC9 按账户隔离：默认账户批量作废不影响小账户（XYZ 45 P 仍在、冻结 4,500.00）；默认账户再请求 XYZ 45 P（1 张）→ NO_POSITION_TO_EXPIRE；小账户可单独批量作废")
    void ac9_accounts() {
        long small = newAccount("小账户");
        accountId = String.valueOf(small);
        deposit("5000.00", "2026-11-02T10:00");
        open("STO", 1, "XYZ", "2026-11-20", "45", "PUT", "100.00", "2026-11-04T10:00");
        assertSummary("5100.00", "4500.00", "600.00");

        accountId = "1";
        scenarioS();
        okBatch(batchS4());
        assertSummary("50355.00", "4000.00", "46355.00");

        accountId = String.valueOf(small);
        assertSummary("5100.00", "4500.00", "600.00");
        assertThat(expirableLabels()).containsExactly(XYZ45);
        assertThat(position(XYZ45).get("expiryContracts").asInt()).isEqualTo(1);
        assertThat(tradeList("action=EXP").get("totalItems").asInt()).isZero();

        accountId = "1";
        JsonNode f = rejectedBatch(batch(xyz45(1)), 1);
        assertThat(failureLines(f)).containsExactly(
                "0 XYZ 2026-11-20 45 PUT | " + XYZ45 + " | NO_POSITION_TO_EXPIRE | 该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废");

        // contracts of the default account do not exist in the small account
        accountId = String.valueOf(small);
        JsonNode f2 = rejectedBatch(batch(xyz45(1), qrtv30(1)), 1);
        assertThat(failureLines(f2)).containsExactly(
                "1 QRTV 2026-11-20 30 CALL | " + QRTV30 + " | NO_POSITION_TO_EXPIRE | 该合约（QRTV 2026-11-20 30 C）没有持仓，无法作废");
        JsonNode b = okBatch(batch(xyz45(1)));
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("100.00");
        assertThat(b.get("frozenChangeTotal").asText()).isEqualTo("-4500.00");
        assertSummary("5100.00", "0.00", "5100.00");
        accountId = "1";
        assertSummary("50355.00", "4000.00", "46355.00");
        assertThat(tradeList("action=EXP").get("totalItems").asInt()).isEqualTo(4);
    }

    // ================================================================== edge cases

    @Test
    @DisplayName("边界：已单独作废的持仓再出现在批量中 → NO_POSITION_TO_EXPIRE，整批不保存；补录较晚批次后可再次批量作废（Q65）")
    void alreadyExpiredSinglyThenBackfill() {
        scenarioS();
        Resp single = postRaw(EXPIRATIONS, xyz42(1).toString(), MediaType.APPLICATION_JSON);
        assertThat(single.status()).isEqualTo(201);
        JsonNode f = rejectedBatch(batchS4(), 1);
        assertThat(failureLines(f)).containsExactly(
                "1 XYZ 2026-11-20 42 PUT | " + XYZ42 + " | NO_POSITION_TO_EXPIRE | 该合约（XYZ 2026-11-20 42 P）没有持仓，无法作废");
        open("STO", 1, "XYZ", "2026-11-20", "42", "PUT", "55.00", "2026-11-19T10:00");
        JsonNode b = okBatch(batchS4());
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("260.00");
        assertThat(expOf(b, XYZ42).get("realizedPnl").asText()).isEqualTo("55.00");
        assertSummary("50410.00", "4000.00", "46410.00");
    }

    @Test
    @DisplayName("边界：假日到期（2026-11-26 感恩节）与周五到期一起作废，分别记在各自到期日 16:00；multiplier 1 的合约口径不变")
    void holidayAndMultiplier() {
        clock.set(Instant.parse("2026-11-30T15:00:00Z"));
        accountId = String.valueOf(newAccount("假日账户"));
        deposit("5000.00", "2026-11-16T10:00");
        // FEAT-009 (Q126): a holiday expiration only exists as data stored before FEAT-009
        moveExpiration(open("STO", 1, "XYZ", "2026-11-25", "30", "PUT", "40.00", "2026-11-24T10:00"), "2026-11-26");
        okTrade(trade("STO", 2, "QFZ", "2026-11-20", "12.345", "PUT", "1.00", "2026-11-17T10:00", 1));
        JsonNode b = okBatch(batch(item("XYZ", "2026-11-26", "30", "PUT", 1),
                item("QFZ", "2026-11-20", "12.345", "PUT", 2)));
        assertThat(expLines(b)).containsExactly(
                "QFZ 2026-11-20 12.345 P 2 1.00 -24.69 " + CUTOFF + " SHORT",
                "XYZ 2026-11-26 30 P 1 40.00 -3000.00 2026-11-26T21:00:00Z SHORT");
        assertThat(b.get("expirations").get(0).get("multiplier").asInt()).isEqualTo(1);
        assertSummary("5041.00", "0.00", "5041.00");
    }

    @Test
    @DisplayName("契约：只有 BATCH_EXPIRATION_REJECTED 带 failures；缺 X-Account-Id 与其它接口一致被拒、不保存")
    void failuresOnlyOnBatchRejection() {
        scenarioS();
        Full before = full();
        Resp v = postBatch(batch());
        assertErrorShape(v, 400, "VALIDATION_ERROR", BATCH);
        assertThat(v.body().has("failures")).isFalse();
        accountId = null;
        Resp noAcc = postBatch(batchS4());
        assertThat(noAcc.status()).isBetween(400, 499);
        assertThat(noAcc.body().has("failures")).isFalse();
        accountId = "1";
        assertThat(full()).isEqualTo(before);
    }
}
