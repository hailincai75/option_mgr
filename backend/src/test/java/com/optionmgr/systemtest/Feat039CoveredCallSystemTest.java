package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-039 covered call 股票覆盖检查与锁定, end-to-end over HTTP (whole application, in-memory H2, profile "test"),
 * only through the REST API of docs/api/FEAT-039.md, except for scenario L: systemtests do not run with the
 * {@code e2e} profile, so the pre-launch (LEGACY) batch is produced by {@link #okLegacyTrade} (normal POST covered by a
 * temporary 转入, then coverage_status = 'LEGACY' via JDBC). "Now" = 2026-11-13 10:00 New York unless a test moves the
 * clock. Fictional tickers only.
 *
 * <p>Every rejection must be 422 COVERAGE_INSUFFICIENT with {@code details = []} and leave cash, ledger, option
 * trades / positions, stock trades / positions, audit log and every coverage status untouched.
 */
class Feat039CoveredCallSystemTest extends OptionTradeSystemTestBase {

    static final Instant FRI_1113_1000 = Instant.parse("2026-11-13T15:00:00Z");
    static final Instant MON_1123_1000 = Instant.parse("2026-11-23T15:00:00Z");
    static final Instant MON_1221_1100 = Instant.parse("2026-12-21T16:00:00Z");

    static final String CODE = "COVERAGE_INSUFFICIENT";
    static final String CLOSES = "/api/option-trades/closes";
    static final String EXPIRATIONS = "/api/option-trades/expirations";
    static final String ASSIGNMENTS = "/api/option-trades/assignments";
    static final String STOCKS = "/api/stock-trades";
    static final String STOCK_POS = "/api/stock-positions";
    static final String AUDIT = "/api/audit-log";

    static final String C10 = "IXQZ 2026-12-18 10 C";
    static final String C11 = "IXQZ 2026-12-18 11 C";
    static final String C12 = "IXQZ 2026-12-18 12 C";
    static final String C13 = "IXQZ 2026-12-18 13 C";
    static final String P40 = "IXQZ 2026-12-18 40 P";

    static final String E1_200_100 = "需要 200 股 IXQZ，可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）";
    static final String E1_100_0_IXQZ = "需要 100 股 IXQZ，可用 0 股（持有 0 股）";
    static final String E2_200_200 = "卖出 100 股超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call";

    long s1;
    long c1;
    long c2;
    long l1;

    @Override
    Instant now() {
        return FRI_1113_1000;
    }

    // ================================================================== request helpers

    long sto(int contracts, String symbol, String exp, String strike, String net, String at, int multiplier) {
        return okTrade(trade("STO", contracts, symbol, exp, strike, "CALL", net, at, multiplier)).get("id").asLong();
    }

    /** IXQZ 2026-12-18 &lt;strike&gt; C, multiplier 100. */
    ObjectNode ixqzCall(int contracts, String strike, String net, String at) {
        return trade("STO", contracts, "IXQZ", "2026-12-18", strike, "CALL", net, at, 100);
    }

    ObjectNode stockBody(String type, String symbol, int shares, String amount, String time) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        if ("TRANSFER_IN".equals(type)) {
            n.put("tradeDate", time);
        } else {
            n.put("tradedAtLocal", time);
        }
        n.putNull("note");
        return n;
    }

    Resp postStock(ObjectNode body) {
        return postRaw(STOCKS, body.toString(), MediaType.APPLICATION_JSON);
    }

    long okStock(ObjectNode body) {
        Resp r = postStock(body);
        assertThat(r.status()).as("POST stock %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** PUT body of a stock record; {@code time} is tradeDate for TRANSFER_IN, tradedAtLocal otherwise. */
    ObjectNode stockEdit(int shares, String amount, String timeField, String time) {
        ObjectNode n = json.createObjectNode();
        n.put("shares", shares);
        n.put("amount", amount);
        n.put(timeField, time);
        n.putNull("note");
        return n;
    }

    Resp putStock(long id, ObjectNode body) {
        return exchange(HttpMethod.PUT, STOCKS + "/" + id, body.toString(), MediaType.APPLICATION_JSON);
    }

    Resp deleteStock(long id) {
        return exchange(HttpMethod.DELETE, STOCKS + "/" + id, null, MediaType.APPLICATION_JSON);
    }

    ObjectNode edit(int contracts, String net, String at, Integer multiplier, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        if (note != null) {
            n.put("note", note);
        }
        return n;
    }

    Resp putTrade(long id, ObjectNode body) {
        return exchange(HttpMethod.PUT, TRADES + "/" + id, body.toString(), MediaType.APPLICATION_JSON);
    }

    Resp deleteTrade(long id) {
        return exchange(HttpMethod.DELETE, TRADES + "/" + id, null, MediaType.APPLICATION_JSON);
    }

    JsonNode okEdit(long id, ObjectNode body) {
        Resp r = putTrade(id, body);
        assertThat(r.status()).as("PUT %d %s -> %s", id, body, r.body()).isEqualTo(200);
        return r.body();
    }

    ObjectNode closeBody(String strike, int contracts, String net, String at) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "IXQZ");
        n.put("expirationDate", "2026-12-18");
        n.put("strike", strike);
        n.put("optionType", "CALL");
        n.put("action", "BTC");
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        n.putNull("note");
        return n;
    }

    long okBtc(String strike, int contracts, String net, String at) {
        Resp r = postRaw(CLOSES, closeBody(strike, contracts, net, at).toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("BTC -> %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    ObjectNode expBody(String symbol, String exp, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        return n;
    }

    long okExpire(String symbol, String exp, String strike, String type, int contracts) {
        Resp r = postRaw(EXPIRATIONS, expBody(symbol, exp, strike, type, contracts).toString(),
                MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("expire -> %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    ObjectNode asgBody(String symbol, String exp, String strike, String type, int contracts, String date) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        n.put("assignmentDate", date);
        n.putNull("note");
        return n;
    }

    Resp postAssign(ObjectNode body) {
        return postRaw(ASSIGNMENTS, body.toString(), MediaType.APPLICATION_JSON);
    }

    long okAssign(ObjectNode body) {
        Resp r = postAssign(body);
        assertThat(r.status()).as("assign %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
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

    // ================================================================== snapshots / rejections

    record Full(State state, JsonNode positions, JsonNode ledger, JsonNode audit, JsonNode trades, JsonNode stocks,
                JsonNode stockPositions, List<String> coverage) {
    }

    Full full() {
        return new Full(state(), positionsBody(), ledger(1, 100), get(AUDIT + "?size=100").body(),
                get(TRADES + "?size=100").body(), get(STOCKS + "?size=100").body(), get(STOCK_POS).body(),
                jdbc.queryForList("SELECT id || ':' || COALESCE(coverage_status, '-') FROM option_trade ORDER BY id",
                        String.class));
    }

    /** Must be 422 COVERAGE_INSUFFICIENT, details [], nothing changed (no audit either); returns the message. */
    String rejected(Resp r, Full before, String path) {
        assertErrorShape(r, 422, CODE, path);
        assertThat(r.body().get("details")).isEmpty();
        assertThat(full()).as("nothing changed after rejected write").isEqualTo(before);
        return r.body().get("message").asText();
    }

    String rejectedTrade(ObjectNode body) {
        Full before = full();
        return rejected(postTrade(body), before, TRADES);
    }

    String rejectedEdit(long id, ObjectNode body) {
        Full before = full();
        return rejected(putTrade(id, body), before, TRADES + "/" + id);
    }

    String rejectedDelete(long id) {
        Full before = full();
        return rejected(deleteTrade(id), before, TRADES + "/" + id);
    }

    String rejectedStockPost(ObjectNode body) {
        Full before = full();
        return rejected(postStock(body), before, STOCKS);
    }

    String rejectedStockPut(long id, ObjectNode body) {
        Full before = full();
        return rejected(putStock(id, body), before, STOCKS + "/" + id);
    }

    String rejectedStockDelete(long id) {
        Full before = full();
        return rejected(deleteStock(id), before, STOCKS + "/" + id);
    }

    String rejectedAssign(ObjectNode body) {
        Full before = full();
        return rejected(postAssign(body), before, ASSIGNMENTS);
    }

    // ================================================================== reads

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
        throw new AssertionError("no position " + label);
    }

    /** "IXQZ shares/covered/available" of the stock position, or null when the symbol has no shares. */
    String stock(String symbol) {
        Resp r = get(STOCK_POS);
        assertThat(r.status()).isEqualTo(200);
        for (JsonNode p : r.body().get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                assertThat(p.get("coveredShares").isIntegralNumber()).as("coveredShares is a number").isTrue();
                assertThat(p.get("availableShares").isIntegralNumber()).as("availableShares is a number").isTrue();
                assertThat(p.get("availableShares").asInt())
                        .isEqualTo(p.get("shares").asInt() - p.get("coveredShares").asInt());
                return symbol + " " + p.get("shares").asInt() + "/" + p.get("coveredShares").asInt() + "/"
                        + p.get("availableShares").asInt();
            }
        }
        return null;
    }

    /** Coverage fields of a short-call row: "covered uncoveredContracts uncoveredShares available". */
    String coverage(JsonNode p) {
        for (String f : List.of("coveredShares", "uncoveredContracts", "uncoveredShares", "availableShares")) {
            assertThat(p.get(f)).as("%s present on %s", f, p.get("contractLabel")).isNotNull();
            assertThat(p.get(f).isIntegralNumber()).as("%s is a number on %s", f, p.get("contractLabel")).isTrue();
        }
        assertThat(p.get("frozenCash").asText()).as("short call frozen stays 0.00").isEqualTo("0.00");
        return p.get("coveredShares").asInt() + " " + p.get("uncoveredContracts").asInt() + " "
                + p.get("uncoveredShares").asInt() + " " + p.get("availableShares").asInt();
    }

    /** A CHECKED-only row: no shortfall data, every lot CHECKED with requiredShares and null shortfall. */
    void assertCheckedRow(JsonNode p, int coveredShares, int available) {
        assertThat(coverage(p)).isEqualTo(coveredShares + " 0 0 " + available);
        assertThat(p.get("uncoveredShortfallAt").isNull()).isTrue();
        assertThat(p.get("uncoveredShortfallAtLocal").isNull()).isTrue();
        assertThat(p.get("uncoveredShortfallHeldShares").isNull()).isTrue();
        for (JsonNode l : p.get("lots")) {
            assertThat(l.get("coverage").asText()).isEqualTo("CHECKED");
            assertThat(l.get("requiredShares").asInt())
                    .isEqualTo(l.get("remainingContracts").asInt() * p.get("multiplier").asInt());
            assertThat(l.get("shortfallAt").isNull()).isTrue();
            assertThat(l.get("shortfallAtLocal").isNull()).isTrue();
            assertThat(l.get("shortfallHeldShares").isNull()).isTrue();
        }
    }

    /** Non-short-call rows carry null coverage fields. */
    void assertNoCoverageFields(JsonNode p) {
        for (String f : List.of("coveredShares", "uncoveredContracts", "uncoveredShares", "availableShares",
                "uncoveredShortfallAt", "uncoveredShortfallAtLocal", "uncoveredShortfallHeldShares")) {
            assertThat(p.get(f)).as("%s present on %s", f, p.get("contractLabel")).isNotNull();
            assertThat(p.get(f).isNull()).as("%s null on %s", f, p.get("contractLabel")).isTrue();
        }
        for (JsonNode l : p.get("lots")) {
            for (String f : List.of("coverage", "requiredShares", "shortfallAt", "shortfallAtLocal",
                    "shortfallHeldShares")) {
                assertThat(l.get(f).isNull()).as("lot %s null on %s", f, p.get("contractLabel")).isTrue();
            }
        }
    }

    JsonNode lastAudit() {
        JsonNode items = get(AUDIT + "?size=100").body().get("items");
        assertThat(items.size()).isPositive();
        return items.get(0);
    }

    int auditCount() {
        return get(AUDIT + "?size=100").body().get("totalItems").asInt();
    }

    // ================================================================== scenarios

    /** Scenario D (current account): deposit, S1 转入 200, C1. C2 is added by the caller (AC1 rejects first). */
    void scenarioDUpToC1() {
        deposit("10000.00", "2026-11-02T10:00");
        s1 = okStock(stockBody("TRANSFER_IN", "IXQZ", 200, "1600.00", "2026-11-02"));
        c1 = sto(1, "IXQZ", "2026-12-18", "10", "50.00", "2026-11-03T10:00", 100);
    }

    void scenarioD() {
        scenarioDUpToC1();
        c2 = sto(1, "IXQZ", "2026-12-18", "11", "30.00", "2026-11-04T10:00", 100);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 200/200/0");
        assertSummary("10080.00", "0.00", "10080.00");
    }

    /** Scenario L (current account): deposit 2,000.00; L1 = pre-launch STO 2 IXQZ 10 C 90.00; no stock. */
    void scenarioL() {
        deposit("2000.00", "2026-11-02T10:00");
        l1 = okLegacyTrade(ixqzCall(2, "10", "90.00", "2026-11-03T10:00")).get("id").asLong();
        assertThat(coverageStatus(l1)).isEqualTo("LEGACY");
        assertThat(stock("IXQZ")).isNull();
        assertSummary("2090.00", "0.00", "2090.00");
    }

    /** L1's row while still LEGACY (whole batch uncovered). */
    void assertL1Legacy(int available, String shortfallLocal, int heldShares) {
        assertThat(coverageStatus(l1)).isEqualTo("LEGACY");
        JsonNode p = position(C10);
        assertThat(coverage(p)).isEqualTo("0 2 200 " + available);
        assertThat(p.get("uncoveredShortfallAtLocal").asText()).isEqualTo(shortfallLocal);
        assertThat(p.get("uncoveredShortfallHeldShares").asInt()).isEqualTo(heldShares);
        assertThat(p.get("uncoveredShortfallAt").asText()).endsWith("Z");
        JsonNode lot = p.get("lots").get(0);
        assertThat(lot.get("tradeId").asLong()).isEqualTo(l1);
        assertThat(lot.get("coverage").asText()).isEqualTo("LEGACY");
        assertThat(lot.get("requiredShares").asInt()).isEqualTo(200);
        assertThat(lot.get("shortfallAtLocal").asText()).isEqualTo(shortfallLocal);
        assertThat(lot.get("shortfallHeldShares").asInt()).isEqualTo(heldShares);
    }

    // ================================================================== AC 1

    @Test
    @DisplayName("AC1 卖出 call 检查（Q77）：STO 2 张 11 C → E1「需要 200 股…可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）」；C2 1 张成功；C1/C2 已覆盖 100 股；股票 200/200/0")
    void ac1_shortCallCheck() {
        scenarioDUpToC1();
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 200/100/100");
        assertThat(rejectedTrade(ixqzCall(2, "11", "60.00", "2026-11-04T10:00"))).isEqualTo(E1_200_100);

        c2 = sto(1, "IXQZ", "2026-12-18", "11", "30.00", "2026-11-04T10:00", 100);
        assertThat(coverageStatus(c1)).isEqualTo("CHECKED");
        assertThat(coverageStatus(c2)).isEqualTo("CHECKED");
        assertCheckedRow(position(C10), 100, 0);
        assertCheckedRow(position(C11), 100, 0);
        assertThat(position(C10).get("underlyingShares").asInt()).isEqualTo(200);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 200/200/0");
        assertSummary("10080.00", "0.00", "10080.00");
        assertThat(positionsBody().get("frozenTotal").asText()).isEqualTo("0.00");
    }

    // ================================================================== AC 2

    @Test
    @DisplayName("AC2 锁定与 BTC 释放（Q78）：卖出 100 → E2；BTC C2 → 200/100/100；10:30 卖出仍 E2；12:00 成功 100/100/0；删 BTC / 改到 12:30 → E3 12:00 起 需要 200 持有 100")
    void ac2_lockAndBtcRelease() {
        scenarioD();
        assertThat(rejectedStockPost(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-05T10:00")))
                .isEqualTo(E2_200_200);

        long btc = okBtc("11", 1, "10.00", "2026-11-05T11:00");
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 200/100/100");

        assertThat(rejectedStockPost(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-05T10:30")))
                .as("C2 still open at 10:30").isEqualTo(E2_200_200);
        long sell = okStock(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-05T12:00"));
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/100/0");
        assertCheckedRow(position(C10), 100, 0);

        assertThat(rejectedDelete(btc))
                .isEqualTo("删除后 2026-11-05 12:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");
        assertThat(rejectedEdit(btc, edit(1, "10.00", "2026-11-05T12:30", null, null)))
                .isEqualTo("修改后 2026-11-05 12:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");

        // modifying the sale itself is an E2 check at its own time point
        assertThat(rejectedStockPut(sell, stockEdit(100, "1000.00", "tradedAtLocal", "2026-11-05T10:59")))
                .isEqualTo(E2_200_200);
        Resp ok = putStock(sell, stockEdit(100, "1001.00", "tradedAtLocal", "2026-11-05T12:00"));
        assertThat(ok.status()).as("only the amount: %s", ok.body()).isEqualTo(200);
    }

    // ================================================================== AC 3

    @Test
    @DisplayName("AC3 补录卖出 11-03 12:00 → 本笔时点通过 E2，E3「补录后 2026-11-04 10:00 起…需要 200 股，持有 100 股」")
    void ac3_backfillSale() {
        scenarioD();
        assertThat(rejectedStockPost(stockBody("SELL", "IXQZ", 100, "900.00", "2026-11-03T12:00")))
                .isEqualTo("补录后 2026-11-04 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");
    }

    @Test
    @DisplayName("AC3 S1 改 150 股 → E3「修改后 2026-11-04 10:00 起…持有 150 股」；删除 S1 → E3「删除后 2026-11-03 10:00 起…需要 100 股，持有 0 股」")
    void ac3_editDeleteTransfer() {
        scenarioD();
        assertThat(rejectedStockPut(s1, stockEdit(150, "1600.00", "tradeDate", "2026-11-02")))
                .isEqualTo("修改后 2026-11-04 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 150 股");
        assertThat(rejectedStockDelete(s1))
                .isEqualTo("删除后 2026-11-03 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
        assertThat(auditCount()).isZero();
    }

    @Test
    @DisplayName("AC3 C2 张数改 2 → E1（需要 200，可用 100）；C1 时间改 11-01 → E1「需要 100 股 IXQZ，可用 0 股（持有 0 股）」；C1 净额改 55.00 → 成功（不做 E1）")
    void ac3_editCalls() {
        scenarioD();
        assertThat(rejectedEdit(c2, edit(2, "30.00", "2026-11-04T10:00", 100, null))).isEqualTo(E1_200_100);
        assertThat(rejectedEdit(c1, edit(1, "50.00", "2026-11-01T10:00", 100, null))).isEqualTo(E1_100_0_IXQZ);

        JsonNode t = okEdit(c1, edit(1, "55.00", "2026-11-03T10:00", 100, null));
        assertThat(t.get("netAmount").asText()).isEqualTo("55.00");
        assertThat(coverageStatus(c1)).isEqualTo("CHECKED");
        assertThat(texts(lastAudit().get("changes"))).containsExactly("净额：50.00 → 55.00");
        assertThat(lastAudit().get("sideEffects")).isEmpty();
        assertSummary("10085.00", "0.00", "10085.00");
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 200/200/0");
    }

    @Test
    @DisplayName("AC3 multiplier 修改也做 E1：C2 multiplier 100 → 200 被拒（需要 200 股，可用 100 股）")
    void ac3_editMultiplier() {
        scenarioD();
        assertThat(rejectedEdit(c2, edit(1, "30.00", "2026-11-04T10:00", 200, null))).isEqualTo(E1_200_100);
    }

    // ================================================================== AC 4

    @Test
    @DisplayName("AC4 作废与 call 被指派：C2 被指派 → 100/100/0；12-18 15:00 卖出 → E2；作废 C1 → 100/0/100；12-21 10:00 卖出成功；删除 C1 作废 → E3 12-21 10:00 起 需要 100 持有 0")
    void ac4_expireAndCallAssignment() {
        scenarioD();
        clock.set(MON_1221_1100);
        // Q34: expired but not processed short calls still need their shares
        assertThat(rejectedStockPost(stockBody("SELL", "IXQZ", 100, "1050.00", "2026-12-21T10:00")))
                .isEqualTo(E2_200_200);

        okAssign(asgBody("IXQZ", "2026-12-18", "11", "CALL", 1, "2026-12-18"));
        assertThat(stock("IXQZ")).as("可用不变").isEqualTo("IXQZ 100/100/0");
        assertThat(rejectedStockPost(stockBody("SELL", "IXQZ", 100, "1050.00", "2026-12-18T15:00")))
                .as("before the 16:00 assignment").isEqualTo(E2_200_200);

        long exp = okExpire("IXQZ", "2026-12-18", "10", "CALL", 1);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/0/100");
        okStock(stockBody("SELL", "IXQZ", 100, "1050.00", "2026-12-21T10:00"));
        assertThat(stock("IXQZ")).isNull();

        assertThat(rejectedDelete(exp))
                .isEqualTo("删除后 2026-12-21 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    // ================================================================== AC 5

    @Test
    @DisplayName("AC5 put 被指派增加持股（wheel）：无股票 STO 45 C → E1；put 被指派后 11-20 15:00 仍拒、11-23 09:45 成功 100/100/0；删除 put 被指派 → E3 11-23 09:45 起")
    void ac5_wheel() {
        accountId = String.valueOf(newAccount("小账户"));
        deposit("5000.00", "2026-11-02T10:00");
        okTrade(trade("STO", 1, "QRTV", "2026-11-20", "40", "PUT", "80.00", "2026-11-03T10:00", 100));
        ObjectNode call = trade("STO", 1, "QRTV", "2026-12-18", "45", "CALL", "60.00", "2026-11-10T10:00", 100);
        assertThat(rejectedTrade(call)).isEqualTo("需要 100 股 QRTV，可用 0 股（持有 0 股）");

        clock.set(MON_1123_1000);
        long putAsg = okAssign(asgBody("QRTV", "2026-11-20", "40", "PUT", 1, "2026-11-20"));
        assertThat(stock("QRTV")).isEqualTo("QRTV 100/0/100");
        call.put("tradedAtLocal", "2026-11-20T15:00");
        assertThat(rejectedTrade(call)).as("before the 16:00 put assignment")
                .isEqualTo("需要 100 股 QRTV，可用 0 股（持有 0 股）");
        call.put("tradedAtLocal", "2026-11-23T09:45");
        long sc = okTrade(call).get("id").asLong();
        assertThat(coverageStatus(sc)).isEqualTo("CHECKED");
        assertThat(stock("QRTV")).isEqualTo("QRTV 100/100/0");

        assertThat(rejectedDelete(putAsg))
                .isEqualTo("删除后 2026-11-23 09:45 起 QRTV 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    // ================================================================== AC 6, 7

    @Test
    @DisplayName("AC6 旧批次不挡操作（Q79）：L1 未覆盖（需要 200 股，当前可用 0 股）；买入 100 → 100/0/100 仍未覆盖；STO 12 C 成功 100/100/0；改备注仍未覆盖；改张数 1 → E1")
    void ac6_legacyDoesNotBlock() {
        scenarioL();
        assertL1Legacy(0, "2026-11-03T10:00:00", 0);
        assertThat(position(C10).get("uncoveredShortfallAt").asText()).isEqualTo("2026-11-03T15:00:00Z");
        assertThat(position(C10).get("lots").get(0).get("shortfallAt").asText()).isEqualTo("2026-11-03T15:00:00Z");

        okStock(stockBody("BUY", "IXQZ", 100, "800.00", "2026-11-10T10:00"));
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/0/100");
        assertL1Legacy(100, "2026-11-03T10:00:00", 0); // 2 contracts as a whole: 100 shares do not cover half

        long c12 = sto(1, "IXQZ", "2026-12-18", "12", "20.00", "2026-11-10T11:00", 100);
        assertThat(coverageStatus(c12)).isEqualTo("CHECKED");
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/100/0");
        assertCheckedRow(position(C12), 100, 0);
        assertL1Legacy(0, "2026-11-03T10:00:00", 0);

        int audits = auditCount();
        JsonNode noted = okEdit(l1, edit(2, "90.00", "2026-11-03T10:00", 100, "旧的 call"));
        assertThat(noted.get("note").asText()).isEqualTo("旧的 call");
        assertL1Legacy(0, "2026-11-03T10:00:00", 0);
        assertThat(auditCount()).isEqualTo(audits + 1);
        assertThat(lastAudit().get("sideEffects")).isEmpty();

        assertThat(rejectedEdit(l1, edit(1, "90.00", "2026-11-03T10:00", 100, "旧的 call")))
                .isEqualTo(E1_100_0_IXQZ);
        assertThat(coverageStatus(l1)).isEqualTo("LEGACY");
    }

    @Test
    @DisplayName("AC7 自动纳入（Q101）：转入 11-04 → 仍未覆盖（2026-11-03 10:00 时持有 0 股）300/100/200；删除后改录 11-01 → 已覆盖 200 股 300/300/0；卖出 → E2；删除 11-01 转入 → E3")
    void ac7_autoInclusion() {
        ac6Setup();
        long t1104 = okStock(stockBody("TRANSFER_IN", "IXQZ", 200, "1500.00", "2026-11-04"));
        assertL1Legacy(200, "2026-11-03T10:00:00", 0);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 300/100/200");

        Resp del = deleteStock(t1104);
        assertThat(del.status()).as("delete 11-04 transfer -> %s", del.body()).isEqualTo(204);
        assertThat(lastAudit().get("sideEffects")).as("nothing included by that delete").isEmpty();
        int audits = auditCount();

        long t1101 = okStock(stockBody("TRANSFER_IN", "IXQZ", 200, "1500.00", "2026-11-01"));
        assertThat(coverageStatus(l1)).isEqualTo("CHECKED");
        assertCheckedRow(position(C10), 200, 0);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 300/300/0");
        assertThat(auditCount()).as("inclusion by a new record writes no audit (Q42)").isEqualTo(audits);

        assertThat(rejectedStockPost(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-12T10:00")))
                .isEqualTo("卖出 100 股超过可用股数 0 股（持有 300 股，其中 300 股被 call 覆盖），请先平掉对应的 call");
        assertThat(rejectedStockDelete(t1101))
                .isEqualTo("删除后 2026-11-03 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 0 股");
        assertThat(coverageStatus(l1)).as("LEGACY → CHECKED is one-way").isEqualTo("CHECKED");
    }

    /** AC6's state: scenario L + BUY 100 (11-10 10:00) + CHECKED STO 1 12 C (11-10 11:00) + L1 note. */
    void ac6Setup() {
        scenarioL();
        okStock(stockBody("BUY", "IXQZ", 100, "800.00", "2026-11-10T10:00"));
        sto(1, "IXQZ", "2026-12-18", "12", "20.00", "2026-11-10T11:00", 100);
        okEdit(l1, edit(2, "90.00", "2026-11-03T10:00", 100, "旧的 call"));
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/100/0");
    }

    @Test
    @DisplayName("规则 5 审计：修改股票引起的纳入写进该审计 sideEffects（field coverage，未覆盖 → 已覆盖，STO #id 纳入覆盖检查）")
    void inclusionByStockEditIsAudited() {
        scenarioL();
        long t = okStock(stockBody("TRANSFER_IN", "IXQZ", 100, "750.00", "2026-11-01"));
        assertL1Legacy(100, "2026-11-03T10:00:00", 100);

        Resp r = putStock(t, stockEdit(200, "1500.00", "tradeDate", "2026-11-01"));
        assertThat(r.status()).as("edit transfer -> %s", r.body()).isEqualTo(200);
        assertThat(coverageStatus(l1)).isEqualTo("CHECKED");
        assertCheckedRow(position(C10), 200, 0);
        JsonNode a = lastAudit();
        assertThat(a.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(a.get("objectType").asText()).isEqualTo("STOCK_TRADE");
        assertThat(a.get("sideEffects")).hasSize(1);
        JsonNode se = a.get("sideEffects").get(0);
        assertThat(List.of(se.get("tradeId").asLong(), se.get("action").asText(), se.get("contractLabel").asText(),
                se.get("field").asText(), se.get("before").asText(), se.get("after").asText(), se.get("text").asText()))
                .containsExactly(l1, "STO", C10, "coverage", "未覆盖", "已覆盖", "STO #" + l1 + " 纳入覆盖检查");
        assertThat(getTrade(l1).get("hasAuditLog").asBoolean()).as("hasAuditLog of the included lot unchanged")
                .isFalse();
    }

    @Test
    @DisplayName("规则 5：修改旧批次张数通过 E1 → 成为受检批次（单向），审计 sideEffects 含自身的纳入")
    void legacyEditBecomesChecked() {
        scenarioL();
        okStock(stockBody("TRANSFER_IN", "IXQZ", 100, "750.00", "2026-11-01"));
        assertL1Legacy(100, "2026-11-03T10:00:00", 100);

        JsonNode t = okEdit(l1, edit(1, "45.00", "2026-11-03T10:00", 100, null));
        assertThat(t.get("contracts").asInt()).isEqualTo(1);
        assertThat(coverageStatus(l1)).isEqualTo("CHECKED");
        assertCheckedRow(position(C10), 100, 0);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/100/0");
        assertThat(texts(lastAudit().get("sideEffects"))).containsExactly("STO #" + l1 + " 纳入覆盖检查");
        assertThat(lastAudit().get("sideEffects").get(0).get("field").asText()).isEqualTo("coverage");
    }

    @Test
    @DisplayName("一行同时有受检和旧批次：coveredShares 100、uncoveredContracts 2（标签「已覆盖 100 股」「未覆盖 2 张」）")
    void mixedRow() {
        scenarioL();
        okStock(stockBody("BUY", "IXQZ", 100, "800.00", "2026-11-10T10:00"));
        long add = sto(1, "IXQZ", "2026-12-18", "10", "20.00", "2026-11-10T11:00", 100);
        assertThat(coverageStatus(add)).isEqualTo("CHECKED");
        JsonNode p = position(C10);
        assertThat(p.get("contracts").asInt()).isEqualTo(3);
        assertThat(coverage(p)).isEqualTo("100 2 200 0");
        assertThat(p.get("uncoveredShortfallAtLocal").asText()).isEqualTo("2026-11-03T10:00:00");
        assertThat(p.get("uncoveredShortfallHeldShares").asInt()).isZero();
        List<String> lots = new ArrayList<>();
        p.get("lots").forEach(l -> lots.add(l.get("tradeId").asLong() + " " + l.get("coverage").asText() + " "
                + l.get("requiredShares").asInt()));
        assertThat(lots).containsExactly(l1 + " LEGACY 200", add + " CHECKED 100");
    }

    @Test
    @DisplayName("边界：旧批次被 call 指派交出股票使受检批次不足 → E3「被指派后 2026-11-20 16:00 起…需要 100 股，持有 0 股」（Q75 已通过）")
    void legacyCallAssignmentBreaksCheckedLot() {
        clock.set(MON_1123_1000);
        deposit("1000.00", "2026-11-02T10:00");
        long legacy = okLegacyTrade(trade("STO", 1, "IXQZ", "2026-11-20", "9", "CALL", "20.00", "2026-11-03T10:00",
                100)).get("id").asLong();
        okStock(stockBody("TRANSFER_IN", "IXQZ", 100, "800.00", "2026-11-04"));
        assertThat(coverageStatus(legacy)).as("0 shares at its 11-03 open").isEqualTo("LEGACY");
        long checked = sto(1, "IXQZ", "2026-12-18", "10", "30.00", "2026-11-05T10:00", 100);
        assertThat(coverageStatus(checked)).isEqualTo("CHECKED");
        assertThat(rejectedAssign(asgBody("IXQZ", "2026-11-20", "9", "CALL", 1, "2026-11-20")))
                .isEqualTo("被指派后 2026-11-20 16:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    // ================================================================== AC 8

    @Test
    @DisplayName("AC8 标签数据（Q62）：加 STO 40 P → 40 P 冻结 4,000.00、覆盖字段 null；C1/C2 已覆盖 100 股；冻结合计 4,000.00")
    void ac8_putAndCalls() {
        scenarioD();
        okTrade(trade("STO", 1, "IXQZ", "2026-12-18", "40", "PUT", "70.00", "2026-11-04T11:00", 100));
        JsonNode p40 = position(P40);
        assertThat(p40.get("frozenCash").asText()).isEqualTo("4000.00");
        assertNoCoverageFields(p40);
        assertCheckedRow(position(C10), 100, 0);
        assertCheckedRow(position(C11), 100, 0);
        assertThat(positionsBody().get("frozenTotal").asText()).isEqualTo("4000.00");
        assertSummary("10150.00", "4000.00", "6150.00");
    }

    @Test
    @DisplayName("多头 call 行的覆盖字段为 null，且不需要股票")
    void longCallHasNoCoverage() {
        deposit("1000.00", "2026-11-02T10:00");
        okTrade(trade("BTO", 1, "IXQZ", "2026-12-18", "10", "CALL", "50.00", "2026-11-03T10:00", 100));
        assertNoCoverageFields(position(C10));
        assertThat(stock("IXQZ")).isNull();
    }

    // ================================================================== AC 9

    @Test
    @DisplayName("AC9 multiplier 10 与账户隔离：买入 10 股 → 210/200/10；STO 13 C ×10 → 已覆盖 10 股、210/210/0；再录 → E1 需要 10 股；小账户 → E1 需要 100 股；默认账户不变")
    void ac9_multiplierAndAccounts() {
        scenarioD();
        okStock(stockBody("BUY", "IXQZ", 10, "85.00", "2026-11-04T11:00"));
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 210/200/10");
        ObjectNode m10 = trade("STO", 1, "IXQZ", "2026-12-18", "13", "CALL", "5.00", "2026-11-04T12:00", 10);
        okTrade(m10);
        assertCheckedRow(position(C13), 10, 0);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 210/210/0");
        assertThat(rejectedTrade(m10))
                .isEqualTo("需要 10 股 IXQZ，可用 0 股（持有 210 股，其中 210 股已覆盖其它 call）");

        Full defaultBefore = full();
        String main = accountId;
        accountId = String.valueOf(newAccount("小账户"));
        deposit("5000.00", "2026-11-02T10:00");
        assertThat(rejectedTrade(ixqzCall(1, "10", "50.00", "2026-11-04T10:00"))).isEqualTo(E1_100_0_IXQZ);
        accountId = main;
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 210/210/0");
        assertThat(full()).isEqualTo(defaultBefore);
    }

    // ================================================================== edge cases

    @Test
    @DisplayName("边界：同一时间、更晚录入的买入不算 STO 时的持股（把 S 移到 11-07 → E3「修改后 2026-11-06 10:00 起…需要 100 股，持有 0 股」）")
    void sameTimeLaterEnteredBuyDoesNotCount() {
        deposit("5000.00", "2026-11-02T10:00");
        long t = okStock(stockBody("TRANSFER_IN", "IXQZ", 100, "800.00", "2026-11-02"));
        long c = sto(1, "IXQZ", "2026-12-18", "10", "50.00", "2026-11-03T10:00", 100);
        okEdit(c, edit(1, "50.00", "2026-11-06T10:00", 100, null)); // still covered by the transfer
        okStock(stockBody("BUY", "IXQZ", 100, "850.00", "2026-11-06T10:00")); // same time, entered after C
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 200/100/100");
        assertThat(rejectedStockPut(t, stockEdit(100, "800.00", "tradeDate", "2026-11-07")))
                .isEqualTo("修改后 2026-11-06 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    @Test
    @DisplayName("边界：同一时间、更早录入的买入算持股 → STO 成功")
    void sameTimeEarlierEnteredBuyCounts() {
        deposit("5000.00", "2026-11-02T10:00");
        okStock(stockBody("BUY", "IXQZ", 100, "850.00", "2026-11-06T10:00"));
        long c = sto(1, "IXQZ", "2026-12-18", "10", "50.00", "2026-11-06T10:00", 100);
        assertThat(coverageStatus(c)).isEqualTo("CHECKED");
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/100/0");
    }

    @Test
    @DisplayName("边界：删除 STO call 不会因覆盖被拒（只释放股票）；删除后股票 200/100/100")
    void deleteShortCallReleases() {
        scenarioD();
        Resp r = deleteTrade(c1);
        assertThat(r.status()).as("delete C1 -> %s", r.body()).isEqualTo(204);
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 200/100/100");
        okStock(stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-05T10:00"));
        assertThat(stock("IXQZ")).isEqualTo("IXQZ 100/100/0");
    }

    @Test
    @DisplayName("Q102：价差（多头 call 保护）不算覆盖：持有 BTO 9 C 时 STO 10 C 仍要求股票")
    void spreadIsNotCoverage() {
        deposit("5000.00", "2026-11-02T10:00");
        okTrade(trade("BTO", 1, "IXQZ", "2026-12-18", "9", "CALL", "150.00", "2026-11-03T10:00", 100));
        assertThat(rejectedTrade(ixqzCall(1, "10", "100.00", "2026-11-03T11:00"))).isEqualTo(E1_100_0_IXQZ);
    }

    @Test
    @DisplayName("检查顺序：Q7 方向冲突先于 E1（多头 call 上无股票 STO 同合约 → POSITION_DIRECTION_CONFLICT）")
    void checkOrder() {
        deposit("1000.00", "2026-11-02T10:00");
        okTrade(trade("BTO", 1, "IXQZ", "2026-12-18", "10", "CALL", "50.00", "2026-11-03T10:00", 100));
        State before = state();
        Resp r = postTrade(ixqzCall(1, "10", "10.00", "2026-11-03T11:00"));
        assertErrorShape(r, 422, "POSITION_DIRECTION_CONFLICT", TRADES);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    @DisplayName("测试支持接口只在 e2e profile：test profile 下 POST /api/test-support/legacy-option-trades → 404")
    void legacyEndpointNotInTestProfile() {
        deposit("1000.00", "2026-11-02T10:00");
        State before = state();
        Resp r = postRaw("/api/test-support/legacy-option-trades", ixqzCall(1, "10", "10.00", "2026-11-03T10:00")
                .toString(), MediaType.APPLICATION_JSON);
        assertErrorShape(r, 404, "NOT_FOUND", "/api/test-support/legacy-option-trades");
        assertThat(state()).isEqualTo(before);
    }

    List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.get("text").asText()));
        return out;
    }
}
