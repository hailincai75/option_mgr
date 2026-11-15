package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-046 (contract docs/api/FEAT-046.md): merged pagination for every list / scope / filter
 * (page stitching), ALL rows = single-account rows (Q53, Q228), validation errors identical to single account,
 * header variants, rename, only one account, records created by expire / assign, edits visible immediately, and the
 * account fields on single-account and write responses.
 */
@DisplayName("FEAT-046 全部账户 4 个列表：分页、校验、边界（系统测试）")
class Feat046AllAccountsListsBoundarySystemTest extends Feat046AllAccountsListsSystemTestBase {

    // =========================================================================================== pagination

    @Test
    @DisplayName("规则 5 分页：4 个列表在 ALL / 甲 / 乙 下，size 1–3 逐页拼接 = 不分页结果（不重复不遗漏），totalItems / totalPages 为合并后的，超过最后一页 []")
    void pagesStitchWithoutFilters() {
        scenarioL();
        for (String path : LISTS) {
            for (String scope : List.of(ALL, A, B)) {
                assertPagesStitch(scope, path, "");
            }
        }
    }

    @Test
    @DisplayName("规则 5 分页 + 筛选：带筛选的 ALL 列表同样逐页拼接一致；已平仓每页 filteredCount / 合计都是全部行的")
    void pagesStitchWithFilters() {
        scenarioL();
        for (String query : List.of("symbol=QRTV", "action=STO", "startDate=2026-11-01&endDate=2026-11-30",
                "symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT")) {
            assertPagesStitch(ALL, TRADES, query);
        }
        for (String query : List.of("symbol=QRTV", "type=BUY,SELL", "type=TRANSFER_IN")) {
            assertPagesStitch(ALL, STOCK_TRADES, query);
        }
        for (String query : List.of("type=OPTION", "type=STOCK", "startDate=2026-11-01&endDate=2026-11-30",
                "symbol=QRTV")) {
            assertPagesStitch(ALL, CLOSED, query);
        }
    }

    @Test
    @DisplayName("规则 5 默认分页参数不变：不带 page/size → page 1、size 20；没有符合条件的记录 → items []、totalItems 0、totalPages 0")
    void defaultsAndEmptyResult() {
        scenarioL();
        for (String path : LISTS) {
            JsonNode d = okAs(ALL, path);
            assertThat(d.get("page").asInt()).as("%s default page", path).isEqualTo(1);
            assertThat(d.get("size").asInt()).as("%s default size", path).isEqualTo(20);
        }
        for (String path : List.of(TRADES, STOCK_TRADES, CLOSED)) {
            JsonNode none = okAs(ALL, path + "?symbol=ZZZQ");
            assertThat(none.get("items").size()).isZero();
            assertThat(none.get("totalItems").asInt()).isZero();
            assertThat(none.get("totalPages").asInt()).as("%s 0 rows -> 0 pages", path).isZero();
        }
        JsonNode closedNone = okAs(ALL, CLOSED + "?symbol=ZZZQ");
        assertThat(closedNone.get("filteredCount").asInt()).isZero();
        assertThat(closedNone.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
    }

    // =========================================================================================== Q53 / Q228

    @Test
    @DisplayName("Q53 / Q228：4 个列表 ALL 的每一行（全部字段，含 balanceAfter、coveredLots、soldLots、hasAuditLog）= 该账户单独读到的同一行；ALL = 两个账户的并集")
    void allRowsEqualSingleRows() {
        scenarioL();
        for (String path : LISTS) {
            assertAllIsUnionOfSingles(path, "", List.of(A, B));
        }
        // the merged ledger is NOT a running balance across accounts (Q228): adjacent rows of different accounts
        JsonNode ledger = fullList(ALL, LEDGER);
        JsonNode r10 = ledger.get("items").get(2); // #11 甲
        JsonNode r9 = ledger.get("items").get(3); // #10 乙
        assertThat(acc(r10)).isEqualTo("1 甲账户");
        assertThat(acc(r9)).isEqualTo("2 乙账户");
        assertThat(money(r10.get("balanceAfter")))
                .isNotEqualByComparingTo(money(r9.get("balanceAfter")).add(money(r10.get("amount"))));
    }

    @Test
    @DisplayName("单账户响应只多 accountId / accountName（值为该账户）；写接口返回的交易、股票记录也带账户字段")
    void accountFieldsOnSingleAndWriteResponses() {
        scenarioL();
        for (String path : LISTS) {
            for (String a : List.of(A, B)) {
                for (JsonNode r : okAs(a, path + "?size=100").get("items")) {
                    assertThat(r.get("accountId").asLong()).as("%s row of %s", path, a).isEqualTo(Long.parseLong(a));
                    assertThat(r.get("accountName").asText()).isEqualTo(a.equals(A) ? A_NAME : B_NAME);
                }
            }
        }
        accountId = B;
        Resp put = editOpeningNote(L.get("#10"), "120.00", "2026-11-16T10:00", "同价位二");
        assertThat(put.status()).isEqualTo(200);
        assertThat(acc(put.body())).isEqualTo("2 乙账户");
        Resp getOne = get(STOCK_TRADES + "/" + L.get("#13"));
        assertThat(getOne.status()).isEqualTo(200);
        assertThat(acc(getOne.body())).isEqualTo("2 乙账户");
        accountId = A;
        Resp getTrade = get(TRADES + "/" + L.get("#11"));
        assertThat(acc(getTrade.body())).isEqualTo("1 甲账户");
        // the GET /{id} body equals the list row (contract: "与列表项保持完全相同")
        JsonNode row = null;
        for (JsonNode r : okAs(A, TRADES).get("items")) {
            if (r.get("id").asLong() == L.get("#11")) {
                row = r;
            }
        }
        assertThat(getTrade.body()).isEqualTo(row);
    }

    // =========================================================================================== validation

    @Test
    @DisplayName("规则 4：ALL 下参数错误与单账户完全相同（状态、code、message、details、path）")
    void validationSameAsSingleAccount() {
        scenarioL();
        List<String> bad = List.of(
                TRADES + "?strike=abc", TRADES + "?startDate=2026-11-30&endDate=2026-11-01", TRADES + "?page=0",
                TRADES + "?size=101", TRADES + "?action=FOO", TRADES + "?symbol=$QRTV", TRADES + "?optionType=X",
                STOCK_TRADES + "?type=FOO", STOCK_TRADES + "?size=0", STOCK_TRADES + "?startDate=2026-02-30",
                CLOSED + "?type=stock", CLOSED + "?type=OPTION,STOCK", CLOSED + "?action=STO",
                CLOSED + "?startDate=x", CLOSED + "?startDate=2026-11-30&endDate=2026-11-01",
                LEDGER + "?size=0", LEDGER + "?page=abc", LEDGER + "?size=101");
        for (String path : bad) {
            Resp all = getAs(ALL, path);
            Resp single = getAs(A, path);
            assertThat(single.status()).as("single %s is an error", path).isBetween(400, 499);
            assertThat(all.status()).as("ALL %s status", path).isEqualTo(single.status());
            assertThat(errorWithoutTimestamp(all)).as("ALL %s error = single-account error", path)
                    .isEqualTo(errorWithoutTimestamp(single));
        }
    }

    @Test
    @DisplayName("请求头：4 个列表 'all' / ' ALL ' 与 ALL 结果相同；缺失 → ACCOUNT_REQUIRED；'ALLX' → BAD_REQUEST；'99' → ACCOUNT_NOT_FOUND")
    void headerVariants() {
        scenarioL();
        for (String path : LISTS) {
            JsonNode upper = okAs(ALL, path);
            assertThat(okAs("all", path)).as("%s lower case", path).isEqualTo(upper);
            assertThat(okAs(" ALL ", path)).as("%s trimmed", path).isEqualTo(upper);
            assertErrorShape(getAs(null, path), 400, "ACCOUNT_REQUIRED", path);
            assertErrorShape(getAs("ALLX", path), 400, "BAD_REQUEST", path);
            assertErrorShape(getAs("99", path), 404, "ACCOUNT_NOT_FOUND", path);
        }
    }

    // =========================================================================================== boundaries

    @Test
    @DisplayName("边界 改名：4 个列表 ALL 行的 accountName 立即为新名称（请求时的当前名称）")
    void renameReflected() {
        scenarioL();
        renameAccount(B, "乙新名");
        for (String path : LISTS) {
            for (JsonNode r : okAs(ALL, path + "?size=100").get("items")) {
                assertThat(r.get("accountName").asText()).as("%s %s", path, rowKey(path, r))
                        .isEqualTo(r.get("accountId").asLong() == 2 ? "乙新名" : A_NAME);
            }
        }
    }

    @Test
    @DisplayName("边界 只有一个账户：4 个列表 ALL = 单账户（含账户字段）")
    void onlyOneAccount() {
        setNow("2026-11-23T10:00");
        accountId = A;
        depositId("10000.00", "2026-11-01T10:00");
        open("STO", "QRTV", "2026-12-18", "48", "PUT", "100.00", "2026-11-02T10:00");
        closeId("BTC", "QRTV", "2026-12-18", "48", "PUT", "40.00", "2026-11-10T10:00");
        buySharesId("QRTV", 10, "480.00", "2026-11-17T10:00");
        sellSharesId("QRTV", 10, "490.00", "2026-11-18T10:00");
        for (String path : LISTS) {
            JsonNode all = okAs(ALL, path + "?size=100");
            assertThat(all).as("%s ALL = single when only one account exists", path).isEqualTo(okAs(A, path + "?size=100"));
            assertThat(all.get("items").get(0).get("accountName").asText()).isEqualTo("默认账户");
        }
        assertPnlMatchesClosed(ALL, null, null);
    }

    @Test
    @DisplayName("边界 在单账户修改 / 新增后，ALL 列表立即是最新数据（无缓存）")
    void singleAccountChangeVisibleInAll() {
        scenarioL();
        accountId = A;
        Resp put = editOpeningNote(L.get("#9"), "118.70", "2026-11-16T10:00", "甲改");
        assertThat(put.status()).isEqualTo(200);
        long dep = depositId("100.00", "2026-11-23T09:30");
        JsonNode trades = okAs(ALL, TRADES);
        JsonNode r9 = trades.get("items").get(2);
        assertThat(r9.get("id").asLong()).isEqualTo(L.get("#9"));
        assertThat(r9.get("note").asText()).isEqualTo("甲改");
        assertThat(r9.get("hasAuditLog").asBoolean()).isTrue();
        JsonNode ledger = okAs(ALL, LEDGER);
        assertThat(ledger.get("totalItems").asInt()).isEqualTo(13);
        JsonNode top = ledger.get("items").get(0);
        assertThat(top.get("sourceType").asText()).isEqualTo("DEPOSIT");
        assertThat(top.get("sourceId").asLong()).isEqualTo(dep);
        assertThat(acc(top)).isEqualTo("1 甲账户");
        assertThat(top.get("balanceAfter").asText()).isEqualTo("50363.05");
    }

    @Test
    @DisplayName("边界 到期作废 / 被指派行（乙 #7 被指派）：交易列表、股票交易（指派买入）、已平仓 ALL 照常出现、带乙账户；数字 = 单账户；盈亏汇总一致")
    void assignmentRowsUnderAll() {
        scenarioL();
        accountId = B;
        ObjectNode assign = json.createObjectNode();
        assign.put("symbol", "QRTV");
        assign.put("expirationDate", "2026-11-20");
        assign.put("strike", "45");
        assign.put("optionType", "PUT");
        assign.put("contracts", 1);
        assign.put("assignmentDate", "2026-11-20");
        assign.put("assignmentFee", "0.00");
        assign.putNull("note");
        Resp r = post(TRADES + "/assignments", assign.toString());
        assertThat(r.status()).as("assign #7 in 乙 -> %s", r.body()).isEqualTo(201);
        long asg = r.body().get("id").asLong();
        accountId = A;

        JsonNode trades = fullList(ALL, TRADES);
        JsonNode asgRow = null;
        for (JsonNode t : trades.get("items")) {
            if (t.get("id").asLong() == asg) {
                asgRow = t;
            }
        }
        assertThat(asgRow).isNotNull();
        assertThat(asgRow.get("action").asText()).isEqualTo("ASG");
        assertThat(acc(asgRow)).isEqualTo("2 乙账户");

        JsonNode stocks = fullList(ALL, STOCK_TRADES);
        boolean assignBuy = false;
        for (JsonNode s : stocks.get("items")) {
            if (s.get("type").asText().equals("ASSIGN_BUY")) {
                assignBuy = true;
                assertThat(acc(s)).isEqualTo("2 乙账户");
                assertThat(s.get("assignmentTradeId").asLong()).isEqualTo(asg);
            }
        }
        assertThat(assignBuy).as("ASSIGN_BUY row under ALL").isTrue();

        JsonNode closed = fullList(ALL, CLOSED);
        assertThat(closedKeys(closed)).contains("OPTION-" + asg);
        for (String path : LISTS) {
            assertAllIsUnionOfSingles(path, "", List.of(A, B));
        }
        assertPnlMatchesClosed(ALL, null, null);
        assertPnlMatchesClosed(ALL, "2026-11-01", "2026-11-30");
    }

    @Test
    @DisplayName("边界 到期作废（乙 #7 作废）：EXP 行在交易列表 / 已平仓 ALL 出现，释放冻结的流水行带乙账户与乙自己的余额")
    void expireRowsUnderAll() {
        scenarioL();
        accountId = B;
        ObjectNode exp = json.createObjectNode();
        exp.put("symbol", "QRTV");
        exp.put("expirationDate", "2026-11-20");
        exp.put("strike", "45");
        exp.put("optionType", "PUT");
        exp.put("contracts", 1);
        Resp r = post(TRADES + "/expirations", exp.toString());
        assertThat(r.status()).as("expire #7 in 乙 -> %s", r.body()).isEqualTo(201);
        long e = r.body().get("id").asLong();
        accountId = A;

        assertThat(ids(fullList(ALL, TRADES))).contains(e);
        assertThat(closedKeys(fullList(ALL, CLOSED))).contains("OPTION-" + e);
        boolean found = false;
        for (JsonNode l : fullList(ALL, LEDGER).get("items")) {
            if (l.get("sourceType").asText().equals("OPTION_EXPIRE")) {
                found = true;
                assertThat(acc(l)).isEqualTo("2 乙账户");
                assertThat(l.get("frozenChange").asText()).isEqualTo("-4500.00");
            }
        }
        assertThat(found).isTrue();
        for (String path : LISTS) {
            assertAllIsUnionOfSingles(path, "", List.of(A, B));
        }
        JsonNode cs = okAs(ALL, CASH_SUMMARY);
        assertThat(cs.get("frozenCash").asText()).isEqualTo("10000.00");
        assertPnlMatchesClosed(ALL, null, null);
    }

    @Test
    @DisplayName("写保护不变：4 个列表对应的写接口（POST 交易 / 股票 / 入金、DELETE 交易、PUT 流水）带 ALL → READ_ONLY，什么都不写")
    void writeGuardsUnchanged() {
        scenarioL();
        Map<String, Object> before = listsWorld();
        accountId = ALL;
        Resp a = post(TRADES, trade("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "90.00", "2026-11-23T09:40")
                .toString());
        Resp b = post(STOCK_TRADES, "{\"type\":\"BUY\",\"symbol\":\"QRTV\",\"shares\":1,\"amount\":\"48.00\","
                + "\"tradedAtLocal\":\"2026-11-23T09:40\",\"note\":null}");
        Resp c = exchange(org.springframework.http.HttpMethod.DELETE, TRADES + "/" + L.get("#4"), null);
        Resp d = exchange(org.springframework.http.HttpMethod.PUT, TRANSFERS + "/" + L.get("#1"),
                "{\"type\":\"DEPOSIT\",\"amount\":\"1.00\",\"occurredAtLocal\":\"2026-10-01T09:30\",\"remark\":null}");
        accountId = A;
        assertGuardError(a, READ_ONLY_CODE, TRADES);
        assertGuardError(b, READ_ONLY_CODE, STOCK_TRADES);
        assertGuardError(c, READ_ONLY_CODE, TRADES + "/" + L.get("#4"));
        assertGuardError(d, READ_ONLY_CODE, TRANSFERS + "/" + L.get("#1"));
        assertThat(listsWorld()).isEqualTo(before);
    }
}
