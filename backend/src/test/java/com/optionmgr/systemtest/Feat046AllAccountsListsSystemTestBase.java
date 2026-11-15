package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;

/**
 * Shared helpers of the QA system tests for FEAT-046 全部账户合并查看（二）(contract docs/api/FEAT-046.md): the 4 list GETs
 * {@code /api/option-trades}, {@code /api/stock-trades}, {@code /api/closed-trades}, {@code /api/cash/ledger} accept
 * {@code X-Account-Id: ALL}; rows carry {@code accountId} / {@code accountName}; pagination over the merged list;
 * per-row account balance in the ledger (Q228); the pnl-summary = closed-trades consistency restored under ALL.
 *
 * <p>Reuses the FEAT-035 QA base (random port — never 8080, in-memory H2, settable clock, reset before / after every
 * test, fake CBOE / Finnhub only; fictional account names and tickers).
 *
 * <p>Requirement scenario L (today 2026-11-23 Mon 10:00 New York; multiplier 100; entered in the order #1..#13 below,
 * the number is the entry order — so the system ids are those of the contract table: option trades #4→1 … #11→8,
 * ledger #1→1, #2→2, #4→3 … #13→12, stock records #3→1, #12→2, #13→3):
 * <pre>
 *  #1  甲 deposit 50,000.00                        2026-10-01 09:30
 *  #2  乙 deposit 20,000.00                        2026-10-01 09:30
 *  #3  甲 转入 IXQZ 100 shares, total cost 5,880.00 2026-10-01
 *  #4  乙 BTO 1 QRTV 2026-12-18 55C net 50.00      2026-10-15 10:00
 *  #5  乙 STC 1 QRTV 2026-12-18 55C net 80.00 (+30) 2026-10-20 10:00
 *  #6  甲 STO 1 QRTV 2026-12-18 48P net 100.00     2026-11-02 10:00
 *  #7  乙 STO 1 QRTV 2026-11-20 45P net 60.00      2026-11-02 10:00
 *  #8  甲 BTC 1 QRTV 2026-12-18 48P net 40.00 (+60) 2026-11-10 10:00
 *  #9  甲 STO 1 QRTV 2026-12-18 50P net 118.70     2026-11-16 10:00
 *  #10 乙 STO 1 QRTV 2026-12-18 50P net 120.00     2026-11-16 10:00
 *  #11 甲 STO 1 IXQZ 2026-11-27 65C net 84.35 (covered) 2026-11-16 10:30
 *  #12 乙 buy QRTV 100 shares, paid 4,800.00       2026-11-17 10:00
 *  #13 乙 sell QRTV 50 shares, got 2,450.00 (+50)  2026-11-18 10:00
 * </pre>
 * Then, in 乙, the note of #10 is changed to "同价位" (the only record with an audit log).
 */
abstract class Feat046AllAccountsListsSystemTestBase extends Feat035AllAccountsSystemTestBase {

    static final String CLOSED = "/api/closed-trades";
    static final String LEDGER = "/api/cash/ledger";

    /** The 4 list GETs that accept ALL since FEAT-046. */
    static final List<String> LISTS = List.of(TRADES, STOCK_TRADES, CLOSED, LEDGER);

    static final String C_55C = "QRTV 2026-12-18 55 C";
    static final String C_48P = "QRTV 2026-12-18 48 P";
    static final String C_45P = "QRTV 2026-11-20 45 P";
    static final String C_50P = "QRTV 2026-12-18 50 P";
    static final String C_65C = "IXQZ 2026-11-27 65 C";

    /** System id of each scenario record by its scenario number ("#4" → option trade id, "#3" → stock id …). */
    final Map<String, Long> L = new LinkedHashMap<>();

    // =========================================================================================== data

    long buySharesId(String symbol, int shares, String amount, String tradedAtLocal) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "BUY");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", tradedAtLocal);
        n.putNull("note");
        Resp r = post(STOCK_TRADES, n.toString());
        assertThat(r.status()).as("BUY %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** PUT /api/option-trades/{id} (opening trade) in the current API account with a new note. */
    Resp editOpeningNote(long id, String net, String tradedAtLocal, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", 1);
        n.put("netAmount", net);
        n.put("tradedAtLocal", tradedAtLocal);
        n.put("multiplier", 100);
        n.put("note", note);
        return exchange(HttpMethod.PUT, TRADES + "/" + id, n.toString());
    }

    /**
     * Scenario L, entered in order #1..#13 (accounts interleaved), then the #10 note change in 乙. Backend clock left at
     * 2026-11-23 10:00 New York; API account 甲 ("1").
     */
    void scenarioL() {
        setNow("2026-11-23T10:00");
        renameAccount(A, A_NAME);
        String b = createAccount(B_NAME);
        assertThat(b).as("乙账户 gets id 2 after the reset").isEqualTo(B);
        accountB = b;

        accountId = A;
        L.put("#1", depositId("50000.00", "2026-10-01T09:30"));
        accountId = B;
        L.put("#2", depositId("20000.00", "2026-10-01T09:30"));
        accountId = A;
        L.put("#3", transferInId("IXQZ", 100, "5880.00", "2026-10-01"));
        accountId = B;
        L.put("#4", open("BTO", "QRTV", "2026-12-18", "55", "CALL", "50.00", "2026-10-15T10:00"));
        L.put("#5", closeId("STC", "QRTV", "2026-12-18", "55", "CALL", "80.00", "2026-10-20T10:00"));
        accountId = A;
        L.put("#6", open("STO", "QRTV", "2026-12-18", "48", "PUT", "100.00", "2026-11-02T10:00"));
        accountId = B;
        L.put("#7", open("STO", "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-02T10:00"));
        accountId = A;
        L.put("#8", closeId("BTC", "QRTV", "2026-12-18", "48", "PUT", "40.00", "2026-11-10T10:00"));
        L.put("#9", open("STO", "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-16T10:00"));
        accountId = B;
        L.put("#10", open("STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-16T10:00"));
        accountId = A;
        L.put("#11", open("STO", "IXQZ", "2026-11-27", "65", "CALL", "84.35", "2026-11-16T10:30"));
        accountId = B;
        L.put("#12", buySharesId("QRTV", 100, "4800.00", "2026-11-17T10:00"));
        L.put("#13", sellSharesId("QRTV", 50, "2450.00", "2026-11-18T10:00"));

        Resp edit = editOpeningNote(L.get("#10"), "120.00", "2026-11-16T10:00", "同价位");
        assertThat(edit.status()).as("edit #10 note in 乙 -> %s", edit.body()).isEqualTo(200);
        accountId = A;

        // the contract's id table (from an empty database) — the exact-id assertions below depend on it
        assertThat(List.of(L.get("#4"), L.get("#5"), L.get("#6"), L.get("#7"), L.get("#8"), L.get("#9"),
                L.get("#10"), L.get("#11"))).as("option trade ids #4..#11").containsExactly(1L, 2L, 3L, 4L, 5L, 6L,
                7L, 8L);
        assertThat(List.of(L.get("#3"), L.get("#12"), L.get("#13"))).as("stock ids #3, #12, #13")
                .containsExactly(1L, 2L, 3L);
    }

    // =========================================================================================== reading

    /** {@code path} plus the query {@code a=b&c=d} (no leading "?"; empty = none). */
    static String q(String path, String query) {
        if (query == null || query.isEmpty()) {
            return path;
        }
        return path + (path.contains("?") ? "&" : "?") + query;
    }

    /** Two queries joined with "&" (either may be empty). */
    static String and(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return a + "&" + b;
    }

    /** All rows of a list (size 100 — scenario L is far below that), asserting it fits on one page. */
    JsonNode fullList(String account, String path, String query) {
        JsonNode body = okAs(account, q(path + "?size=100", query));
        assertThat(body.get("totalItems").asInt()).as("%s fits on one page of 100", path)
                .isEqualTo(body.get("items").size());
        return body;
    }

    JsonNode fullList(String account, String path) {
        return fullList(account, path, "");
    }

    static List<JsonNode> items(JsonNode page) {
        List<JsonNode> out = new ArrayList<>();
        page.get("items").forEach(out::add);
        return out;
    }

    static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asLong()));
        return out;
    }

    static List<Long> accounts(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("accountId").asLong()));
        return out;
    }

    /** "TYPE-id" of every closed row in response order. */
    static List<String> closedKeys(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("type").asText() + "-" + i.get("id").asLong()));
        return out;
    }

    /** "accountId accountName" of a row. */
    static String acc(JsonNode row) {
        return row.get("accountId").asLong() + " " + row.get("accountName").asText();
    }

    static String accName(long accountId) {
        return accountId == 1 ? A_NAME : B_NAME;
    }

    /** Identity of a row inside one list (closed rows: type + id, FEAT-040). */
    static String rowKey(String path, JsonNode row) {
        return path.equals(CLOSED) ? row.get("type").asText() + "-" + row.get("id").asLong()
                : String.valueOf(row.get("id").asLong());
    }

    /**
     * Q53 / contract "计算不变": the ALL list (with {@code query}) is exactly the union of the single-account lists, and
     * every ALL row equals (all fields, all values, incl. balanceAfter / coveredLots / soldLots / hasAuditLog) the same
     * row read with that account's own X-Account-Id. Within one account the relative order is unchanged. Returns the
     * ALL body.
     */
    JsonNode assertAllIsUnionOfSingles(String path, String query, List<String> accountIds) {
        JsonNode all = fullList(ALL, path, query);
        int total = 0;
        for (String a : accountIds) {
            JsonNode single = fullList(a, path, query);
            List<JsonNode> fromAll = new ArrayList<>();
            for (JsonNode r : all.get("items")) {
                if (r.get("accountId").asLong() == Long.parseLong(a)) {
                    fromAll.add(r);
                }
            }
            List<JsonNode> singleRows = items(single);
            for (JsonNode r : singleRows) {
                assertThat(r.get("accountId").asLong()).as("%s single-account row carries its own account id", path)
                        .isEqualTo(Long.parseLong(a));
            }
            assertThat(fromAll).as("%s%s: ALL rows of account %s = that account's own rows (same order, same values)",
                    path, query, a).containsExactlyElementsOf(singleRows);
            total += singleRows.size();
        }
        assertThat(all.get("items").size()).as("%s%s: ALL = union of single accounts", path, query).isEqualTo(total);
        assertThat(all.get("totalItems").asInt()).isEqualTo(total);
        return all;
    }

    /**
     * Merged pagination (rule 5): for {@code size} 1..3 the pages 1..totalPages concatenated = the unpaged list (no
     * duplicate, no gap); totalItems / totalPages on every page; page totalPages + 1 → items []; closed totals are the
     * same on every page (they cover all filtered rows, not the page).
     */
    void assertPagesStitch(String account, String path, String query) {
        JsonNode full = fullList(account, path, query);
        List<JsonNode> expected = items(full);
        int n = expected.size();
        for (int size = 1; size <= 3; size++) {
            int pages = (n + size - 1) / size;
            List<JsonNode> stitched = new ArrayList<>();
            for (int p = 1; p <= pages + 1; p++) {
                JsonNode page = okAs(account, q(path + "?page=" + p + "&size=" + size, query));
                assertThat(page.get("page").asInt()).isEqualTo(p);
                assertThat(page.get("size").asInt()).isEqualTo(size);
                assertThat(page.get("totalItems").asInt()).as("%s %s size %d page %d totalItems", account, path, size,
                        p).isEqualTo(n);
                assertThat(page.get("totalPages").asInt()).as("%s %s size %d totalPages", account, path, size)
                        .isEqualTo(pages);
                if (path.equals(CLOSED)) {
                    assertThat(page.get("filteredCount")).isEqualTo(full.get("filteredCount"));
                    assertThat(page.get("filteredRealizedPnlTotal")).isEqualTo(full.get("filteredRealizedPnlTotal"));
                }
                if (p == pages + 1) {
                    assertThat(page.get("items").size()).as("%s page beyond the last -> []", path).isZero();
                } else {
                    assertThat(page.get("items").size()).isEqualTo(Math.min(size, n - (p - 1) * size));
                    page.get("items").forEach(stitched::add);
                }
            }
            assertThat(stitched).as("%s %s%s size %d: pages stitched = unpaged list", account, path, query, size)
                    .containsExactlyElementsOf(expected);
        }
    }

    // =========================================================================================== consistency

    /** Sum of {count, total} of the closed list (no type) for a scope / query. */
    JsonNode closedTotals(String account, String query) {
        JsonNode c = okAs(account, q(CLOSED + "?size=1", query));
        ObjectNode o = json.createObjectNode();
        o.put("closedCount", c.get("filteredCount").asInt());
        o.put("realizedPnl", c.get("filteredRealizedPnlTotal").asText());
        return o;
    }

    ObjectNode countPnl(JsonNode n, String countField, String pnlField) {
        ObjectNode o = json.createObjectNode();
        o.put("closedCount", n.get(countField).asInt());
        o.put("realizedPnl", n.get(pnlField).asText());
        return o;
    }

    /**
     * Rule 6 / contract "一致性": for the scope and date range, pnl-summary total = closed-trades (no type) totals;
     * option / stock split = type=OPTION / type=STOCK totals; each bySymbol row = symbol=&lt;symbol&gt; totals; each byMonth
     * row = that month's date range (intersected with the range) totals.
     */
    void assertPnlMatchesClosed(String account, String startDate, String endDate) {
        String range = startDate == null ? "" : "startDate=" + startDate + "&endDate=" + endDate;
        JsonNode pnl = okAs(account, q(PNL, range));
        String what = "scope " + account + " range " + (range.isEmpty() ? "all" : range);
        assertThat(countPnl(pnl, "closedCount", "realizedPnlTotal")).as("%s: total = closed list", what)
                .isEqualTo(closedTotals(account, range));
        assertThat(pnl.get("option")).as("%s: option = closed type=OPTION", what)
                .isEqualTo(closedTotals(account, and("type=OPTION", range)));
        assertThat(pnl.get("stock")).as("%s: stock = closed type=STOCK", what)
                .isEqualTo(closedTotals(account, and("type=STOCK", range)));
        for (JsonNode s : pnl.get("bySymbol")) {
            String sq = and("symbol=" + s.get("symbol").asText(), range);
            assertThat(countPnl(s, "closedCount", "realizedPnl")).as("%s: bySymbol %s", what, s.get("symbol"))
                    .isEqualTo(closedTotals(account, sq));
            assertThat(s.get("option")).isEqualTo(closedTotals(account, sq + "&type=OPTION"));
            assertThat(s.get("stock")).isEqualTo(closedTotals(account, sq + "&type=STOCK"));
        }
        for (JsonNode m : pnl.get("byMonth")) {
            java.time.YearMonth ym = java.time.YearMonth.parse(m.get("month").asText());
            String from = ym.atDay(1).toString();
            String to = ym.atEndOfMonth().toString();
            if (startDate != null && startDate.compareTo(from) > 0) {
                from = startDate;
            }
            if (endDate != null && endDate.compareTo(to) < 0) {
                to = endDate;
            }
            String mq = "startDate=" + from + "&endDate=" + to;
            assertThat(countPnl(m, "closedCount", "realizedPnl")).as("%s: byMonth %s", what, m.get("month"))
                    .isEqualTo(closedTotals(account, mq));
            assertThat(m.get("option")).isEqualTo(closedTotals(account, mq + "&type=OPTION"));
            assertThat(m.get("stock")).isEqualTo(closedTotals(account, mq + "&type=STOCK"));
        }
    }

    // =========================================================================================== snapshots

    /** The 4 lists under ALL / 甲 / 乙 (unpaged), the cash summaries, pnl-summary and the audit logs of 甲 / 乙. */
    Map<String, Object> listsWorld() {
        Map<String, Object> w = new LinkedHashMap<>();
        for (String t : List.of("account", "cash_transfer", "cash_ledger_entry", "option_trade", "stock_trade",
                "audit_record")) {
            w.put(t, count(t));
        }
        for (String a : List.of(A, B, ALL)) {
            for (String p : LISTS) {
                w.put(a + " " + p, okAs(a, p + "?size=100"));
            }
            w.put(a + " " + CASH_SUMMARY, okAs(a, CASH_SUMMARY));
            w.put(a + " " + PNL, okAs(a, PNL));
        }
        for (String a : List.of(A, B)) {
            w.put(a + " audit", okAs(a, "/api/audit-log?size=100"));
        }
        return w;
    }

    /** The error body without the volatile timestamp (to compare ALL vs single-account errors). */
    static JsonNode errorWithoutTimestamp(Resp r) {
        assertThat(r.body()).as("error body").isNotNull();
        ObjectNode copy = r.body().deepCopy();
        copy.remove("timestamp");
        return copy;
    }

    static BigDecimal money(JsonNode n) {
        return new BigDecimal(n.asText());
    }
}
