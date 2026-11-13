package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-033 multiple accounts end-to-end over HTTP: X-Account-Id rules, account management and its audit, and full
 * isolation of every M1 rule per account. The default account holds scenario M (FEAT-008 scenario Q + extras);
 * "now" = 2026-11-12 10:00 New York. After every write in another account, the default account's complete read
 * snapshot must be unchanged.
 */
class Feat033AccountsSystemTest extends OptionTradeSystemTestBase {

    static final String ACCOUNTS = "/api/accounts";

    final Map<String, Long> ids = new LinkedHashMap<>();

    @Override
    Instant now() {
        return Instant.parse("2026-11-12T15:00:00Z");
    }

    // ------------------------------------------------------------------ helpers

    long open(String label, String action, int contracts, String symbol, String exp, String strike, String type,
              String net, String at, int multiplier) {
        long id = okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, multiplier)).get("id").asLong();
        ids.put(label, id);
        return id;
    }

    Resp closeResp(String action, int contracts, String symbol, String exp, String strike, String type, String net,
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
        return postRaw("/api/option-trades/closes", n.toString(), MediaType.APPLICATION_JSON);
    }

    long close(String label, String action, int contracts, String symbol, String exp, String strike, String type,
               String net, String at) {
        Resp r = closeResp(action, contracts, symbol, exp, strike, type, net, at);
        assertThat(r.status()).as("close %s", r.body()).isEqualTo(201);
        ids.put(label, r.body().get("id").asLong());
        return r.body().get("id").asLong();
    }

    /** Scenario M in the current account (account 1). */
    void scenarioM() {
        ids.put("D1", deposit("100000.00", "2026-10-26T10:00").get("id").asLong());
        open("O1", "BTO", 1, "TSLA", "2026-12-18", "300", "CALL", "500.00", "2026-10-27T10:00", 100);
        open("O2", "STO", 1, "AAPL", "2026-11-20", "180", "PUT", "150.00", "2026-10-28T10:00", 100);
        close("O3", "STC", 1, "TSLA", "2026-12-18", "300", "CALL", "620.00", "2026-10-30T10:00");
        close("O4", "BTC", 1, "AAPL", "2026-11-20", "180", "PUT", "200.00", "2026-10-31T23:30");
        open("T1", "STO", 2, "AAPL", "2026-12-18", "200", "PUT", "698.70", "2026-11-02T10:30", 100);
        open("T2", "BTO", 1, "MSFT", "2027-01-15", "450", "CALL", "900.00", "2026-11-02T14:00", 100);
        open("T3", "STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-02T15:00", 10);
        open("T4", "BTO", 2, "AAPL", "2026-12-18", "210", "CALL", "1000.00", "2026-11-03T09:45", 100);
        open("T5", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-03T11:00", 100);
        close("C1", "BTC", 3, "AAPL", "2026-12-18", "200", "PUT", "400.00", "2026-11-05T10:00");
        close("C2", "BTC", 1, "XYZ", "2026-12-18", "200", "PUT", "50.00", "2026-11-06T10:00");
        close("C3", "STC", 1, "MSFT", "2027-01-15", "450", "CALL", "1250.00", "2026-11-06T23:30");
        close("C4", "STC", 2, "AAPL", "2026-12-18", "210", "CALL", "640.00", "2026-11-07T10:00");
        open("T14", "STO", 1, "TSLA", "2026-11-06", "250", "PUT", "300.00", "2026-11-02T09:00", 100);
        open("T15", "BTO", 1, "SPY", "2026-11-13", "600", "CALL", "80.00", "2026-11-09T10:00", 100);
        open("T16", "STO", 1, "AAPL", "2026-11-20", "190", "PUT", "120.00", "2026-11-09T10:00", 100);
        ids.put("W2", withdraw("1000.00", "2026-11-10T10:00").get("id").asLong());
        ObjectNode e = json.createObjectNode();
        e.put("contracts", 2);
        e.put("netAmount", "700.00");
        e.put("tradedAtLocal", "2026-11-07T10:00");
        assertThat(exchange(HttpMethod.PUT, TRADES + "/" + ids.get("C4"), e.toString(), MediaType.APPLICATION_JSON)
                .status()).isEqualTo(200);
        assertThat(exchange(HttpMethod.PUT, "/api/cash/transfers/" + ids.get("W2"),
                transferBody("WITHDRAWAL", "1500.00", "2026-11-10T10:00", null).toString(),
                MediaType.APPLICATION_JSON).status()).isEqualTo(200);
        assertSummary("99603.05", "44000.00", "55603.05");
    }

    static final List<String> READS = List.of("/api/cash/summary", "/api/cash/ledger?size=100",
            "/api/option-trades?size=100", "/api/option-positions", "/api/closed-trades?size=100", "/api/pnl-summary",
            "/api/dashboard", "/api/audit-log?size=100");

    /** Every read endpoint (plus each trade by id) as seen by the given account. */
    Map<String, JsonNode> snapshot(String account) {
        String saved = accountId;
        accountId = account;
        try {
            Map<String, JsonNode> out = new LinkedHashMap<>();
            for (String path : READS) {
                Resp r = get(path);
                assertThat(r.status()).as(path).isEqualTo(200);
                out.put(path, r.body());
            }
            for (JsonNode t : out.get("/api/option-trades?size=100").get("items")) {
                String p = TRADES + "/" + t.get("id").asLong();
                out.put(p, get(p).body());
            }
            return out;
        } finally {
            accountId = saved;
        }
    }

    JsonNode createAccount(String name, String brokerNote) {
        Resp r = postAccount(name, brokerNote);
        assertThat(r.status()).as("create account %s -> %s", name, r.body()).isEqualTo(201);
        return r.body();
    }

    Resp postAccount(String name, String brokerNote) {
        ObjectNode n = json.createObjectNode();
        if (name != null) {
            n.put("name", name);
        }
        if (brokerNote != null) {
            n.put("brokerNote", brokerNote);
        }
        return postRaw(ACCOUNTS, n.toString(), MediaType.APPLICATION_JSON);
    }

    Resp putAccount(long id, String name, String brokerNote) {
        ObjectNode n = json.createObjectNode();
        if (name != null) {
            n.put("name", name);
        }
        if (brokerNote != null) {
            n.put("brokerNote", brokerNote);
        }
        return exchange(HttpMethod.PUT, ACCOUNTS + "/" + id, n.toString(), MediaType.APPLICATION_JSON);
    }

    void nameError(Resp r, String field, String message) {
        assertErrorShape(r, 400, "VALIDATION_ERROR", r.body().get("path").asText());
        List<String> d = new ArrayList<>();
        r.body().get("details").forEach(x -> d.add(x.get("field").asText() + "=" + x.get("message").asText()));
        assertThat(d).containsExactly(field + "=" + message);
    }

    List<String> accountNames() {
        List<String> out = new ArrayList<>();
        get(ACCOUNTS).body().forEach(a -> out.add(a.get("id").asLong() + ":" + a.get("name").asText()));
        return out;
    }

    void as(long account) {
        accountId = String.valueOf(account);
    }

    // ------------------------------------------------------------------ header rules

    @Test
    @DisplayName("X-Account-Id：缺失/空白 → ACCOUNT_REQUIRED；非整数 → BAD_REQUEST；不存在 → ACCOUNT_NOT_FOUND；都不写数据；先于请求体校验")
    void headerErrors() {
        deposit("100.00", "2026-11-02T10:00");
        State before = state();
        List<Object[]> calls = List.of(
                new Object[] {HttpMethod.GET, "/api/cash/summary", null},
                new Object[] {HttpMethod.GET, "/api/cash/ledger", null},
                new Object[] {HttpMethod.POST, "/api/cash/transfers", "{\"type\":\"DEPOSIT\",\"amount\":\"1.00\",\"occurredAtLocal\":\"2026-11-02T10:00\"}"},
                new Object[] {HttpMethod.PUT, "/api/cash/transfers/1", "{}"},
                new Object[] {HttpMethod.DELETE, "/api/cash/transfers/1", null},
                new Object[] {HttpMethod.GET, "/api/option-trades", null},
                new Object[] {HttpMethod.POST, "/api/option-trades", "{\"bad\":true}"},
                new Object[] {HttpMethod.POST, "/api/option-trades/closes", "{}"},
                new Object[] {HttpMethod.GET, "/api/option-trades/1", null},
                new Object[] {HttpMethod.GET, "/api/option-positions", null},
                new Object[] {HttpMethod.GET, "/api/closed-trades", null},
                new Object[] {HttpMethod.GET, "/api/pnl-summary", null},
                new Object[] {HttpMethod.GET, "/api/dashboard", null},
                new Object[] {HttpMethod.GET, "/api/audit-log", null});
        for (Object[] c : calls) {
            String path = (String) c[1];
            String body = (String) c[2];
            String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            for (String header : new String[] {null, "", "   "}) {
                accountId = header;
                Resp r = exchange((HttpMethod) c[0], path, body, MediaType.APPLICATION_JSON);
                assertErrorShape(r, 400, "ACCOUNT_REQUIRED", bare);
                assertThat(r.body().get("details").get(0).get("field").asText()).isEqualTo("X-Account-Id");
                assertThat(r.body().get("details").get(0).get("message").asText()).isEqualTo("请指定账户");
            }
            accountId = "abc";
            Resp nonInt = exchange((HttpMethod) c[0], path, body, MediaType.APPLICATION_JSON);
            assertErrorShape(nonInt, 400, "BAD_REQUEST", bare);
            assertThat(nonInt.body().get("details").get(0).get("field").asText()).isEqualTo("X-Account-Id");
            assertThat(nonInt.body().get("details").get(0).get("message").asText()).isEqualTo("账户 id 必须是整数");
            accountId = "99";
            Resp unknown = exchange((HttpMethod) c[0], path, body, MediaType.APPLICATION_JSON);
            assertErrorShape(unknown, 404, "ACCOUNT_NOT_FOUND", bare);
            assertThat(unknown.body().get("message").asText()).isEqualTo("Account 99 not found");
            assertThat(unknown.body().get("details")).isEmpty();
        }
        accountId = null;
        assertThat(get("/api/health").status()).isEqualTo(200);
        assertThat(get(ACCOUNTS).status()).isEqualTo(200);
        accountId = "1";
        assertThat(state()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ account management (AC 4)

    @Test
    @DisplayName("AC4 账户：初始只有默认账户；' 小账户 ' 去空白保存；重名（含大小写、非 ASCII）/空/31 字符被拒；30 字符、备注 50 允许；审计写在本账户")
    void ac4_accountManagement() {
        assertThat(accountNames()).containsExactly("1:默认账户");
        JsonNode first = get(ACCOUNTS).body().get(0);
        assertThat(first.get("brokerNote").isNull()).isTrue();

        JsonNode small = createAccount(" 小账户 ", "券商 B");
        long sid = small.get("id").asLong();
        assertThat(sid).isGreaterThan(1);
        assertThat(small.get("name").asText()).isEqualTo("小账户");
        assertThat(small.get("brokerNote").asText()).isEqualTo("券商 B");
        assertThat(Instant.parse(small.get("createdAt").asText())).isEqualTo(now());
        assertThat(get(ACCOUNTS + "/" + sid).body()).isEqualTo(small);
        assertThat(accountNames()).containsExactly("1:默认账户", sid + ":小账户");

        nameError(postAccount("默认账户", null), "name", "账户名称已存在");
        nameError(postAccount("小账户", null), "name", "账户名称已存在");
        nameError(postAccount("  小账户", null), "name", "账户名称已存在");
        nameError(postAccount("", null), "name", "账户名称不能为空");
        nameError(postAccount("   ", null), "name", "账户名称不能为空");
        Resp missing = postAccount(null, null);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(detailFields(missing)).containsExactly("name");
        nameError(postAccount("x".repeat(31), null), "name", "账户名称最多 30 个字符");
        nameError(postAccount("ok", "b".repeat(51)), "brokerNote", "券商备注最多 50 个字符");
        JsonNode main = createAccount("Main", null);
        nameError(postAccount("MAIN", null), "name", "账户名称已存在");
        nameError(postAccount(" main ", null), "name", "账户名称已存在");
        createAccount("Ärger", null);
        nameError(postAccount("ärger", null), "name", "账户名称已存在");
        JsonNode thirty = createAccount("y".repeat(30), "b".repeat(50));
        assertThat(thirty.get("brokerNote").asText()).hasSize(50);
        JsonNode blankNote = createAccount("备注空白", "   ");
        assertThat(blankNote.get("brokerNote").isNull()).isTrue();
        assertThat(get(ACCOUNTS).body()).hasSize(6);

        // ids ascending
        List<Long> idList = new ArrayList<>();
        get(ACCOUNTS).body().forEach(a -> idList.add(a.get("id").asLong()));
        assertThat(idList).isSorted();

        // CREATE audit lives in the new account only
        as(sid);
        JsonNode created = get("/api/audit-log").body();
        assertThat(created.get("totalItems").asInt()).isEqualTo(1);
        JsonNode rec = created.get("items").get(0);
        assertThat(rec.get("operation").asText()).isEqualTo("CREATE");
        assertThat(rec.get("objectType").asText()).isEqualTo("ACCOUNT");
        assertThat(rec.get("objectId").asLong()).isEqualTo(sid);
        assertThat(rec.get("before").isNull()).isTrue();
        assertThat(rec.get("changes")).isEmpty();
        assertThat(rec.get("summary").asText()).isEqualTo("账户 小账户");
        assertThat(rec.get("after").get("name").asText()).isEqualTo("小账户");
        assertThat(rec.get("after").get("brokerNote").asText()).isEqualTo("券商 B");
        as(1);
        assertThat(get("/api/audit-log").body().get("totalItems").asInt()).isZero();

        // rename default account
        Resp renamed = putAccount(1, "主账户", null);
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().get("name").asText()).isEqualTo("主账户");
        assertThat(accountNames().get(0)).isEqualTo("1:主账户");
        JsonNode upd = get("/api/audit-log").body().get("items").get(0);
        assertThat(upd.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(upd.get("objectId").asLong()).isEqualTo(1);
        assertThat(texts(upd.get("changes"))).containsExactly("名称：默认账户 → 主账户");
        assertThat(upd.get("summary").asText()).isEqualTo("账户 默认账户");
        assertThat(get("/api/audit-log?objectType=ACCOUNT&objectId=1").body().get("totalItems").asInt()).isEqualTo(1);

        Resp note = putAccount(1, "主账户", "IBKR");
        assertThat(note.status()).isEqualTo(200);
        assertThat(texts(get("/api/audit-log").body().get("items").get(0).get("changes")))
                .containsExactly("券商备注：— → IBKR");
        // PUT is a full replacement: omitting brokerNote clears it
        assertThat(putAccount(1, "主账户", null).body().get("brokerNote").isNull()).isTrue();

        Resp same = putAccount(1, " 主账户 ", null);
        assertErrorShape(same, 422, "NO_CHANGES", ACCOUNTS + "/1");
        assertThat(same.body().get("message").asText()).isEqualTo("没有修改");
        nameError(putAccount(1, "小账户", null), "name", "账户名称已存在");
        nameError(putAccount(1, "", null), "name", "账户名称不能为空");
        // changing only the case of its own name is allowed (unique against the other accounts)
        assertThat(putAccount(main.get("id").asLong(), "MAIN", null).status()).isEqualTo(200);

        assertErrorShape(get(ACCOUNTS + "/99"), 404, "ACCOUNT_NOT_FOUND", ACCOUNTS + "/99");
        assertErrorShape(putAccount(99, "x", null), 404, "ACCOUNT_NOT_FOUND", ACCOUNTS + "/99");
        assertErrorShape(exchange(HttpMethod.DELETE, ACCOUNTS + "/1", null, MediaType.APPLICATION_JSON), 405,
                "METHOD_NOT_ALLOWED", ACCOUNTS + "/1");
        assertThat(get(ACCOUNTS).body()).hasSize(6);
    }

    List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.get("text").asText()));
        return out;
    }

    // ------------------------------------------------------------------ AC 5-8, 10: isolation

    @Test
    @DisplayName("AC5–AC8 隔离：新账户从零开始；现金/担保（Q18/Q19）、多空与 FIFO（Q7/Q21）、修改删除、审计都按账户；默认账户全部数字不变")
    void ac5to8_isolation() {
        scenarioM();
        Map<String, JsonNode> defaultBefore = snapshot("1");
        long sid = createAccount("小账户", "券商 B").get("id").asLong();
        as(sid);

        // AC 5: empty
        assertSummary("0.00", "0.00", "0.00");
        assertThat(ledgerCount()).isZero();
        assertThat(get(TRADES).body().get("totalItems").asInt()).isZero();
        assertThat(get("/api/option-positions").body().get("positionCount").asInt()).isZero();
        assertThat(get("/api/closed-trades").body().get("filteredCount").asInt()).isZero();
        assertThat(get("/api/pnl-summary").body().get("realizedPnlTotal").asText()).isEqualTo("0.00");
        JsonNode dash = get("/api/dashboard").body();
        assertThat(dash.get("realizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(dash.get("realizedPnl").get("closedCount").asInt()).isZero();
        assertThat(dash.get("cash").get("totalCash").asText()).isEqualTo("0.00");
        JsonNode audit = get("/api/audit-log").body();
        assertThat(audit.get("totalItems").asInt()).isEqualTo(1);
        assertThat(audit.get("items").get(0).get("operation").asText()).isEqualTo("CREATE");
        assertThat(snapshot("1")).isEqualTo(defaultBefore);

        // AC 6: cash and collateral per account
        long smallDeposit = deposit("30000.00", "2026-11-02T10:00").get("id").asLong();
        open("S1", "STO", 1, "AAPL", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:30", 100);
        assertSummary("30349.35", "20000.00", "10349.35");
        Resp tooMuch = postTrade(trade("BTO", 1, "MSFT", "2026-12-18", "450", "CALL", "20000.00", "2026-11-04T10:00", 100));
        assertErrorShape(tooMuch, 422, "CASH_INSUFFICIENT", TRADES);
        assertThat(tooMuch.body().get("message").asText()).isEqualTo("可用现金不足：需要 20,000.00，可用 10,349.35");
        Resp q18 = postTrade(trade("STO", 1, "QQQ", "2026-12-18", "500", "PUT", "1.00", "2026-11-04T10:00", 100));
        assertErrorShape(q18, 422, "CASH_INSUFFICIENT", TRADES);
        assertThat(q18.body().get("message").asText()).isEqualTo("可用现金不足以担保卖出 put：需要 50,000.00，可用 10,349.35");
        assertThat(snapshot("1")).isEqualTo(defaultBefore);

        // AC 7: opposite direction of the same contract across accounts; FIFO only within the account
        open("S2", "BTO", 1, "AAPL", "2026-11-20", "190", "PUT", "300.00", "2026-11-04T10:00", 100);
        assertSummary("30049.35", "20000.00", "10049.35");
        Map<String, String> smallPositions = new LinkedHashMap<>();
        get("/api/option-positions").body().get("positions")
                .forEach(p -> smallPositions.put(p.get("contractLabel").asText(), p.get("direction").asText()));
        assertThat(smallPositions).containsExactly(
                Map.entry("AAPL 2026-11-20 190 P", "LONG"), Map.entry("AAPL 2026-12-18 200 P", "SHORT"));
        long sbtc = close("SBTC", "BTC", 1, "AAPL", "2026-12-18", "200", "PUT", "100.00", "2026-11-05T10:00");
        JsonNode sClose = getTrade(sbtc);
        assertThat(sClose.get("realizedPnl").asText()).isEqualTo("249.35");
        assertThat(sClose.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(ids.get("S1"));
        assertSummary("29949.35", "0.00", "29949.35");
        JsonNode sClosed = get("/api/closed-trades").body();
        assertThat(sClosed.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(sClosed.get("filteredRealizedPnlTotal").asText()).isEqualTo("249.35");
        JsonNode sDash = get("/api/dashboard").body();
        assertThat(sDash.get("realizedPnl").get("total").asText()).isEqualTo("249.35");
        assertThat(sDash.get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(get("/api/pnl-summary").body().get("bySymbol").get(0).get("realizedPnl").asText()).isEqualTo("249.35");
        assertThat(snapshot("1")).isEqualTo(defaultBefore);

        // default account: its own 200 P was fully closed by C1, the small account's lot is not visible
        as(1);
        Resp noPos = closeResp("BTC", 1, "AAPL", "2026-12-18", "200", "PUT", "1.00", "2026-11-11T10:00");
        assertErrorShape(noPos, 422, "NO_POSITION_TO_CLOSE", "/api/option-trades/closes");
        assertThat(noPos.body().get("message").asText()).isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时没有持仓，无法平仓");
        assertThat(snapshot("1")).isEqualTo(defaultBefore);

        // AC 8: edit in the small account, audit per account
        as(sid);
        ObjectNode e = json.createObjectNode();
        e.put("contracts", 1);
        e.put("netAmount", "110.00");
        e.put("tradedAtLocal", "2026-11-05T10:00");
        assertThat(exchange(HttpMethod.PUT, TRADES + "/" + sbtc, e.toString(), MediaType.APPLICATION_JSON).status())
                .isEqualTo(200);
        assertThat(getTrade(sbtc).get("realizedPnl").asText()).isEqualTo("239.35");
        JsonNode sAudit = get("/api/audit-log").body();
        assertThat(sAudit.get("totalItems").asInt()).isEqualTo(2);
        List<String> ops = new ArrayList<>();
        sAudit.get("items").forEach(i -> ops.add(i.get("operation").asText() + " " + i.get("objectType").asText()));
        assertThat(ops).containsExactly("UPDATE OPTION_TRADE", "CREATE ACCOUNT");
        Map<String, JsonNode> smallBefore = snapshot(String.valueOf(sid));

        // cross-account access from the default account is "not found"; nothing changes anywhere
        as(1);
        for (Resp r : List.of(get(TRADES + "/" + sbtc),
                exchange(HttpMethod.PUT, TRADES + "/" + sbtc, e.toString(), MediaType.APPLICATION_JSON),
                exchange(HttpMethod.DELETE, TRADES + "/" + sbtc, null, MediaType.APPLICATION_JSON))) {
            assertErrorShape(r, 404, "NOT_FOUND", TRADES + "/" + sbtc);
            assertThat(r.body().get("message").asText()).isEqualTo("Option trade " + sbtc + " not found");
        }
        for (Resp r : List.of(
                exchange(HttpMethod.PUT, "/api/cash/transfers/" + smallDeposit,
                        transferBody("DEPOSIT", "1.00", "2026-11-02T10:00", null).toString(), MediaType.APPLICATION_JSON),
                exchange(HttpMethod.DELETE, "/api/cash/transfers/" + smallDeposit, null, MediaType.APPLICATION_JSON))) {
            assertErrorShape(r, 404, "NOT_FOUND", "/api/cash/transfers/" + smallDeposit);
            assertThat(r.body().get("message").asText()).isEqualTo("Cash transfer " + smallDeposit + " not found");
        }
        assertThat(get("/api/audit-log?objectType=OPTION_TRADE&objectId=" + sbtc).body().get("totalItems").asInt()).isZero();
        assertThat(get("/api/audit-log?objectType=ACCOUNT&objectId=" + sid).body().get("totalItems").asInt()).isZero();
        // and the default account's records are equally invisible from the small account
        as(sid);
        assertErrorShape(get(TRADES + "/" + ids.get("C1")), 404, "NOT_FOUND", TRADES + "/" + ids.get("C1"));
        assertErrorShape(exchange(HttpMethod.DELETE, "/api/cash/transfers/" + ids.get("D1"), null,
                MediaType.APPLICATION_JSON), 404, "NOT_FOUND", "/api/cash/transfers/" + ids.get("D1"));
        assertThat(snapshot(String.valueOf(sid))).isEqualTo(smallBefore);
        assertThat(snapshot("1")).isEqualTo(defaultBefore);

        // deleting the small account's deposit breaks its own later cash points (Q19)
        Resp del = exchange(HttpMethod.DELETE, "/api/cash/transfers/" + smallDeposit, null, MediaType.APPLICATION_JSON);
        assertErrorShape(del, 422, "CASH_INSUFFICIENT", "/api/cash/transfers/" + smallDeposit);
        assertThat(del.body().get("message").asText()).startsWith("可用现金不足：删除后 2026-11-03 10:30");
        assertThat(snapshot(String.valueOf(sid))).isEqualTo(smallBefore);
        assertThat(snapshot("1")).isEqualTo(defaultBefore);
    }

    @Test
    @DisplayName("AC10 不存在的账户查询现金汇总 / 交易列表 → 404 ACCOUNT_NOT_FOUND，不返回数据")
    void ac10_unknownAccount() {
        deposit("100.00", "2026-11-02T10:00");
        accountId = "99";
        for (String p : List.of("/api/cash/summary", "/api/option-trades")) {
            Resp r = get(p);
            assertErrorShape(r, 404, "ACCOUNT_NOT_FOUND", p);
            assertThat(r.body().has("items")).isFalse();
            assertThat(r.body().has("totalCash")).isFalse();
        }
    }

    @Test
    @DisplayName("Q23 / Q20 / 同一时刻 按账户：同合约不同 multiplier 各自允许；BTC 只释放本账户冻结；id 全局递增、各自流水只含自己的")
    void moreRulesPerAccount() {
        deposit("50000.00", "2026-11-02T10:00");
        open("A1", "STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-03T10:00", 10);
        long sid = createAccount("B", null).get("id").asLong();
        as(sid);
        deposit("20000.00", "2026-11-02T10:00");
        open("B1", "STO", 1, "XYZ", "2026-12-18", "200", "PUT", "349.35", "2026-11-03T10:00", 100);
        assertSummary("20349.35", "20000.00", "349.35");
        // Q20 in B: release B's 20,000 then pay 500
        close("BC", "BTC", 1, "XYZ", "2026-12-18", "200", "PUT", "500.00", "2026-11-05T10:00");
        assertSummary("19849.35", "0.00", "19849.35");
        as(1);
        assertSummary("50034.35", "2000.00", "48034.35");
        JsonNode p = get("/api/option-positions").body().get("positions").get(0);
        assertThat(p.get("multiplier").asInt()).isEqualTo(10);
        assertThat(p.get("frozenCash").asText()).isEqualTo("2000.00");
        // ledger ids are global and increasing, but each ledger only has its own rows
        List<Long> aIds = new ArrayList<>();
        ledger(1, 100).get("items").forEach(i -> aIds.add(i.get("id").asLong()));
        as(sid);
        List<Long> bIds = new ArrayList<>();
        ledger(1, 100).get("items").forEach(i -> bIds.add(i.get("id").asLong()));
        assertThat(aIds).hasSize(2).doesNotContainAnyElementsOf(bIds);
        assertThat(bIds).hasSize(3);
        assertThat(ids.get("B1")).isGreaterThan(ids.get("A1"));
    }

    @Test
    @DisplayName("每个账户一把锁：两个账户各 1,000，各 6 个并发出金 300 → 每个账户恰好 3 笔成功，没有 500")
    void perAccountLocks() throws Exception {
        long sid = createAccount("并发", null).get("id").asLong();
        deposit("1000.00", "2026-11-02T10:00");
        as(sid);
        deposit("1000.00", "2026-11-02T10:00");
        as(1);
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(2 * n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> fa = new ArrayList<>();
        List<Future<Integer>> fb = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                fa.add(pool.submit(() -> {
                    start.await();
                    return withdrawAs("1", "300.00");
                }));
                fb.add(pool.submit(() -> {
                    start.await();
                    return withdrawAs(String.valueOf(sid), "300.00");
                }));
            }
            start.countDown();
            List<Integer> a = new ArrayList<>();
            List<Integer> b = new ArrayList<>();
            for (Future<Integer> f : fa) {
                a.add(f.get());
            }
            for (Future<Integer> f : fb) {
                b.add(f.get());
            }
            assertThat(a).containsOnly(201, 422);
            assertThat(b).containsOnly(201, 422);
            assertThat(a.stream().filter(s -> s == 201).count()).isEqualTo(3);
            assertThat(b.stream().filter(s -> s == 201).count()).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        as(1);
        assertSummary("100.00", "0.00", "100.00");
        as(sid);
        assertSummary("100.00", "0.00", "100.00");
    }

    /** Thread-safe withdrawal for an explicit account (does not use the shared accountId field). */
    int withdrawAs(String account, String amount) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Account-Id", account);
        String body = transferBody("WITHDRAWAL", amount, "2026-11-03T10:00", null).toString();
        return http.exchange("/api/cash/transfers", HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), h),
                byte[].class).getStatusCode().value();
    }
}
