package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-021 按标的分组查看. Backend N/A (docs/api/FEAT-021.md): the 策略 page only groups the responses of
 * {@code GET /api/option-positions} and {@code GET /api/stock-positions}. This system test pins, on the requirement's
 * acceptance scenario, the exact field values the page shows without any computation of its own (dte /
 * expirationStatus / daysPastExpiration, contracts, openingNetTotal, shares / coveredShares / availableShares /
 * totalCost / averageCost "48.0000" per Q88), the option row order the page keeps as-is (expiration → symbol →
 * strike numerically → CALL before PUT), the follow-the-data behaviour of AC 4–7 and account isolation (AC 8).
 *
 * <p>Whole application over HTTP, in-memory H2, profile "test". "Now" = 2026-11-23 10:00 New York unless a test moves
 * the clock. Fictional tickers only (QRTV, IXQZ, ZWLK).
 */
class Feat021GroupByUnderlyingSystemTest extends OptionTradeSystemTestBase {

    static final Instant MON_1123_1000 = Instant.parse("2026-11-23T15:00:00Z");
    static final Instant TUE_1124_1005 = Instant.parse("2026-11-24T15:05:00Z");
    static final Instant FRI_1218_1800 = Instant.parse("2026-12-18T23:00:00Z");

    static final String OPT_POS = "/api/option-positions";
    static final String STOCK_POS = "/api/stock-positions";
    static final String STOCKS = "/api/stock-trades";

    static final String IXQZ_58P = "IXQZ 2026-11-20 58 P";
    static final String IXQZ_60P = "IXQZ 2026-12-18 60 P";
    static final String QRTV_40P = "QRTV 2026-12-18 40 P";
    static final String QRTV_45P = "QRTV 2026-12-18 45 P";
    static final String QRTV_55C = "QRTV 2026-12-18 55 C";
    static final String QRTV_45P_JAN = "QRTV 2027-01-15 45 P";

    /** One option row as the page uses it (label, direction, contracts, openingNetTotal, expiration, dte, status, past). */
    static String opt(String label, String direction, int contracts, String net, String exp, int dte, String status,
                      int past) {
        return label + " | " + direction + " | " + contracts + " | " + net + " | " + exp + " | " + dte + " | " + status
                + " | " + past;
    }

    /** One stock row as the page uses it (symbol, shares, covered, available, totalCost, averageCost). */
    static String stock(String symbol, int shares, int covered, int available, String total, String avg) {
        return symbol + " | " + shares + " | " + covered + " | " + available + " | " + total + " | " + avg;
    }

    static final String ROW_IXQZ_58P = opt(IXQZ_58P, "SHORT", 1, "90.00", "2026-11-20", -3, "EXPIRED", 3);
    static final String ROW_IXQZ_60P = opt(IXQZ_60P, "SHORT", 1, "200.00", "2026-12-18", 25, "ACTIVE", 0);
    static final String ROW_QRTV_40P = opt(QRTV_40P, "LONG", 1, "-40.65", "2026-12-18", 25, "ACTIVE", 0);
    static final String ROW_QRTV_45P = opt(QRTV_45P, "SHORT", 2, "230.35", "2026-12-18", 25, "ACTIVE", 0);
    static final String ROW_QRTV_55C = opt(QRTV_55C, "SHORT", 1, "85.35", "2026-12-18", 25, "ACTIVE", 0);
    static final String ROW_QRTV_45P_JAN = opt(QRTV_45P_JAN, "SHORT", 1, "150.35", "2027-01-15", 53, "ACTIVE", 0);

    static final String STOCK_QRTV = stock("QRTV", 100, 100, 0, "4800.00", "48.0000");
    static final String STOCK_ZWLK = stock("ZWLK", 50, 0, 50, "1500.00", "30.0000");

    @Override
    Instant now() {
        return MON_1123_1000;
    }

    // ================================================================== helpers

    long stockTrade(String type, String symbol, int shares, String amount, String time) {
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
        Resp r = postRaw(STOCKS, n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("POST stock %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long open(String action, String symbol, String exp, String strike, String type, String net, String at) {
        return okTrade(trade(action, 1, symbol, exp, strike, type, net, at, 100)).get("id").asLong();
    }

    long close(String action, String symbol, String exp, String strike, String type, int contracts, String net,
               String at) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        n.putNull("note");
        Resp r = postRaw(TRADES + "/closes", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("%s %s -> %s", action, n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long expire(String symbol, String exp, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        Resp r = postRaw(TRADES + "/expirations", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("expire %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long assign(String symbol, String exp, String strike, String type, int contracts, String date) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        n.put("assignmentDate", date);
        n.putNull("assignmentFee");
        n.putNull("note");
        Resp r = postRaw(TRADES + "/assignments", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("assign %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void deleteTrade(long id) {
        Resp r = exchange(HttpMethod.DELETE, TRADES + "/" + id, null, MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("DELETE trade %d -> %s", id, r.body()).isEqualTo(204);
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

    JsonNode optionPositions() {
        Resp r = get(OPT_POS);
        assertThat(r.status()).as("GET %s -> %s", OPT_POS, r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode stockPositions() {
        Resp r = get(STOCK_POS);
        assertThat(r.status()).as("GET %s -> %s", STOCK_POS, r.body()).isEqualTo(200);
        return r.body();
    }

    /** contractLabel of every option position, in API order. */
    Stream<String> labels() {
        List<String> out = new ArrayList<>();
        optionPositions().get("positions").forEach(p -> out.add(p.get("contractLabel").asText()));
        return out.stream();
    }

    /** Option rows in API order; also checks positionCount and that symbol is the label's first word. */
    List<String> optionRows() {
        JsonNode body = optionPositions();
        List<String> rows = new ArrayList<>();
        for (JsonNode p : body.get("positions")) {
            String label = p.get("contractLabel").asText();
            assertThat(label).startsWith(p.get("symbol").asText() + " ");
            rows.add(opt(label, p.get("direction").asText(), p.get("contracts").asInt(),
                    p.get("openingNetTotal").asText(), p.get("expirationDate").asText(), p.get("dte").asInt(),
                    p.get("expirationStatus").asText(), p.get("daysPastExpiration").asInt()));
        }
        assertThat(body.get("positionCount").asInt()).isEqualTo(rows.size());
        return rows;
    }

    /** Stock rows in API order (symbol ascending); also checks positionCount and available = shares − covered. */
    List<String> stockRows() {
        JsonNode body = stockPositions();
        List<String> rows = new ArrayList<>();
        for (JsonNode p : body.get("positions")) {
            int shares = p.get("shares").asInt();
            int covered = p.get("coveredShares").asInt();
            int available = p.get("availableShares").asInt();
            assertThat(available).as("availableShares of %s", p.get("symbol")).isEqualTo(shares - covered);
            assertThat(p.get("totalCost").isTextual()).as("totalCost is a JSON string").isTrue();
            assertThat(p.get("averageCost").isTextual()).as("averageCost is a JSON string").isTrue();
            rows.add(stock(p.get("symbol").asText(), shares, covered, available, p.get("totalCost").asText(),
                    p.get("averageCost").asText()));
        }
        assertThat(body.get("positionCount").asInt()).isEqualTo(rows.size());
        return rows;
    }

    /** Ids of the requirement scenario. */
    record Scenario(long accountB, long t1, long t6, long t7) {
    }

    /**
     * Requirement scenario (all times New York): account A (id 1) deposit 50,000.00; S1 转入 QRTV 100 股 4,800.00,
     * S2 转入 ZWLK 50 股 1,500.00 (2026-10-01); T1–T7. Account B: deposit 10,000.00; T8.
     */
    Scenario scenario() {
        accountId = "1";
        deposit("50000.00", "2026-10-01T09:00");
        stockTrade("TRANSFER_IN", "QRTV", 100, "4800.00", "2026-10-01");
        stockTrade("TRANSFER_IN", "ZWLK", 50, "1500.00", "2026-10-01");
        long t1 = open("STO", "QRTV", "2026-12-18", "55", "CALL", "85.35", "2026-11-02T10:00");
        open("STO", "QRTV", "2026-12-18", "45", "PUT", "120.35", "2026-11-03T10:00");
        open("BTO", "QRTV", "2026-12-18", "40", "PUT", "40.65", "2026-11-03T10:05");
        open("STO", "QRTV", "2026-12-18", "45", "PUT", "110.00", "2026-11-05T10:00");
        open("STO", "QRTV", "2027-01-15", "45", "PUT", "150.35", "2026-11-05T10:10");
        long t6 = open("STO", "IXQZ", "2026-11-20", "58", "PUT", "90.00", "2026-11-02T10:30");
        long t7 = open("STO", "IXQZ", "2026-12-18", "60", "PUT", "200.00", "2026-11-05T10:30");
        long b = newAccount("账户B");
        accountId = String.valueOf(b);
        deposit("10000.00", "2026-10-01T09:00");
        open("STO", "QRTV", "2026-12-18", "45", "PUT", "115.00", "2026-11-05T11:00");
        accountId = "1";
        return new Scenario(b, t1, t6, t7);
    }

    // ================================================================== tests

    @Test
    @DisplayName("AC1–3 账户 A：期权 6 行按 到期日 → 标的 → 行权价 → C 在 P 前；DTE 25 / 53、58P 已过期 3 天；股票 QRTV 100/100/0/4800.00/48.0000、ZWLK 50/0/50/1500.00/30.0000")
    void scenarioFieldsAndOrder() {
        scenario();
        JsonNode body = optionPositions();
        assertThat(body.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(optionRows()).containsExactly(
                ROW_IXQZ_58P, ROW_IXQZ_60P,
                ROW_QRTV_40P, ROW_QRTV_45P, ROW_QRTV_55C, ROW_QRTV_45P_JAN);
        // openingNetTotal is a JSON string (money), contracts a number
        for (JsonNode p : body.get("positions")) {
            assertThat(p.get("openingNetTotal").isTextual()).isTrue();
            assertThat(p.get("contracts").isNumber()).isTrue();
        }
        // 45P: two lots merged into one contract-level row (Q186)
        assertThat(optionPositions().get("positions").get(3).get("lotCount").asInt()).isEqualTo(2);

        assertThat(stockRows()).containsExactly(STOCK_QRTV, STOCK_ZWLK);
    }

    @Test
    @DisplayName("AC4 11-24 BTC 1 张 45P 60.65 → 45P 1 张 +110.00（FIFO 平掉 T2）；STC 40P 20.65 → 40P 消失；卖出 ZWLK 50 股 → ZWLK 消失")
    void closesAndStockSaleFollowTheData() {
        scenario();
        clock.set(TUE_1124_1005);
        close("BTC", "QRTV", "2026-12-18", "45", "PUT", 1, "60.65", "2026-11-24T10:00");
        assertThat(optionRows()).containsExactly(
                opt(IXQZ_58P, "SHORT", 1, "90.00", "2026-11-20", -4, "EXPIRED", 4),
                opt(IXQZ_60P, "SHORT", 1, "200.00", "2026-12-18", 24, "ACTIVE", 0),
                opt(QRTV_40P, "LONG", 1, "-40.65", "2026-12-18", 24, "ACTIVE", 0),
                opt(QRTV_45P, "SHORT", 1, "110.00", "2026-12-18", 24, "ACTIVE", 0),
                opt(QRTV_55C, "SHORT", 1, "85.35", "2026-12-18", 24, "ACTIVE", 0),
                opt(QRTV_45P_JAN, "SHORT", 1, "150.35", "2027-01-15", 52, "ACTIVE", 0));

        close("STC", "QRTV", "2026-12-18", "40", "PUT", 1, "20.65", "2026-11-24T10:00");
        List<String> labels = labels().toList();
        assertThat(labels).containsExactly(IXQZ_58P, IXQZ_60P, QRTV_45P, QRTV_55C, QRTV_45P_JAN);
        assertThat(labels.stream().filter(l -> l.startsWith("QRTV ")).count()).as("QRTV 期权 3 个持仓").isEqualTo(3);
        assertThat(stockRows()).containsExactly(STOCK_QRTV, STOCK_ZWLK);

        stockTrade("SELL", "ZWLK", 50, "1600.00", "2026-11-24T10:00");
        assertThat(stockRows()).containsExactly(STOCK_QRTV);
    }

    @Test
    @DisplayName("AC5 作废 IXQZ 58P → IXQZ 只剩 60P；删除作废记录 → 恢复 AC3")
    void expireAndDeleteReplay() {
        scenario();
        long exp = expire("IXQZ", "2026-11-20", "58", "PUT", 1);
        assertThat(optionRows()).containsExactly(
                ROW_IXQZ_60P, ROW_QRTV_40P, ROW_QRTV_45P, ROW_QRTV_55C, ROW_QRTV_45P_JAN);
        assertThat(stockRows()).containsExactly(STOCK_QRTV, STOCK_ZWLK);

        deleteTrade(exp);
        assertThat(optionRows()).containsExactly(
                ROW_IXQZ_58P, ROW_IXQZ_60P, ROW_QRTV_40P, ROW_QRTV_45P, ROW_QRTV_55C, ROW_QRTV_45P_JAN);
        assertThat(stockRows()).containsExactly(STOCK_QRTV, STOCK_ZWLK);
    }

    @Test
    @DisplayName("AC6 12-18 18:00 IXQZ 60P 被指派 → IXQZ 股票 100/0/100/5800.00/58.0000，58P 仍在（已过期 28 天）；到期日当天 QRTV 行 dte 0 / EXPIRES_TODAY；删除指派 → 股票消失、60P 恢复")
    void putAssignmentAddsStockToTheSameSymbol() {
        scenario();
        clock.set(FRI_1218_1800);
        String row58 = opt(IXQZ_58P, "SHORT", 1, "90.00", "2026-11-20", -28, "EXPIRED", 28);
        String row60Today = opt(IXQZ_60P, "SHORT", 1, "200.00", "2026-12-18", 0, "EXPIRES_TODAY", 0);
        List<String> qrtvToday = List.of(
                opt(QRTV_40P, "LONG", 1, "-40.65", "2026-12-18", 0, "EXPIRES_TODAY", 0),
                opt(QRTV_45P, "SHORT", 2, "230.35", "2026-12-18", 0, "EXPIRES_TODAY", 0),
                opt(QRTV_55C, "SHORT", 1, "85.35", "2026-12-18", 0, "EXPIRES_TODAY", 0),
                opt(QRTV_45P_JAN, "SHORT", 1, "150.35", "2027-01-15", 28, "ACTIVE", 0));
        List<String> before = new ArrayList<>(List.of(row58, row60Today));
        before.addAll(qrtvToday);
        assertThat(optionRows()).containsExactlyElementsOf(before);

        long asg = assign("IXQZ", "2026-12-18", "60", "PUT", 1, "2026-12-18");
        List<String> after = new ArrayList<>(List.of(row58));
        after.addAll(qrtvToday);
        assertThat(optionRows()).containsExactlyElementsOf(after);
        // symbol ascending: IXQZ, QRTV, ZWLK; cost 60 × 100 − 200.00 premium (Q8)
        assertThat(stockRows()).containsExactly(
                stock("IXQZ", 100, 0, 100, "5800.00", "58.0000"), STOCK_QRTV, STOCK_ZWLK);

        deleteTrade(asg);
        assertThat(optionRows()).containsExactlyElementsOf(before);
        assertThat(stockRows()).containsExactly(STOCK_QRTV, STOCK_ZWLK);
    }

    @Test
    @DisplayName("AC7 12-18 18:00 QRTV 55C 被指派 → 55C 消失、QRTV 股票交出后消失（QRTV 期权 3 个持仓，无股票）")
    void callAssignmentRemovesTheStock() {
        scenario();
        clock.set(FRI_1218_1800);
        assign("QRTV", "2026-12-18", "55", "CALL", 1, "2026-12-18");
        List<String> labels = labels().toList();
        assertThat(labels).containsExactly(IXQZ_58P, IXQZ_60P, QRTV_40P, QRTV_45P, QRTV_45P_JAN);
        assertThat(stockRows()).containsExactly(STOCK_ZWLK);
    }

    @Test
    @DisplayName("AC8 账户 B 只有 QRTV 45P 空头 1 张 +115.00、无股票；账户 A 不受 B 影响；空账户 C 两个接口都为空")
    void accountIsolation() {
        Scenario s = scenario();
        accountId = String.valueOf(s.accountB());
        assertThat(optionRows()).containsExactly(
                opt(QRTV_45P, "SHORT", 1, "115.00", "2026-12-18", 25, "ACTIVE", 0));
        assertThat(stockRows()).isEmpty();

        accountId = "1";
        assertThat(optionRows()).containsExactly(
                ROW_IXQZ_58P, ROW_IXQZ_60P, ROW_QRTV_40P, ROW_QRTV_45P, ROW_QRTV_55C, ROW_QRTV_45P_JAN);
        assertThat(stockRows()).containsExactly(STOCK_QRTV, STOCK_ZWLK);

        long c = newAccount("账户C");
        accountId = String.valueOf(c);
        JsonNode opt = optionPositions();
        assertThat(opt.get("positions")).isEmpty();
        assertThat(opt.get("positionCount").asInt()).isZero();
        JsonNode stk = stockPositions();
        assertThat(stk.get("positions")).isEmpty();
        assertThat(stk.get("positionCount").asInt()).isZero();
    }

    @Test
    @DisplayName("组内顺序按行权价数值：QRTV 100 P 排在 45 P 之后（字符串排序会错）")
    void strikeOrderIsNumeric() {
        scenario();
        open("STO", "QRTV", "2026-12-18", "100", "PUT", "900.00", "2026-11-05T10:20");
        open("STO", "QRTV", "2026-12-18", "7.5", "PUT", "10.00", "2026-11-05T10:25");
        List<String> labels = labels()
                .filter(l -> l.startsWith("QRTV ")).toList();
        assertThat(labels).containsExactly(
                "QRTV 2026-12-18 7.5 P", QRTV_40P, QRTV_45P, QRTV_55C, "QRTV 2026-12-18 100 P", QRTV_45P_JAN);
    }
}
