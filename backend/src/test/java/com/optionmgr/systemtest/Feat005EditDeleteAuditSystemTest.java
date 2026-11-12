package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * FEAT-005 edit / delete of trades and transfers, plus the audit log, end-to-end over HTTP.
 * "Now" = 2026-11-11 10:00 New York. Every rejection must leave cash, ledger (incl. frozen changes), trades,
 * positions and the audit log untouched; after every success positions frozenTotal == cash frozenCash.
 */
class Feat005EditDeleteAuditSystemTest extends OptionTradeSystemTestBase {

    static final String CLOSES = "/api/option-trades/closes";
    static final String AUDIT = "/api/audit-log";

    long d1;
    long l1;
    long l2;
    long c1;

    @Override
    Instant now() {
        return Instant.parse("2026-11-11T15:00:00Z");
    }

    // ------------------------------------------------------------------ helpers

    long open(String action, int contracts, String symbol, String exp, String strike, String type, String net,
              String at, int multiplier) {
        return okTrade(trade(action, contracts, symbol, exp, strike, type, net, at, multiplier)).get("id").asLong();
    }

    long closeTrade(String action, int contracts, String symbol, String exp, String strike, String type, String net,
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
        Resp r = postRaw(CLOSES, n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("close %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
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

    Resp putTransfer(long id, String type, String amount, String at, String remark) {
        return exchange(HttpMethod.PUT, "/api/cash/transfers/" + id,
                transferBody(type, amount, at, remark).toString(), MediaType.APPLICATION_JSON);
    }

    Resp deleteTransfer(long id) {
        return exchange(HttpMethod.DELETE, "/api/cash/transfers/" + id, null, MediaType.APPLICATION_JSON);
    }

    JsonNode okEditTrade(long id, ObjectNode body) {
        Resp r = putTrade(id, body);
        assertThat(r.status()).as("PUT trade %d %s -> %s", id, body, r.body()).isEqualTo(200);
        assertFrozenConsistent();
        return r.body();
    }

    void ok(Resp r, int status) {
        assertThat(r.status()).as("-> %s", r.body()).isEqualTo(status);
        assertFrozenConsistent();
    }

    JsonNode positionsBody() {
        return get("/api/option-positions").body();
    }

    void assertFrozenConsistent() {
        assertThat(positionsBody().get("frozenTotal").asText()).isEqualTo(summary().get("frozenCash").asText());
    }

    record Full(State state, JsonNode positions, JsonNode ledger, JsonNode audit, JsonNode trades) {
    }

    Full full() {
        return new Full(state(), positionsBody(), ledger(1, 100), get(AUDIT + "?size=100").body(),
                get(TRADES + "?size=100").body());
    }

    /** Any write that must be rejected with a 422 code and leave everything untouched; returns the message. */
    String rejected(Resp r, Full before, String code, String path) {
        assertErrorShape(r, 422, code, path);
        assertThat(r.body().get("details")).isEmpty();
        assertThat(full()).as("nothing changed").isEqualTo(before);
        return r.body().get("message").asText();
    }

    String rejectedEdit(long id, ObjectNode body, String code) {
        Full before = full();
        return rejected(putTrade(id, body), before, code, TRADES + "/" + id);
    }

    String rejectedDelete(long id, String code) {
        Full before = full();
        return rejected(deleteTrade(id), before, code, TRADES + "/" + id);
    }

    JsonNode audit() {
        return get(AUDIT).body();
    }

    JsonNode lastAudit() {
        return audit().get("items").get(0);
    }

    List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.get("text").asText()));
        return out;
    }

    JsonNode position(String label) {
        for (JsonNode p : positionsBody().get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    static final String AAPL = "AAPL 2026-12-18 200 P";

    void scenarioA() {
        d1 = deposit("80000.00", "2026-10-26T10:00").get("id").asLong();
        l1 = open("STO", 2, "AAPL", "2026-12-18", "200", "PUT", "698.70", "2026-11-03T10:30", 100);
        l2 = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "360.00", "2026-11-04T11:00", 100);
        c1 = closeTrade("BTC", 2, "AAPL", "2026-12-18", "200", "PUT", "241.30", "2026-11-05T10:00");
        assertSummary("80817.40", "20000.00", "60817.40");
        assertThat(position(AAPL).get("contracts").asInt()).isEqualTo(1);
    }

    ObjectNode l2Body(String net) {
        return edit(1, net, "2026-11-04T11:00", 100, null);
    }

    ObjectNode c1Body(int contracts, String net, String at) {
        return edit(contracts, net, at, null, null);
    }

    // ------------------------------------------------------------------ AC 1, 2, 3

    @Test
    @DisplayName("AC1 改 L2 净额 360 → 365：汇总、流水原地更新（id、条数不变）、持仓、审计内容")
    void ac1_editOpenNet() {
        scenarioA();
        long ledgerId = getTrade(l2).get("ledgerEntryId").asLong();
        JsonNode t = okEditTrade(l2, l2Body("365.00"));
        assertThat(t.get("id").asLong()).isEqualTo(l2);
        assertThat(t.get("netAmount").asText()).isEqualTo("365.00");
        assertThat(t.get("cashChange").asText()).isEqualTo("365.00");
        assertThat(t.get("ledgerEntryId").asLong()).isEqualTo(ledgerId);
        assertThat(getTrade(l2)).isEqualTo(t);
        assertSummary("80822.40", "20000.00", "60822.40");
        assertThat(ledgerCount()).isEqualTo(4);
        JsonNode row = ledgerRowFor("OPTION_OPEN", l2);
        assertThat(row.get("id").asLong()).isEqualTo(ledgerId);
        assertThat(row.get("amount").asText()).isEqualTo("365.00");
        JsonNode p = position(AAPL);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("365.00");
        assertThat(p.get("averageNetPerContract").asText()).isEqualTo("365.00");

        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).isEqualTo(1);
        JsonNode r = a.get("items").get(0);
        assertThat(r.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(r.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(r.get("objectId").asLong()).isEqualTo(l2);
        assertThat(r.get("summary").asText()).isEqualTo("STO AAPL 2026-12-18 200 P");
        assertThat(Instant.parse(r.get("operatedAt").asText())).isEqualTo(now());
        assertThat(texts(r.get("changes"))).containsExactly("净额：360.00 → 365.00");
        JsonNode ch = r.get("changes").get(0);
        assertThat(ch.get("field").asText()).isEqualTo("netAmount");
        assertThat(ch.get("label").asText()).isEqualTo("净额");
        assertThat(r.get("sideEffects")).isEmpty();
        JsonNode before = r.get("before");
        assertThat(before.get("id").asLong()).isEqualTo(l2);
        assertThat(before.get("contractLabel").asText()).isEqualTo(AAPL);
        assertThat(before.get("action").asText()).isEqualTo("STO");
        assertThat(before.get("contracts").asInt()).isEqualTo(1);
        assertThat(before.get("multiplier").asInt()).isEqualTo(100);
        assertThat(before.get("netAmount").asText()).isEqualTo("360.00");
        assertThat(before.get("tradedAt").asText()).isEqualTo("2026-11-04T16:00:00Z");
        assertThat(before.get("tradedAtLocal").asText()).isEqualTo("2026-11-04T11:00:00");
        assertThat(before.get("note").isNull()).isTrue();
        assertThat(before.get("cashChange").asText()).isEqualTo("360.00");
        assertThat(before.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(r.get("after").get("netAmount").asText()).isEqualTo("365.00");
        assertThat(r.get("after").get("cashChange").asText()).isEqualTo("365.00");
    }

    @Test
    @DisplayName("AC2 改 C1 净额 241.30 → 250.00：盈亏 +448.70")
    void ac2_editCloseNet() {
        scenarioA();
        okEditTrade(c1, c1Body(2, "250.00", "2026-11-05T10:00"));
        assertSummary("80808.70", "20000.00", "60808.70");
        assertThat(getTrade(c1).get("realizedPnl").asText()).isEqualTo("448.70");
    }

    @Test
    @DisplayName("AC3 C1 张数 2 → 3：平 L1+L2、冻结 −60,000、盈亏 +817.40；改成 1 被拒且不写审计")
    void ac3_editCloseContracts() {
        scenarioA();
        assertThat(rejectedEdit(c1, c1Body(1, "241.30", "2026-11-05T10:00"), "CLOSE_NOT_WHOLE_LOTS"))
                .isEqualTo("必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张");
        assertThat(audit().get("totalItems").asInt()).isZero();

        JsonNode t = okEditTrade(c1, c1Body(3, "241.30", "2026-11-05T10:00"));
        assertThat(t.get("frozenChange").asText()).isEqualTo("-60000.00");
        assertThat(t.get("coveredLots")).hasSize(2);
        assertThat(t.get("realizedPnl").asText()).isEqualTo("817.40");
        assertSummary("80817.40", "0.00", "80817.40");
        assertThat(position(AAPL)).isNull();
        assertThat(ledgerRowFor("OPTION_CLOSE", c1).get("frozenChange").asText()).isEqualTo("-60000.00");
        assertThat(texts(lastAudit().get("changes")))
                .containsExactly("张数：2 → 3", "冻结变动：−40,000.00 → −60,000.00");
    }

    // ------------------------------------------------------------------ AC 4, 5

    @Test
    @DisplayName("AC4 L1 张数 2 → 3 让 C1 不是整笔批次被拒；2 → 1 成功，C1 改平 L1+L2")
    void ac4_editOpenContracts() {
        scenarioA();
        assertThat(rejectedEdit(l1, edit(3, "698.70", "2026-11-03T10:30", 100, null), "CLOSE_NOT_WHOLE_LOTS"))
                .isEqualTo("修改后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将不是整笔开仓（最早一笔是 3 张，可平 3 张或 4 张）");
        okEditTrade(l1, edit(1, "698.70", "2026-11-03T10:30", 100, null));
        JsonNode c = getTrade(c1);
        List<Long> covered = new ArrayList<>();
        c.get("coveredLots").forEach(x -> covered.add(x.get("tradeId").asLong()));
        assertThat(covered).containsExactly(l1, l2);
        assertThat(c.get("frozenChange").asText()).isEqualTo("-40000.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("817.40");
        assertSummary("80817.40", "0.00", "80817.40");
        assertThat(position(AAPL)).isNull();
        JsonNode a = lastAudit();
        assertThat(texts(a.get("changes"))).containsExactly("张数：2 → 1", "冻结变动：+40,000.00 → +20,000.00");
        assertThat(a.get("sideEffects")).isEmpty();
    }

    @Test
    @DisplayName("AC5 改时间：L1 挪到 C1 之后 → 超过持仓；C1 挪到 L1 之前 → 没有持仓；未来时间 → 字段错误")
    void ac5_editTimes() {
        scenarioA();
        assertThat(rejectedEdit(l1, edit(2, "698.70", "2026-11-06T10:00", 100, null), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("修改后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将超过当时持仓 1 张");
        assertThat(rejectedEdit(c1, c1Body(2, "241.30", "2026-11-03T10:00"), "NO_POSITION_TO_CLOSE"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时没有持仓，无法平仓");
        Full before = full();
        Resp future = putTrade(c1, c1Body(2, "241.30", "2026-11-11T10:01"));
        assertErrorShape(future, 400, "VALIDATION_ERROR", TRADES + "/" + c1);
        assertThat(detailFields(future)).containsExactly("tradedAtLocal");
        assertThat(future.body().get("details").get(0).get("message").asText()).isEqualTo("成交时间不能晚于当前时间");
        assertThat(full()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ AC 6, 7

    @Test
    @DisplayName("AC6 删 L1 被拒（被 C1 平掉）；删 L2 成功：汇总、流水少 1 条、404、审计 DELETE 内容")
    void ac6_deleteOpen() {
        scenarioA();
        assertThat(rejectedDelete(l1, "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("该开仓被 2026-11-05 10:00 的 BTC 2 张平掉（删除后那笔平仓将超过当时持仓 1 张），请先修改或删除那笔平仓");

        ok(deleteTrade(l2), 204);
        assertSummary("80457.40", "0.00", "80457.40");
        assertThat(position(AAPL)).isNull();
        List<Long> ids = new ArrayList<>();
        get(TRADES).body().get("items").forEach(i -> ids.add(i.get("id").asLong()));
        assertThat(ids).containsExactly(c1, l1);
        assertThat(ledgerCount()).isEqualTo(3);
        Resp gone = get(TRADES + "/" + l2);
        assertErrorShape(gone, 404, "NOT_FOUND", TRADES + "/" + l2);
        assertThat(gone.body().get("message").asText()).isEqualTo("Option trade " + l2 + " not found");
        assertErrorShape(putTrade(l2, l2Body("1.00")), 404, "NOT_FOUND", TRADES + "/" + l2);
        assertErrorShape(deleteTrade(l2), 404, "NOT_FOUND", TRADES + "/" + l2);

        JsonNode r = lastAudit();
        assertThat(r.get("operation").asText()).isEqualTo("DELETE");
        assertThat(r.get("objectId").asLong()).isEqualTo(l2);
        assertThat(r.get("after").isNull()).isTrue();
        assertThat(r.get("changes")).isEmpty();
        JsonNode b = r.get("before");
        assertThat(List.of(b.get("action").asText(), b.get("contracts").asText(), b.get("contractLabel").asText(),
                b.get("netAmount").asText(), b.get("tradedAtLocal").asText(), b.get("cashChange").asText(),
                b.get("frozenChange").asText()))
                .containsExactly("STO", "1", AAPL, "360.00", "2026-11-04T11:00:00", "360.00", "20000.00");
        // audit records survive the deletion and are filterable
        assertThat(get(AUDIT + "?objectType=OPTION_TRADE&objectId=" + l2).body().get("totalItems").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC7 删 C1：冻结恢复 → 81,058.70/60,000/21,058.70、3 张 2 批 可平 [2,3]")
    void ac7_deleteClose() {
        scenarioA();
        ok(deleteTrade(c1), 204);
        assertSummary("81058.70", "60000.00", "21058.70");
        JsonNode p = position(AAPL);
        assertThat(p.get("contracts").asInt()).isEqualTo(3);
        assertThat(p.get("lotCount").asInt()).isEqualTo(2);
        List<Integer> allowed = new ArrayList<>();
        p.get("allowedCloseContracts").forEach(x -> allowed.add(x.asInt()));
        assertThat(allowed).containsExactly(2, 3);
    }

    @Test
    @DisplayName("AC7 之后有出金 50,000：删 C1 被拒，删除后 11-06 10:00 可用 −28,941.30")
    void ac7_deleteCloseBreaksLaterWithdrawal() {
        scenarioA();
        withdraw("50000.00", "2026-11-06T10:00");
        assertSummary("30817.40", "20000.00", "10817.40");
        assertThat(rejectedDelete(c1, "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：删除后 2026-11-06 10:00 时可用现金将变为 −28,941.30");
    }

    // ------------------------------------------------------------------ AC 8, 9 (transfers)

    @Test
    @DisplayName("AC8 改 D1：70,000 成功；59,000 成功（L2 不重查 Q18）；58,900 被拒 −41.30；改到 11-04 12:00 被拒 −39,301.30")
    void ac8_editDeposit() {
        scenarioA();
        long ledgerId = ledgerRowFor("DEPOSIT", d1).get("id").asLong();
        ok(putTransfer(d1, "DEPOSIT", "70000.00", "2026-10-26T10:00", null), 200);
        assertSummary("70817.40", "20000.00", "50817.40");
        assertThat(ledgerRowFor("DEPOSIT", d1).get("id").asLong()).isEqualTo(ledgerId);
        assertThat(ledgerLines()).containsExactly(
                "2026-11-05T15:00:00Z OPTION_CLOSE -241.30 70817.40",
                "2026-11-04T16:00:00Z OPTION_OPEN 360.00 71058.70",
                "2026-11-03T15:30:00Z OPTION_OPEN 698.70 70698.70",
                "2026-10-26T14:00:00Z DEPOSIT 70000.00 70000.00");
        assertThat(texts(lastAudit().get("changes"))).containsExactly("金额：80,000.00 → 70,000.00");
        assertThat(lastAudit().get("summary").asText()).isEqualTo("入金 80,000.00");

        ok(putTransfer(d1, "DEPOSIT", "59000.00", "2026-10-26T10:00", null), 200);
        assertSummary("59817.40", "20000.00", "39817.40");

        Full before = full();
        assertThat(rejected(putTransfer(d1, "DEPOSIT", "58900.00", "2026-10-26T10:00", null), before,
                "CASH_INSUFFICIENT", "/api/cash/transfers/" + d1))
                .isEqualTo("可用现金不足：修改后 2026-11-04 11:00 时可用现金将变为 −41.30");
        assertThat(rejected(putTransfer(d1, "DEPOSIT", "59000.00", "2026-11-04T12:00", null), before,
                "CASH_INSUFFICIENT", "/api/cash/transfers/" + d1))
                .isEqualTo("可用现金不足：修改后 2026-11-03 10:30 时可用现金将变为 −39,301.30");
    }

    @Test
    @DisplayName("Q41（按当前实现，待用户确认）：D1 改为 59,000 后，只改 L2 备注/净额不重查 Q18 → 成功；改 L2 张数 → 按 Q18 拒绝")
    void q41_editedStoPutQ18OnlyWhenCollateralInputsChange() {
        scenarioA();
        ok(putTransfer(d1, "DEPOSIT", "59000.00", "2026-10-26T10:00", null), 200);
        // before L2 (11-04 11:00): available = 59,000 + 698.70 − 40,000 = 19,698.70 < 20,000 collateral
        okEditTrade(l2, edit(1, "360.00", "2026-11-04T11:00", 100, "只改备注"));
        okEditTrade(l2, edit(1, "361.00", "2026-11-04T11:00", 100, "只改备注"));
        assertThat(rejectedEdit(l2, edit(2, "361.00", "2026-11-04T11:00", 100, "只改备注"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足以担保卖出 put：需要 40,000.00，可用 19,698.70");
        // a time change also re-checks Q18 (same point in time but expressed with seconds → still a change? no:
        // moving it one minute later is a real change)
        assertThat(rejectedEdit(l2, edit(1, "361.00", "2026-11-04T11:01", 100, "只改备注"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足以担保卖出 put：需要 20,000.00，可用 19,698.70");
    }

    @Test
    @DisplayName("AC9 改类型 入金 → 出金；删除重复入金；场景 A 删 D1 被拒")
    void ac9_transferTypeAndDelete() {
        long t1 = deposit("1000.00", "2026-11-02T10:00").get("id").asLong();
        long t2 = deposit("500.00", "2026-11-03T10:00").get("id").asLong();
        long ledgerId = ledgerRowFor("DEPOSIT", t2).get("id").asLong();
        Resp r = putTransfer(t2, "WITHDRAWAL", "500.00", "2026-11-03T10:00", null);
        ok(r, 200);
        assertThat(r.body().get("type").asText()).isEqualTo("WITHDRAWAL");
        assertSummary("500.00", "0.00", "500.00");
        JsonNode row = ledgerRowFor("WITHDRAWAL", t2);
        assertThat(row.get("id").asLong()).isEqualTo(ledgerId);
        assertThat(row.get("amount").asText()).isEqualTo("-500.00");
        JsonNode a = lastAudit();
        assertThat(a.get("objectType").asText()).isEqualTo("CASH_TRANSFER");
        assertThat(a.get("objectId").asLong()).isEqualTo(t2);
        assertThat(texts(a.get("changes"))).containsExactly("类型：入金 → 出金");
        assertThat(a.get("before").get("type").asText()).isEqualTo("DEPOSIT");
        assertThat(a.get("after").get("type").asText()).isEqualTo("WITHDRAWAL");

        emptyLedger();
        deposit("1000.00", "2026-11-02T10:00");
        long dup = deposit("1000.00", "2026-11-02T10:00").get("id").asLong();
        ok(deleteTransfer(dup), 204);
        assertSummary("1000.00", "0.00", "1000.00");
        assertThat(ledgerCount()).isEqualTo(1);
        JsonNode del = lastAudit();
        assertThat(del.get("operation").asText()).isEqualTo("DELETE");
        assertThat(del.get("objectType").asText()).isEqualTo("CASH_TRANSFER");
        assertThat(del.get("summary").asText()).isEqualTo("入金 1,000.00");
        assertThat(del.get("after").isNull()).isTrue();
        Resp gone = deleteTransfer(dup);
        assertErrorShape(gone, 404, "NOT_FOUND", "/api/cash/transfers/" + dup);
        assertThat(gone.body().get("message").asText()).isEqualTo("Cash transfer " + dup + " not found");

        emptyLedger();
        scenarioA();
        Full before = full();
        assertThat(rejected(deleteTransfer(d1), before, "CASH_INSUFFICIENT", "/api/cash/transfers/" + d1))
                .isEqualTo("可用现金不足：删除后 2026-11-03 10:30 时可用现金将变为 −39,301.30");
        assertThat(t1).isPositive();
    }

    @Test
    @DisplayName("改小入金让之后的出金时点变负 → 拒绝；出金改为入金允许")
    void transferEditBreaksLaterWithdrawal() {
        long dep = deposit("1000.00", "2026-11-02T10:00").get("id").asLong();
        long wd = withdraw("900.00", "2026-11-05T10:00").get("id").asLong();
        Full before = full();
        assertThat(rejected(putTransfer(dep, "DEPOSIT", "800.00", "2026-11-02T10:00", null), before,
                "CASH_INSUFFICIENT", "/api/cash/transfers/" + dep))
                .isEqualTo("可用现金不足：修改后 2026-11-05 10:00 时可用现金将变为 −100.00");
        assertThat(rejected(putTransfer(dep, "WITHDRAWAL", "1000.00", "2026-11-02T10:00", null), before,
                "CASH_INSUFFICIENT", "/api/cash/transfers/" + dep)).contains("修改后");
        ok(putTransfer(wd, "DEPOSIT", "900.00", "2026-11-05T10:00", "冲正"), 200);
        assertSummary("1900.00", "0.00", "1900.00");
    }

    // ------------------------------------------------------------------ AC 10 (Q28/Q39/Q42)

    @Test
    @DisplayName("AC10 F1 张数 1 → 2：FC 改平 F1、冻结 −24.70 → −24.69（连带变化写入同一条审计）")
    void ac10_sideEffect() {
        deposit("100.00", "2026-10-26T10:00");
        long f1 = open("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", "2026-11-02T10:00", 1);
        long f2 = open("STO", 1, "F", "2026-12-18", "12.345", "PUT", "0.50", "2026-11-03T10:00", 1);
        long fc = closeTrade("BTC", 2, "F", "2026-12-18", "12.345", "PUT", "0.20", "2026-11-05T10:00");
        assertSummary("100.80", "0.00", "100.80");
        JsonNode t = okEditTrade(f1, edit(2, "0.50", "2026-11-02T10:00", 1, null));
        assertThat(t.get("frozenChange").asText()).isEqualTo("24.69");
        JsonNode c = getTrade(fc);
        assertThat(c.get("frozenChange").asText()).isEqualTo("-24.69");
        assertThat(c.get("coveredLots")).hasSize(1);
        assertThat(c.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(f1);
        assertThat(c.get("realizedPnl").asText()).isEqualTo("0.30");
        assertThat(ledgerRowFor("OPTION_CLOSE", fc).get("frozenChange").asText()).isEqualTo("-24.69");
        JsonNode p = position("F 2026-12-18 12.345 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("frozenCash").asText()).isEqualTo("12.35");
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(f2);
        assertSummary("100.80", "12.35", "88.45");

        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).isEqualTo(1);
        JsonNode r = a.get("items").get(0);
        assertThat(r.get("objectId").asLong()).isEqualTo(f1);
        assertThat(texts(r.get("changes"))).containsExactly("张数：1 → 2", "冻结变动：+12.35 → +24.69");
        assertThat(texts(r.get("sideEffects"))).containsExactly("交易 #" + fc + " BTC 冻结变动 −24.70 → −24.69");
        JsonNode se = r.get("sideEffects").get(0);
        assertThat(se.get("tradeId").asLong()).isEqualTo(fc);
        assertThat(se.get("action").asText()).isEqualTo("BTC");
        assertThat(se.get("contractLabel").asText()).isEqualTo("F 2026-12-18 12.345 P");
        assertThat(se.get("field").asText()).isEqualTo("frozenChange");
    }

    // ------------------------------------------------------------------ AC 11

    @ParameterizedTest(name = "AC11 修改 {0} = {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "contracts|0|张数必须是正整数",
            "contracts|1.5|张数必须是正整数",
            "netAmount|\"0\"|净额必须大于 0",
            "netAmount|\"360.001\"|净额最多 2 位小数",
            "tradedAtLocal|\"2026-11-11T10:01\"|成交时间不能晚于当前时间",
            "multiplier|0|multiplier 必须是正整数",
    })
    void ac11_fieldValidation(String field, String raw, String message) throws Exception {
        scenarioA();
        ObjectNode b = l2Body("360.00");
        b.set(field, json.readTree(raw));
        Full before = full();
        Resp r = putTrade(l2, b);
        assertErrorShape(r, 400, "VALIDATION_ERROR", TRADES + "/" + l2);
        List<String> d = new ArrayList<>();
        r.body().get("details").forEach(x -> d.add(x.get("field").asText() + "=" + x.get("message").asText()));
        assertThat(d).containsExactly(field + "=" + message);
        assertThat(full()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC11 备注 501 / 金额 0 → 字段错误；完全相同 → NO_CHANGES 不写审计（含等价写法）")
    void ac11_noteAmountAndNoChanges() {
        scenarioA();
        Full before = full();
        Resp note = putTrade(l2, edit(1, "360.00", "2026-11-04T11:00", 100, "x".repeat(501)));
        assertErrorShape(note, 400, "VALIDATION_ERROR", TRADES + "/" + l2);
        assertThat(detailFields(note)).containsExactly("note");
        Resp amount = putTransfer(d1, "DEPOSIT", "0", "2026-10-26T10:00", null);
        assertErrorShape(amount, 400, "VALIDATION_ERROR", "/api/cash/transfers/" + d1);
        assertThat(detailFields(amount)).containsExactly("amount");
        assertThat(full()).isEqualTo(before);

        assertThat(rejectedEdit(l2, l2Body("360.00"), "NO_CHANGES")).isEqualTo("没有修改");
        assertThat(rejectedEdit(l2, edit(1, "360", "2026-11-04T11:00:00", null, "   "), "NO_CHANGES")).isEqualTo("没有修改");
        assertThat(rejectedEdit(c1, c1Body(2, "241.3", "2026-11-05T10:00"), "NO_CHANGES")).isEqualTo("没有修改");
        Full b2 = full();
        assertThat(rejected(putTransfer(d1, "DEPOSIT", "80000", "2026-10-26T10:00:00", " "), b2, "NO_CHANGES",
                "/api/cash/transfers/" + d1)).isEqualTo("没有修改");
        assertThat(audit().get("totalItems").asInt()).isZero();
    }

    @Test
    @DisplayName("AC11 合约/方向字段被忽略；PUT 整体替换：不传 note 会清空；不传 multiplier 不改")
    void ac11_ignoredFieldsAndReplacement() {
        scenarioA();
        okEditTrade(l2, edit(1, "360.00", "2026-11-04T11:00", 100, "加仓"));
        ObjectNode b = edit(1, "360.00", "2026-11-04T11:00", null, null);
        b.put("symbol", "MSFT");
        b.put("action", "BTO");
        b.put("optionType", "CALL");
        b.put("strike", "999");
        JsonNode t = okEditTrade(l2, b);
        assertThat(t.get("contractLabel").asText()).isEqualTo(AAPL);
        assertThat(t.get("action").asText()).isEqualTo("STO");
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("note").isNull()).isTrue();
        assertThat(texts(lastAudit().get("changes"))).containsExactly("备注：加仓 → —");
        // closes ignore multiplier
        ObjectNode cb = c1Body(2, "241.30", "2026-11-05T10:00");
        cb.put("multiplier", 10);
        cb.put("note", "平仓备注");
        JsonNode c = okEditTrade(c1, cb);
        assertThat(c.get("multiplier").asInt()).isEqualTo(100);
        assertThat(ledgerRowFor("OPTION_CLOSE", c1).get("remark").asText()).isEqualTo("平仓备注");
    }

    // ------------------------------------------------------------------ AC 12 (audit log)

    @Test
    @DisplayName("AC12 审计倒序 3 条、按对象筛选、分页、参数错误、审计不可改/删（405）")
    void ac12_auditLog() {
        scenarioA();
        okEditTrade(l2, l2Body("365.00"));
        okEditTrade(l2, edit(1, "365.00", "2026-11-04T11:00", 100, "加仓"));
        ok(deleteTrade(l2), 204);
        JsonNode a = audit();
        assertThat(a.get("totalItems").asInt()).isEqualTo(3);
        List<String> rows = new ArrayList<>();
        a.get("items").forEach(i -> rows.add(i.get("operation").asText() + " " + i.get("objectId").asLong() + " "
                + texts(i.get("changes"))));
        assertThat(rows).containsExactly(
                "DELETE " + l2 + " []",
                "UPDATE " + l2 + " [备注：— → 加仓]",
                "UPDATE " + l2 + " [净额：360.00 → 365.00]");
        List<Long> ids = new ArrayList<>();
        a.get("items").forEach(i -> ids.add(i.get("id").asLong()));
        assertThat(ids).isSortedAccordingTo((x, y) -> Long.compare(y, x));

        assertThat(get(AUDIT + "?objectType=OPTION_TRADE&objectId=" + c1).body().get("totalItems").asInt()).isZero();
        assertThat(get(AUDIT + "?objectType=OPTION_TRADE&objectId=" + l2).body().get("totalItems").asInt()).isEqualTo(3);
        assertThat(get(AUDIT + "?objectType=CASH_TRANSFER").body().get("totalItems").asInt()).isZero();
        assertThat(get(AUDIT + "?objectType=OPTION_TRADE").body().get("totalItems").asInt()).isEqualTo(3);
        JsonNode p2 = get(AUDIT + "?size=2&page=2").body();
        assertThat(p2.get("items")).hasSize(1);
        assertThat(p2.get("totalPages").asInt()).isEqualTo(2);
        assertThat(p2.get("items").get(0).get("changes").get(0).get("text").asText()).isEqualTo("净额：360.00 → 365.00");
        assertThat(get(AUDIT + "?page=9").body().get("items")).isEmpty();

        Resp noType = get(AUDIT + "?objectId=1");
        assertErrorShape(noType, 400, "VALIDATION_ERROR", AUDIT);
        assertThat(noType.body().get("details").get(0).get("field").asText()).isEqualTo("objectType");
        assertThat(noType.body().get("details").get(0).get("message").asText()).isEqualTo("请同时指定对象类型");
        assertErrorShape(get(AUDIT + "?objectType=FOO"), 400, "BAD_REQUEST", AUDIT);
        assertErrorShape(get(AUDIT + "?objectType=OPTION_TRADE&objectId=abc"), 400, "BAD_REQUEST", AUDIT);
        assertErrorShape(get(AUDIT + "?page=0"), 400, "VALIDATION_ERROR", AUDIT);
        assertErrorShape(get(AUDIT + "?size=101"), 400, "VALIDATION_ERROR", AUDIT);

        assertErrorShape(postRaw(AUDIT, "{}", MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", AUDIT);
        assertErrorShape(exchange(HttpMethod.PUT, AUDIT, "{}", MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", AUDIT);
        assertErrorShape(exchange(HttpMethod.DELETE, AUDIT, null, MediaType.APPLICATION_JSON), 405, "METHOD_NOT_ALLOWED", AUDIT);
        long auditId = ids.get(0);
        Resp one = exchange(HttpMethod.DELETE, AUDIT + "/" + auditId, null, MediaType.APPLICATION_JSON);
        assertThat(one.status()).isIn(404, 405);
        assertThat(audit().get("totalItems").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ more boundaries

    @Test
    @DisplayName("录入顺序保留：改时间与另一笔相同时按原 id 排；交易/流水 id 不变")
    void entryOrderPreserved() {
        deposit("100000.00", "2026-10-26T10:00");
        long x1 = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "1.00", "2026-11-03T10:00", 100);
        long x2 = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "2.00", "2026-11-04T10:00", 100);
        okEditTrade(x1, edit(1, "1.00", "2026-11-05T10:00", 100, null));
        assertThat(position(AAPL).get("lots").get(0).get("tradeId").asLong()).isEqualTo(x2);
        okEditTrade(x1, edit(1, "1.00", "2026-11-04T10:00", 100, null));
        List<Long> lots = new ArrayList<>();
        position(AAPL).get("lots").forEach(l -> lots.add(l.get("tradeId").asLong()));
        assertThat(lots).containsExactly(x1, x2);
        List<Long> listIds = new ArrayList<>();
        get(TRADES).body().get("items").forEach(i -> listIds.add(i.get("id").asLong()));
        assertThat(listIds).containsExactly(x2, x1);
    }

    @Test
    @DisplayName("删掉被平开仓但之后平仓按 FIFO 仍合法 → 允许（改平另一批）；删前一笔平仓 → 之后平仓重新匹配")
    void deleteRematches() {
        deposit("100000.00", "2026-10-26T10:00");
        long a = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "100.00", "2026-11-02T10:00", 100);
        long b = open("STO", 1, "AAPL", "2026-12-18", "200", "PUT", "200.00", "2026-11-03T10:00", 100);
        long ca = closeTrade("BTC", 1, "AAPL", "2026-12-18", "200", "PUT", "10.00", "2026-11-05T10:00");
        long cb = closeTrade("BTC", 1, "AAPL", "2026-12-18", "200", "PUT", "20.00", "2026-11-06T10:00");
        assertThat(getTrade(cb).get("realizedPnl").asText()).isEqualTo("180.00");
        ok(deleteTrade(ca), 204);
        assertThat(getTrade(cb).get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(a);
        assertThat(getTrade(cb).get("realizedPnl").asText()).isEqualTo("80.00");
        assertThat(position(AAPL).get("lots").get(0).get("tradeId").asLong()).isEqualTo(b);
        ok(deleteTrade(a), 204);
        assertThat(getTrade(cb).get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(b);
        assertThat(getTrade(cb).get("realizedPnl").asText()).isEqualTo("180.00");
        assertThat(position(AAPL)).isNull();
    }

    @Test
    @DisplayName("到期规则：开仓改到到期日之后 / 平仓改到到期日之后 → tradedAtLocal 字段错误；已过期合约可修改")
    void expiryRulesOnEdit() {
        deposit("100000.00", "2026-10-26T10:00");
        long o = open("STO", 1, "TSLA", "2026-11-06", "250", "PUT", "300.00", "2026-11-02T10:00", 100);
        Resp r1 = putTrade(o, edit(1, "300.00", "2026-11-09T10:00", 100, null));
        assertErrorShape(r1, 400, "VALIDATION_ERROR", TRADES + "/" + o);
        assertThat(r1.body().get("details").get(0).get("message").asText()).isEqualTo("成交日不能晚于到期日（2026-11-06）");
        assertThat(detailFields(r1)).containsExactly("tradedAtLocal");
        okEditTrade(o, edit(1, "310.00", "2026-11-02T10:00", 100, "过期合约也能改"));
        long c = closeTrade("BTC", 1, "TSLA", "2026-11-06", "250", "PUT", "10.00", "2026-11-06T10:00");
        Resp r2 = putTrade(c, edit(1, "10.00", "2026-11-07T10:00", null, null));
        assertErrorShape(r2, 400, "VALIDATION_ERROR", TRADES + "/" + c);
        assertThat(r2.body().get("details").get(0).get("message").asText()).isEqualTo("到期日（2026-11-06）之后不能平仓");
        okEditTrade(c, edit(1, "10.00", "2026-11-06T23:59", null, null));
    }

    @Test
    @DisplayName("改开仓 multiplier 使之后平仓与持仓不一致 → MULTIPLIER_MISMATCH；没有平仓时可改并重算冻结")
    void multiplierEdit() {
        deposit("100000.00", "2026-10-26T10:00");
        long o = open("STO", 1, "XYZ", "2026-12-18", "200", "PUT", "34.35", "2026-11-03T10:30", 10);
        JsonNode t = okEditTrade(o, edit(1, "34.35", "2026-11-03T10:30", 100, null));
        assertThat(t.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(texts(lastAudit().get("changes"))).contains("冻结变动：+2,000.00 → +20,000.00");
        closeTrade("BTC", 1, "XYZ", "2026-12-18", "200", "PUT", "10.00", "2026-11-05T10:00");
        assertThat(rejectedEdit(o, edit(1, "34.35", "2026-11-03T10:30", 10, null), "MULTIPLIER_MISMATCH"))
                .startsWith("修改后该合约（XYZ 2026-12-18 200 P）");
    }

    @Test
    @DisplayName("BAD_REQUEST：id 不是整数；PUT 不存在的 id → 404")
    void badIds() {
        Resp bad = putTrade(0, edit(1, "1.00", "2026-11-03T10:00", null, null));
        assertThat(bad.status()).isEqualTo(404);
        Resp abc = exchange(HttpMethod.DELETE, TRADES + "/abc", null, MediaType.APPLICATION_JSON);
        assertErrorShape(abc, 400, "BAD_REQUEST", TRADES + "/abc");
        Resp t = putTransfer(999999, "DEPOSIT", "1.00", "2026-11-03T10:00", null);
        assertErrorShape(t, 404, "NOT_FOUND", "/api/cash/transfers/999999");
    }
}
