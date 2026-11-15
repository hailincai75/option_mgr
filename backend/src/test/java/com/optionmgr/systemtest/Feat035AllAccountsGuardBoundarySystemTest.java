package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-035 (contract docs/api/FEAT-035.md): the {@code X-Account-Id: ALL} guards (every write →
 * 400 ALL_ACCOUNTS_READ_ONLY with nothing written; reads outside the 7 GETs → 400 ALL_ACCOUNTS_NOT_SUPPORTED),
 * header parsing, endpoints that ignore the account (market data, accounts, calendar, health), single-account
 * responses with the new fields, and boundary cases (empty new account, same stock / same contract in two accounts,
 * only one account, rename, an alert affecting one account).
 */
@DisplayName("FEAT-035 全部账户：只读保护、请求头、边界（系统测试）")
class Feat035AllAccountsGuardBoundarySystemTest extends Feat035AllAccountsSystemTestBase {

    record Call(HttpMethod method, String path, String body) {
    }

    ObjectNode contract(String symbol, String exp, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        return n;
    }

    /** Representative writes of every kind; with a single account each body would be accepted / meaningful. */
    List<Call> writes() {
        long a1 = ids.get("A1");
        long b4 = ids.get("B4");
        long ixqz = ids.get("A_IXQZ");
        long dep = ids.get("A_DEP");
        String sto = trade("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "90.00", "2026-11-23T09:40").toString();
        ObjectNode close = trade("BTC", 1, "QRTV", "2026-12-18", "50", "PUT", "100.00", "2026-11-23T09:40");
        close.remove("multiplier");
        ObjectNode expire = contract("QRTV", "2026-11-20", "45", "PUT", 1);
        ObjectNode batch = json.createObjectNode();
        batch.putArray("items").add(contract("QRTV", "2026-11-20", "45", "PUT", 1));
        ObjectNode assign = contract("QRTV", "2026-11-20", "45", "PUT", 1);
        assign.put("assignmentDate", "2026-11-20");
        assign.put("assignmentFee", "0.00");
        assign.putNull("note");
        ObjectNode exercise = contract("QRTV", "2026-12-18", "50", "PUT", 1);
        exercise.put("exerciseDate", "2026-11-23");
        exercise.put("exerciseFee", "0.00");
        exercise.putNull("note");
        ObjectNode adjust = json.createObjectNode();
        adjust.put("symbol", "QRTV");
        adjust.put("expirationDate", "2026-12-18");
        adjust.put("strike", "50");
        adjust.put("optionType", "PUT");
        adjust.put("newExpirationDate", "2026-12-17");
        adjust.putNull("note");
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "130.00");
        edit.put("tradedAtLocal", "2026-11-20T10:00");
        edit.putNull("note");
        String buy = "{\"type\":\"BUY\",\"symbol\":\"EKVR\",\"shares\":10,\"amount\":\"200.00\","
                + "\"tradedAtLocal\":\"2026-11-23T09:40\",\"note\":null}";
        String stockEdit = "{\"type\":\"TRANSFER_IN\",\"symbol\":\"IXQZ\",\"shares\":100,\"amount\":\"5000.00\","
                + "\"tradeDate\":\"2026-11-19\",\"note\":null}";
        String transfer = "{\"type\":\"DEPOSIT\",\"amount\":\"100.00\",\"occurredAtLocal\":\"2026-11-23T09:30\","
                + "\"remark\":null}";
        String legacy = trade("STO", 1, "IXQZ", "2026-12-18", "70", "CALL", "30.00", "2026-11-23T09:40").toString();
        return List.of(
                new Call(HttpMethod.POST, TRANSFERS, transfer),
                new Call(HttpMethod.PUT, TRANSFERS + "/" + dep, transfer),
                new Call(HttpMethod.DELETE, TRANSFERS + "/" + dep, null),
                new Call(HttpMethod.POST, TRADES, sto),
                new Call(HttpMethod.POST, TRADES, "{\"bad\":true}"),
                new Call(HttpMethod.POST, TRADES + "/closes", close.toString()),
                new Call(HttpMethod.POST, TRADES + "/expirations", expire.toString()),
                new Call(HttpMethod.POST, TRADES + "/expirations/batch", batch.toString()),
                new Call(HttpMethod.POST, TRADES + "/assignments", assign.toString()),
                new Call(HttpMethod.POST, TRADES + "/exercises", exercise.toString()),
                new Call(HttpMethod.POST, TRADES + "/expiration-adjustments", adjust.toString()),
                new Call(HttpMethod.PUT, TRADES + "/" + a1, edit.toString()),
                new Call(HttpMethod.DELETE, TRADES + "/" + a1, null),
                new Call(HttpMethod.DELETE, TRADES + "/" + b4, null),
                new Call(HttpMethod.POST, STOCK_TRADES, buy),
                new Call(HttpMethod.PUT, STOCK_TRADES + "/" + ixqz, stockEdit),
                new Call(HttpMethod.DELETE, STOCK_TRADES + "/" + ixqz, null),
                new Call(HttpMethod.POST, LEGACY, legacy));
    }

    // =========================================================================================== write guard

    @Test
    @DisplayName("写接口（入金/改/删流水、开仓、平仓、作废、批量作废、指派、行权、调整到期日、改/删交易、股票录入/改/删、legacy 测试支持）带 ALL → 400 ALL_ACCOUNTS_READ_ONLY，什么都不写（含审计）")
    void writesWithAllRejected() {
        scenarioS();
        Map<String, Object> before = world();
        for (Call c : writes()) {
            accountId = ALL;
            Resp r = exchange(c.method(), c.path(), c.body());
            accountId = A;
            assertGuardError(r, READ_ONLY_CODE, c.path());
        }
        assertThat(world()).as("nothing written by any rejected ALL write").isEqualTo(before);
    }

    @Test
    @DisplayName("写保护先于请求体校验；ALL 不区分大小写、去首尾空白（' all '、'All'）；同一写请求带单账户头正常成功（对照）")
    void writeGuardVariants() {
        scenarioS();
        Map<String, Object> before = world();
        for (String header : List.of("all", "All", " ALL ", " all ")) {
            accountId = header;
            Resp r = post(TRANSFERS, "{}");
            assertGuardError(r, READ_ONLY_CODE, TRANSFERS);
        }
        accountId = A;
        assertThat(world()).isEqualTo(before);
        // control: the same deposit with a single account works (so the rejection above is only the ALL scope)
        depositId("100.00", "2026-11-23T09:30");
        assertThat(okAs(A, CASH_SUMMARY).get("totalCash").asText()).isEqualTo("50363.05");
        assertThat(okAs(ALL, CASH_SUMMARY).get("totalCash").asText()).isEqualTo("70573.05");
    }

    // =========================================================================================== read guard

    @Test
    @DisplayName("仍不支持 ALL 的 GET（/{id}、审计；FEAT-047 起组合 Greeks、策略支持 ALL）带 ALL → 400 ALL_ACCOUNTS_NOT_SUPPORTED（带查询参数也一样），不读不写；FEAT-046 起 4 个列表不在其中")
    void unsupportedReads() {
        scenarioS();
        Map<String, Object> before = world();
        // FEAT-046 (requirement change): /api/option-trades, /api/stock-trades, /api/closed-trades, /api/cash/ledger
        // accept ALL now and are no longer asserted here (see Feat046AllAccountsLists*SystemTest)
        // FEAT-047 (requirement change): greeks-summary / underlying-summary accept ALL now
        for (String path : List.of(TRADES + "/" + ids.get("B3"), STOCK_TRADES + "/" + ids.get("A_IXQZ"),
                "/api/audit-log?size=100")) {
            String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            for (String h : List.of(ALL, "all")) {
                assertGuardError(getAs(h, path), NOT_SUPPORTED_CODE, bare);
            }
        }
        assertThat(world()).isEqualTo(before);
    }

    @Test
    @DisplayName("7 个接口：ALL / all / ' ALL ' 都返回 200 且结果相同；缺失 → ACCOUNT_REQUIRED、'abc'/'ALLX' → BAD_REQUEST、'99' → ACCOUNT_NOT_FOUND 不变")
    void headerParsingOnAllReads() {
        scenarioS();
        for (String path : ALL_READS) {
            JsonNode upper = okAs(ALL, path);
            assertThat(okAs("all", path)).as("%s lower case", path).isEqualTo(upper);
            assertThat(okAs(" ALL ", path)).as("%s trimmed", path).isEqualTo(upper);
            for (String blank : new String[] {null, "", "   "}) {
                Resp r = getAs(blank, path);
                assertErrorShape(r, 400, "ACCOUNT_REQUIRED", path);
            }
            for (String bad : List.of("abc", "ALLX", "1,2")) {
                Resp r = getAs(bad, path);
                assertErrorShape(r, 400, "BAD_REQUEST", path);
                assertThat(details(r)).containsExactly("X-Account-Id=账户 id 必须是整数");
            }
            assertErrorShape(getAs("99", path), 404, "ACCOUNT_NOT_FOUND", path);
        }
    }

    // =========================================================================================== not account-scoped

    @Test
    @DisplayName("Q218/规则 6：行情立即刷新、手动覆盖 mark / 标的价（设置与清除）、行情状态、账户（列表/新增/改名）、交易日历、健康检查带 ALL 照常；覆盖 mark 同时影响两个账户的同一合约")
    void nonAccountEndpointsWorkWithAll() {
        scenarioS();
        accountId = ALL;
        assertThat(post(REFRESH, null).status()).isEqualTo(200);
        assertThat(get(STATUS).status()).isEqualTo(200);
        Resp mark = exchange(HttpMethod.PUT, OVERRIDE_OPTIONS,
                optionOverrideBody("QRTV", "2026-12-18", "50", "PUT", "1.40"));
        assertThat(mark.status()).as("PUT mark override with ALL -> %s", mark.body()).isEqualTo(200);
        Resp px = exchange(HttpMethod.PUT, OVERRIDE_UNDERLYINGS + "IXQZ", "{\"price\":\"62.00\"}");
        assertThat(px.status()).as("PUT underlying override with ALL -> %s", px.body()).isEqualTo(200);
        assertThat(get("/api/accounts").status()).isEqualTo(200);
        assertThat(get("/api/trading-calendar/status").status()).isEqualTo(200);
        assertThat(get("/api/health").status()).isEqualTo(200);
        accountId = A;

        // the manual mark applies to both accounts' rows of the contract (one quote for the whole system)
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        assertThat(val(optRowOf(all, 1, L_A1))).isEqualTo("-140.00 -21.30 -17.94 OK null");
        assertThat(val(optRowOf(all, 2, L_B3))).isEqualTo("-140.00 -20.00 -16.67 OK null");
        assertThat(optRowOf(all, 1, L_A1).get("optionQuote").get("manual").asBoolean()).isTrue();
        assertThat(optRowOf(all, 2, L_B3).get("optionQuote").get("manual").asBoolean()).isTrue();
        JsonNode stock = stockRowOf(okAs(ALL, STOCK_POSITIONS), 1, "IXQZ");
        assertThat(stock.get("marketValue").asText()).isEqualTo("6200.00");
        assertThat(okAs(ALL, UNREALIZED).get("option").get("unrealizedPnl").asText()).isEqualTo("-41.95");

        accountId = ALL;
        assertThat(exchange(HttpMethod.DELETE,
                OVERRIDE_OPTIONS + "?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT", null).status())
                .isEqualTo(204);
        assertThat(exchange(HttpMethod.DELETE, OVERRIDE_UNDERLYINGS + "IXQZ", null).status()).isEqualTo(204);
        // account management is not account-scoped either (规则 6): create / rename with ALL
        Resp created = post("/api/accounts", "{\"name\":\"丙账户\",\"brokerNote\":null}");
        assertThat(created.status()).as("create account with ALL -> %s", created.body()).isEqualTo(201);
        String c = created.body().get("id").asText();
        assertThat(exchange(HttpMethod.PUT, "/api/accounts/" + c, "{\"name\":\"丙账户2\",\"brokerNote\":null}")
                .status()).isEqualTo(200);
        accountId = A;
        assertThat(val(optRowOf(okAs(ALL, OPTION_POSITIONS), 1, L_A1))).isEqualTo("-125.00 -6.30 -5.31 OK null");
    }

    // =========================================================================================== single account

    @Test
    @DisplayName("单账户响应只多了新字段：期权/股票行 accountId+accountName=本账户；cash/summary.accounts 只有本账户且等于顶层；alerts 无休市时为空；数字与场景一致")
    void singleAccountResponsesOnlyGainNewFields() {
        scenarioS();
        for (String acc : List.of(A, B)) {
            String name = acc.equals(A) ? A_NAME : B_NAME;
            for (String path : List.of(OPTION_POSITIONS, STOCK_POSITIONS)) {
                for (JsonNode p : okAs(acc, path).get("positions")) {
                    assertThat(p.get("accountId").asLong()).isEqualTo(Long.parseLong(acc));
                    assertThat(p.get("accountName").asText()).isEqualTo(name);
                }
            }
            JsonNode cs = okAs(acc, CASH_SUMMARY);
            assertThat(fieldNames(cs)).containsExactlyInAnyOrder("totalCash", "frozenCash", "availableCash",
                    "accounts");
            assertThat(cs.get("accounts").size()).isEqualTo(1);
            JsonNode row = cs.get("accounts").get(0);
            assertThat(row.get("accountId").asLong()).isEqualTo(Long.parseLong(acc));
            assertThat(row.get("accountName").asText()).isEqualTo(name);
            for (String f : List.of("totalCash", "frozenCash", "availableCash")) {
                assertThat(row.get(f)).isEqualTo(cs.get(f));
            }
            assertThat(okAs(acc, ALERTS)).isEqualTo(parse("{\"alerts\": []}"));
        }
        assertThat(okAs(ALL, ALERTS)).isEqualTo(parse("{\"alerts\": []}"));
        // single-account unrealized summary unchanged (FEAT-017 numbers of each account)
        JsonNode ua = okAs(A, UNREALIZED);
        assertThat(ua.get("option").get("unrealizedPnl").asText()).isEqualTo("-6.95");
        assertThat(ua.get("total").get("marketValue").asText()).isEqualTo("5900.00");
        JsonNode ub = okAs(B, UNREALIZED);
        assertThat(ub.get("option").get("unrealizedPnl").asText()).isEqualTo("-5.00");
        assertThat(ub.get("option").get("expiredPendingCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("FEAT-043 调整到期日（单账户）响应的 position 也带 accountId / accountName，并与持仓行一致")
    void adjustmentResponseCarriesAccount() {
        scenarioData();
        setNow("2026-11-23T09:55");
        injectDec18Closure();
        pull();
        accountId = B;
        ObjectNode adjust = json.createObjectNode();
        adjust.put("symbol", "QRTV");
        adjust.put("expirationDate", "2026-12-18");
        adjust.put("strike", "50");
        adjust.put("optionType", "PUT");
        adjust.put("newExpirationDate", "2026-12-17");
        adjust.putNull("note");
        Resp r = post(TRADES + "/expiration-adjustments", adjust.toString());
        accountId = A;
        assertThat(r.status()).as("adjust -> %s", r.body()).isEqualTo(200);
        JsonNode pos = r.body().get("position");
        assertThat(pos.get("accountId").asLong()).isEqualTo(2);
        assertThat(pos.get("accountName").asText()).isEqualTo(B_NAME);
        assertThat(pos).isEqualTo(optRowOf(okAs(B, OPTION_POSITIONS), 2, "QRTV 2026-12-17 50 P"));
        // after the adjustment only 甲 is affected; ALL alert lists only 甲账户
        JsonNode alert = okAs(ALL, ALERTS).get("alerts").get(0);
        assertThat(alert.get("affectedPositionCount").asInt()).isEqualTo(1);
        assertThat(alert.get("byAccount").size()).isEqualTo(1);
        assertThat(alert.get("byAccount").get(0).get("accountName").asText()).isEqualTo(A_NAME);
    }

    // =========================================================================================== boundaries

    @Test
    @DisplayName("边界 新增账户：ALL 立即包含（cash/summary 多一行 0.00），合计、首页、持仓、盈亏汇总不变")
    void newEmptyAccountIncluded() {
        scenarioS();
        JsonNode dashBefore = okAs(ALL, DASHBOARD);
        JsonNode posBefore = okAs(ALL, OPTION_POSITIONS);
        JsonNode pnlBefore = okAs(ALL, PNL);
        JsonNode unrBefore = okAs(ALL, UNREALIZED);
        String c = createAccount("丙账户");
        JsonNode cs = okAs(ALL, CASH_SUMMARY);
        assertThat(cs.get("totalCash").asText()).isEqualTo("70473.05");
        assertThat(cs.get("frozenCash").asText()).isEqualTo("14500.00");
        assertThat(cs.get("availableCash").asText()).isEqualTo("55973.05");
        assertThat(cs.get("accounts").size()).isEqualTo(3);
        assertThat(cs.get("accounts").get(2)).isEqualTo(parse("{\"accountId\": " + c
                + ", \"accountName\": \"丙账户\", \"totalCash\": \"0.00\", \"frozenCash\": \"0.00\","
                + " \"availableCash\": \"0.00\"}"));
        assertThat(okAs(ALL, DASHBOARD)).isEqualTo(dashBefore);
        assertThat(okAs(ALL, OPTION_POSITIONS)).isEqualTo(posBefore);
        assertThat(okAs(ALL, PNL)).isEqualTo(pnlBefore);
        assertThat(okAs(ALL, UNREALIZED)).isEqualTo(unrBefore);
        assertThat(okAs(ALL, ALERTS)).isEqualTo(parse("{\"alerts\": []}"));
    }

    @Test
    @DisplayName("边界 同一标的股票在两个账户：分两行（按 symbol、再按 accountId），个数按不同标的为 1（Q227）；市值/未实现相加；乙卖出 10 股 +100.00 合并进 IXQZ 行与 11 月")
    void sameStockInBothAccounts() {
        scenarioData();
        accountId = B;
        transferInId("IXQZ", 50, "3000.00", "2026-11-19");
        sellSharesId("IXQZ", 10, "700.00", "2026-11-20T11:00");
        accountId = A;
        pull();

        JsonNode st = okAs(ALL, STOCK_POSITIONS);
        assertThat(stockOrder(st)).containsExactly("1 甲账户 IXQZ 100", "2 乙账户 IXQZ 40");
        assertThat(st.get("positionCount").asInt()).as("Q227: distinct symbols across accounts").isEqualTo(1);
        JsonNode b = stockRowOf(st, 2, "IXQZ");
        assertThat(b.get("totalCost").asText()).isEqualTo("2400.00");
        assertThat(b.get("coveredShares").asInt()).isZero();
        assertThat(b.get("availableShares").asInt()).isEqualTo(40);
        assertThat(val(b)).isEqualTo("2444.00 44.00 1.83 OK null");
        assertThat(okAs(ALL, DASHBOARD).get("stockPositions").get("positionCount").asInt()).as("Q227").isEqualTo(1);
        assertThat(okAs(B, STOCK_POSITIONS).get("positionCount").asInt()).as("single account unchanged").isEqualTo(1);
        JsonNode u = okAs(ALL, UNREALIZED);
        assertThat(u.get("stock").get("marketValue").asText()).isEqualTo("8554.00");
        assertThat(u.get("stock").get("unrealizedPnl").asText()).isEqualTo("274.00");
        assertThat(u.get("stock").get("includedCount").asInt()).isEqualTo(2);
        assertThat(u.get("total").get("unrealizedPnl").asText()).isEqualTo("262.05");
        assertAllRowsEqualSingleRows(STOCK_POSITIONS, "symbol", List.of(A, B));

        JsonNode cs = okAs(ALL, CASH_SUMMARY);
        assertThat(cs.get("totalCash").asText()).isEqualTo("71173.05");
        assertThat(cs.get("availableCash").asText()).isEqualTo("56673.05");

        JsonNode pnl = okAs(ALL, PNL);
        assertThat(pnl.get("closedCount").asInt()).isEqualTo(3);
        assertThat(pnl.get("realizedPnlTotal").asText()).isEqualTo("190.00");
        assertThat(pnl.get("option")).isEqualTo(parse("{\"closedCount\": 2, \"realizedPnl\": \"90.00\"}"));
        assertThat(pnl.get("stock")).isEqualTo(parse("{\"closedCount\": 1, \"realizedPnl\": \"100.00\"}"));
        assertThat(pnl.get("bySymbol")).isEqualTo(parse("""
                [
                  { "symbol": "IXQZ", "closedCount": 1, "realizedPnl": "100.00",
                    "option": { "closedCount": 0, "realizedPnl": "0.00" }, "stock": { "closedCount": 1, "realizedPnl": "100.00" } },
                  { "symbol": "QRTV", "closedCount": 2, "realizedPnl": "90.00",
                    "option": { "closedCount": 2, "realizedPnl": "90.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } }
                ]"""));
        assertThat(pnl.get("byMonth").get(0)).isEqualTo(parse("""
                { "month": "2026-11", "closedCount": 2, "realizedPnl": "160.00",
                  "option": { "closedCount": 1, "realizedPnl": "60.00" }, "stock": { "closedCount": 1, "realizedPnl": "100.00" } }"""));
        JsonNode d = okAs(ALL, DASHBOARD);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("190.00");
        assertThat(d.get("currentMonthRealizedPnl").get("closedCount").asInt()).isEqualTo(2);
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("160.00");
    }

    @Test
    @DisplayName("边界 同一合约一空一多：丙账户 BTO QRTV 12-18 50P 130.00 → 第 5 行（同序按 accountId），LONG、冻结 0、市值 +125.00、未实现 −5.00；合计直接相加，无净额抵消")
    void longAndShortSameContractInDifferentAccounts() {
        scenarioS();
        String c = createAccount("丙账户");
        accountId = c;
        depositId("1000.00", "2026-11-16T10:00");
        open("BTO", "QRTV", "2026-12-18", "50", "PUT", "130.00", "2026-11-20T11:00");
        accountId = A;

        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        assertThat(optionOrder(all)).containsExactly("2 乙账户 " + L_B4, "1 甲账户 " + L_A4, "1 甲账户 " + L_A1,
                "2 乙账户 " + L_B3, c + " 丙账户 " + L_A1);
        JsonNode lng = optRowOf(all, Long.parseLong(c), L_A1);
        assertThat(lng.get("direction").asText()).isEqualTo("LONG");
        assertThat(lng.get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(val(lng)).isEqualTo("125.00 -5.00 -3.85 OK null");
        assertThat(all.get("positionCount").asInt()).isEqualTo(5);
        assertThat(all.get("frozenTotal").asText()).isEqualTo("14500.00");
        JsonNode u = okAs(ALL, UNREALIZED);
        assertThat(u.get("option").get("marketValue").asText()).isEqualTo("-210.00");
        assertThat(u.get("option").get("unrealizedPnl").asText()).isEqualTo("-16.95");
        assertThat(u.get("option").get("includedCount").asInt()).isEqualTo(4);
        JsonNode cs = okAs(ALL, CASH_SUMMARY);
        assertThat(cs.get("accounts").get(2).get("totalCash").asText()).isEqualTo("870.00");
        assertThat(cs.get("totalCash").asText()).isEqualTo("71343.05");
        assertThat(okAs(ALL, DASHBOARD).get("positions").get("positionCount").asInt()).isEqualTo(5);
        // 甲 / 乙 rows untouched by 丙's long (Q53: no cross-account netting)
        assertThat(val(optRowOf(all, 1, L_A1))).isEqualTo("-125.00 -6.30 -5.31 OK null");
        assertThat(optRowOf(all, 1, L_A1).get("frozenCash").asText()).isEqualTo("5000.00");
    }

    @Test
    @DisplayName("边界 只有一个账户：ALL 仍有效，结果与单账户相同（带账户字段），accounts 一行")
    void onlyOneAccount() {
        setNow("2026-11-23T09:50");
        accountId = A;
        depositId("10000.00", "2026-11-16T10:00");
        open("STO", "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-20T10:00");
        sources().cboe("QRTV", qrtvChain()).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        for (String path : ALL_READS) {
            assertThat(okAs(ALL, path)).as("%s ALL = single when only one account exists", path)
                    .isEqualTo(okAs(A, path));
        }
        JsonNode cs = okAs(ALL, CASH_SUMMARY);
        assertThat(cs.get("accounts").size()).isEqualTo(1);
        assertThat(cs.get("accounts").get(0).get("accountName").asText()).isEqualTo("默认账户");
        assertThat(okAs(ALL, OPTION_POSITIONS).get("positions").get(0).get("accountName").asText())
                .isEqualTo("默认账户");
    }

    @Test
    @DisplayName("边界 改名：ALL 行与资金汇总立即显示新名称（请求时的当前名称）")
    void renameReflectedImmediately() {
        scenarioS();
        renameAccount(B, "乙新名");
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        assertThat(optRowOf(all, 2, L_B3).get("accountName").asText()).isEqualTo("乙新名");
        assertThat(optRowOf(all, 2, L_B4).get("accountName").asText()).isEqualTo("乙新名");
        assertThat(okAs(ALL, CASH_SUMMARY).get("accounts").get(1).get("accountName").asText()).isEqualTo("乙新名");
        assertThat(optRowOf(all, 1, L_A1).get("accountName").asText()).isEqualTo(A_NAME);
    }

    @Test
    @DisplayName("边界 临时休市只影响一个账户：ALL message 仍带括号（乙账户 1 个），byAccount 只有乙账户（契约需求问题 4）")
    void alertAffectingOneAccount() {
        scenarioData();
        accountId = A;
        closeId("BTC", "QRTV", "2026-12-18", "50", "PUT", "100.00", "2026-11-20T15:00");
        setNow("2026-11-23T09:55");
        injectDec18Closure();
        pull();
        assertThat(okAs(ALL, ALERTS)).isEqualTo(parse("""
                {
                  "alerts": [
                    {
                      "date": "2026-12-18",
                      "eventName": "Test Closure",
                      "affectedPositionCount": 1,
                      "byAccount": [ { "accountId": 2, "accountName": "乙账户", "affectedPositionCount": 1 } ],
                      "message": "2026-12-18 临时休市（Test Closure），你有 1 个持仓在该日到期（乙账户 1 个），期权通常改为前一个交易日到期，请核对并调整到期日"
                    }
                  ]
                }"""));
        assertThat(okAs(A, ALERTS)).isEqualTo(parse("{\"alerts\": []}"));
    }

    @Test
    @DisplayName("边界 已过期未处理在 ALL 下照常：B4 仍冻结 4,500.00、不计入未实现；乙账户作废 B4 后 ALL 立即少一行、冻结 10,000.00、已实现 +150.00（3 笔）")
    void expiredRowAndProcessingInSingleAccount() {
        scenarioS();
        accountId = B;
        Resp r = post(TRADES + "/expirations", contract("QRTV", "2026-11-20", "45", "PUT", 1).toString());
        accountId = A;
        assertThat(r.status()).as("expire B4 in 乙 -> %s", r.body()).isEqualTo(201);
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        assertThat(optionOrder(all)).containsExactly("1 甲账户 " + L_A4, "1 甲账户 " + L_A1, "2 乙账户 " + L_B3);
        assertThat(all.get("frozenTotal").asText()).isEqualTo("10000.00");
        JsonNode d = okAs(ALL, DASHBOARD);
        assertThat(d.get("positions").get("expiredUnprocessedCount").asInt()).isZero();
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(3);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("150.00");
        JsonNode u = okAs(ALL, UNREALIZED);
        assertThat(u.get("excludedLabel").isNull()).isTrue();
        assertThat(u.get("total").get("unrealizedPnl").asText()).isEqualTo("218.05");
    }
}
