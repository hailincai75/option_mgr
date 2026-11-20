package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Contract "列表行的 hasAuditLog": the "历史" link of trade / closed-trade / cash-ledger rows is shown only when the
 * object it points to has audit records. The flag is stored on the record (V6 column {@code has_audit_log}):
 * creation leaves it false (Q42), a successful edit sets it on the edited record only (records corrected as a side
 * effect are not marked), a rejected edit leaves it false; option ledger rows are always false.
 * Fictional ticker ZZQX; "now" = 2026-11-12 10:00 NY.
 */
class HasAuditLogSystemTest extends OptionTradeSystemTestBase {

    static final String EXP = "2026-12-18";

    // ------------------------------------------------------------------ helpers

    ObjectNode zzqx(String action, int contracts, String strike, String type, String net, String at) {
        return trade(action, contracts, "ZZQX", EXP, strike, type, net, at, 100);
    }

    long closeTrade(String action, int contracts, String strike, String type, String net, String at) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "ZZQX");
        n.put("expirationDate", EXP);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        Resp r = postRaw(TRADES + "/closes", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("close -> %s", r.body()).isEqualTo(201);
        assertThat(r.body().get("hasAuditLog").isBoolean()).isTrue();
        assertThat(r.body().get("hasAuditLog").asBoolean()).as("POST close response").isFalse();
        return r.body().get("id").asLong();
    }

    Resp putTrade(long id, int contracts, String net, String at, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        n.put("note", note);
        return exchange(HttpMethod.PUT, TRADES + "/" + id, n.toString(), MediaType.APPLICATION_JSON);
    }

    Resp putTradeWithMultiplier(long id, int contracts, String net, String at, int multiplier) {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        n.put("multiplier", multiplier);
        n.putNull("note");
        return exchange(HttpMethod.PUT, TRADES + "/" + id, n.toString(), MediaType.APPLICATION_JSON);
    }

    /** Stored column value (V6 has_audit_log). */
    boolean storedTradeFlag(long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT has_audit_log FROM option_trade WHERE id = ?", Boolean.class, id));
    }

    boolean storedTransferFlag(long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT has_audit_log FROM cash_transfer WHERE id = ?", Boolean.class, id));
    }

    long auditCount(String objectType, long objectId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE object_type = ? AND object_id = ?",
                Long.class, objectType, objectId);
        return n == null ? 0 : n;
    }

    long auditCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class);
        return n == null ? 0 : n;
    }

    Resp putTransfer(long id, String type, String amount, String at, String remark) {
        return exchange(HttpMethod.PUT, "/api/cash/transfers/" + id, transferBody(type, amount, at, remark).toString(),
                MediaType.APPLICATION_JSON);
    }

    /** id → hasAuditLog of every item; asserts the field is a JSON boolean on every row. */
    static Map<Long, Boolean> flags(JsonNode page, String idField) {
        Map<Long, Boolean> out = new LinkedHashMap<>();
        for (JsonNode item : page.get("items")) {
            JsonNode f = item.get("hasAuditLog");
            assertThat(f).as("hasAuditLog present on %s", item).isNotNull();
            assertThat(f.isBoolean()).as("hasAuditLog is a JSON boolean on %s", item).isTrue();
            out.put(item.get(idField).asLong(), f.asBoolean());
        }
        return out;
    }

    Map<Long, Boolean> tradeFlags() {
        Resp r = get(TRADES + "?size=100");
        assertThat(r.status()).isEqualTo(200);
        return flags(r.body(), "id");
    }

    Map<Long, Boolean> closedFlags() {
        Resp r = get("/api/closed-trades?size=100");
        assertThat(r.status()).isEqualTo(200);
        return flags(r.body(), "id");
    }

    /** "sourceType#sourceId" → hasAuditLog for every ledger row. */
    Map<String, Boolean> ledgerFlags() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (JsonNode item : ledger(1, 100).get("items")) {
            JsonNode f = item.get("hasAuditLog");
            assertThat(f).as("hasAuditLog present on %s", item).isNotNull();
            assertThat(f.isBoolean()).as("hasAuditLog is a JSON boolean on %s", item).isTrue();
            out.put(item.get("sourceType").asText() + "#" + item.get("sourceId").asLong(), f.asBoolean());
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("trades + closed + ledger: all false until edited; edit → only that object's rows true; opening-lot edit does not mark the closed row")
    void flagsFollowEdits() {
        long dep = deposit("50000.00", "2026-11-02T10:00").get("id").asLong();
        long wd = withdraw("1000.00", "2026-11-02T11:00").get("id").asLong();
        JsonNode l1Resp = okTrade(zzqx("STO", 1, "40", "PUT", "120.00", "2026-11-03T10:30"));
        assertThat(l1Resp.get("hasAuditLog").isBoolean()).isTrue();
        assertThat(l1Resp.get("hasAuditLog").asBoolean()).as("POST open response").isFalse();
        long l1 = l1Resp.get("id").asLong();
        long l2 = okTrade(zzqx("STO", 1, "40", "PUT", "130.00", "2026-11-04T10:30")).get("id").asLong();
        long b1 = okTrade(zzqx("BTO", 1, "45", "CALL", "200.00", "2026-11-04T11:00")).get("id").asLong();
        long c1 = closeTrade("BTC", 1, "40", "PUT", "50.00", "2026-11-05T10:00"); // closes L1 (FIFO)
        long c2 = closeTrade("STC", 1, "45", "CALL", "260.00", "2026-11-05T11:00"); // closes B1

        // Nothing edited: every row false.
        assertThat(tradeFlags()).containsOnlyKeys(l1, l2, b1, c1, c2).doesNotContainValue(true);
        assertThat(closedFlags()).containsOnlyKeys(c1, c2).doesNotContainValue(true);
        assertThat(ledgerFlags()).hasSize(7).doesNotContainValue(true);
        assertThat(getTrade(c1).get("hasAuditLog").asBoolean()).isFalse();

        // Edit the opening lot L1 (closed by C1): L1 true; the closed row C1 stays false (only the closing trade counts).
        Resp putL1 = putTrade(l1, 1, "120.00", "2026-11-03T10:30", "第一笔");
        assertThat(putL1.status()).as("PUT L1 -> %s", putL1.body()).isEqualTo(200);
        assertThat(putL1.body().get("hasAuditLog").asBoolean()).as("PUT response").isTrue();
        assertThat(tradeFlags()).containsEntry(l1, true).containsEntry(l2, false).containsEntry(b1, false)
                .containsEntry(c1, false).containsEntry(c2, false);
        assertThat(getTrade(l1).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(closedFlags()).containsEntry(c1, false).containsEntry(c2, false);
        // option ledger rows never get the flag, even for the edited trade
        assertThat(ledgerFlags()).containsEntry("OPTION_OPEN#" + l1, false).doesNotContainValue(true);

        // Edit the closing trade C2: its trade row and closed row become true.
        Resp putC2 = putTrade(c2, 1, "265.00", "2026-11-05T11:00", null);
        assertThat(putC2.status()).as("PUT C2 -> %s", putC2.body()).isEqualTo(200);
        assertThat(putC2.body().get("hasAuditLog").asBoolean()).isTrue();
        assertThat(closedFlags()).containsEntry(c1, false).containsEntry(c2, true);
        assertThat(tradeFlags()).containsEntry(c2, true).containsEntry(c1, false);
        assertThat(ledgerFlags()).containsEntry("OPTION_CLOSE#" + c2, false).doesNotContainValue(true);

        // Edit the withdrawal: only its ledger row becomes true; the deposit stays false.
        Resp putWd = putTransfer(wd, "WITHDRAWAL", "1000.00", "2026-11-02T11:00", "改备注");
        assertThat(putWd.status()).as("PUT transfer -> %s", putWd.body()).isEqualTo(200);
        Map<String, Boolean> ledger = ledgerFlags();
        assertThat(ledger).containsEntry("WITHDRAWAL#" + wd, true).containsEntry("DEPOSIT#" + dep, false);
        assertThat(ledger.values().stream().filter(b -> b).count()).isEqualTo(1);

        // Delete the never-edited L2 → gone from the list, the other flags unchanged.
        Resp del = exchange(HttpMethod.DELETE, TRADES + "/" + l2, null, MediaType.APPLICATION_JSON);
        assertThat(del.status()).isEqualTo(204);
        assertThat(tradeFlags()).containsOnlyKeys(l1, b1, c1, c2).containsEntry(l1, true).containsEntry(c2, true)
                .containsEntry(b1, false).containsEntry(c1, false);
    }

    @Test
    @DisplayName("object type matters: an edited trade does not mark a cash transfer with the same id, and vice versa")
    void sameIdDifferentObjectType() {
        deposit("80000.00", "2026-11-02T10:00");
        // Align a transfer id and a trade id (independent sequences). The shared in-memory DB's sequences drift apart
        // by however many records other test classes created, so restart both identities at a common next value
        // (both tables are empty apart from the deposit above; in-memory test DB only).
        long next = Math.max(
                okTrade(zzqx("BTO", 1, "50", "CALL", "100.00", "2026-11-03T10:00")).get("id").asLong(),
                deposit("10.00", "2026-11-02T10:05").get("id").asLong()) + 1;
        jdbc.execute("ALTER TABLE option_trade ALTER COLUMN id RESTART WITH " + next);
        jdbc.execute("ALTER TABLE cash_transfer ALTER COLUMN id RESTART WITH " + next);
        long tradeId = okTrade(zzqx("BTO", 1, "50", "CALL", "100.00", "2026-11-03T10:00")).get("id").asLong();
        long transferId = deposit("10.00", "2026-11-02T10:05").get("id").asLong();
        assertThat(transferId).as("aligned ids").isEqualTo(tradeId);
        long same = tradeId;

        Resp putT = putTrade(same, 1, "101.00", "2026-11-03T10:00", null);
        assertThat(putT.status()).as("PUT trade -> %s", putT.body()).isEqualTo(200);
        assertThat(tradeFlags()).containsEntry(same, true);
        assertThat(ledgerFlags()).as("DEPOSIT with the same id as the edited trade")
                .containsEntry("DEPOSIT#" + same, false).doesNotContainValue(true);

        // Now edit the transfer with that id: its row true; trades other than `same` still false.
        Resp putD = putTransfer(same, "DEPOSIT", "10.00", "2026-11-02T10:05", "对齐");
        assertThat(putD.status()).as("PUT transfer -> %s", putD.body()).isEqualTo(200);
        assertThat(ledgerFlags()).containsEntry("DEPOSIT#" + same, true);
        Map<Long, Boolean> trades = tradeFlags();
        assertThat(trades.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey))
                .containsExactly(same);
    }

    @Test
    @DisplayName("per account: an edit in another account does not mark rows of the default account")
    void perAccount() {
        deposit("30000.00", "2026-11-02T10:00");
        long t1 = okTrade(zzqx("BTO", 1, "50", "CALL", "100.00", "2026-11-03T10:00")).get("id").asLong();

        ObjectNode acc = json.createObjectNode();
        acc.put("name", "小账户");
        acc.putNull("brokerNote");
        Resp created = postRaw("/api/accounts", acc.toString(), MediaType.APPLICATION_JSON);
        assertThat(created.status()).as("create account -> %s", created.body()).isEqualTo(201);
        accountId = String.valueOf(created.body().get("id").asLong());
        deposit("30000.00", "2026-11-02T10:00");
        long t2 = okTrade(zzqx("BTO", 1, "50", "CALL", "100.00", "2026-11-03T10:00")).get("id").asLong();
        Resp put = putTrade(t2, 1, "105.00", "2026-11-03T10:00", null);
        assertThat(put.status()).isEqualTo(200);
        assertThat(tradeFlags()).containsOnlyKeys(t2).containsEntry(t2, true);

        accountId = "1";
        assertThat(tradeFlags()).containsOnlyKeys(t1).containsEntry(t1, false);
        assertThat(ledgerFlags()).doesNotContainValue(true);
    }

    @Test
    @DisplayName("stored flag: create = false in the column; a successful edit sets the column together with its audit record; the API reads the column")
    void storedFlag() {
        long dep = deposit("50000.00", "2026-11-02T10:00").get("id").asLong();
        long l1 = okTrade(zzqx("BTO", 1, "50", "CALL", "100.00", "2026-11-03T10:00")).get("id").asLong();
        assertThat(storedTradeFlag(l1)).isFalse();
        assertThat(storedTransferFlag(dep)).isFalse();

        assertThat(putTrade(l1, 1, "101.00", "2026-11-03T10:00", null).status()).isEqualTo(200);
        assertThat(putTransfer(dep, "DEPOSIT", "50000.00", "2026-11-02T10:00", "初始").status()).isEqualTo(200);
        assertThat(storedTradeFlag(l1)).isTrue();
        assertThat(storedTransferFlag(dep)).isTrue();
        assertThat(auditCount("OPTION_TRADE", l1)).isEqualTo(1);
        assertThat(auditCount("CASH_TRANSFER", dep)).isEqualTo(1);

        // The API value comes from the stored column, not from a lookup in audit_record.
        long l2 = okTrade(zzqx("BTO", 1, "55", "CALL", "80.00", "2026-11-03T10:30")).get("id").asLong();
        assertThat(getTrade(l2).get("hasAuditLog").asBoolean()).isFalse();
        jdbc.update("UPDATE option_trade SET has_audit_log = TRUE WHERE id = ?", l2);
        assertThat(getTrade(l2).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(tradeFlags()).containsEntry(l2, true);
    }

    @Test
    @DisplayName("cascade: editing an opening STO put corrects the put assignment's stock cost, but only the edited trade is marked")
    void cascadeCorrectedRecordNotMarked() {
        // FEAT-051 (Q255): BTC / STC keep their saved lots, so the old cascade used here (editing L1 1 → 2 re-matched
        // the BTC and corrected its frozen release −24.70 → −24.69) is now rejected with CLOSE_LOTS_INVALID. An ASG is
        // still replayed by FIFO (Q260) and its stock cost is corrected when a covered open's net changes (FEAT-013),
        // so it carries the same "cascade-corrected record keeps its flag" check.
        deposit("5000.00", "2026-11-02T09:30");
        long l1 = okTrade(zzqx("STO", 1, "40", "PUT", "120.00", "2026-11-03T10:00")).get("id").asLong();
        long l2 = okTrade(zzqx("STO", 1, "5", "PUT", "10.00", "2026-11-03T11:00")).get("id").asLong();
        ObjectNode a = json.createObjectNode();
        a.put("symbol", "ZZQX");
        a.put("expirationDate", EXP);
        a.put("strike", "40");
        a.put("optionType", "PUT");
        a.put("contracts", 1);
        a.put("assignmentDate", "2026-11-06");
        a.put("assignmentFee", "0.00");
        a.putNull("note");
        Resp asg = postRaw(TRADES + "/assignments", a.toString(), MediaType.APPLICATION_JSON);
        assertThat(asg.status()).as("ASG -> %s", asg.body()).isEqualTo(201);
        long c1 = asg.body().get("id").asLong();
        assertThat(asg.body().get("assignment").get("stockCost").asText()).isEqualTo("3880.00");

        Resp put = putTradeWithMultiplier(l1, 1, "130.00", "2026-11-03T10:00", 100);
        assertThat(put.status()).as("PUT L1 -> %s", put.body()).isEqualTo(200);
        assertThat(getTrade(c1).get("assignment").get("stockCost").asText())
                .as("ASG was cascade-corrected").isEqualTo("3870.00");
        assertThat(get("/api/audit-log").body().get("items").get(0).get("sideEffects")).as("side effect recorded")
                .hasSize(1);

        assertThat(tradeFlags()).containsEntry(l1, true).containsEntry(l2, false).containsEntry(c1, false);
        assertThat(getTrade(c1).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(closedFlags()).containsEntry(c1, false);
        assertThat(ledgerFlags()).doesNotContainValue(true);
        assertThat(storedTradeFlag(l1)).isTrue();
        assertThat(storedTradeFlag(c1)).as("cascade-corrected record keeps its flag").isFalse();
        assertThat(auditCount("OPTION_TRADE", c1)).isZero();
    }

    @Test
    @DisplayName("rejected edits (NO_CHANGES, validation, position, cash) leave the flag false and write no audit record")
    void rejectedEditsLeaveFlagFalse() {
        long dep = deposit("5000.00", "2026-11-02T10:00").get("id").asLong();
        long l1 = okTrade(zzqx("STO", 1, "40", "PUT", "120.00", "2026-11-03T10:30")).get("id").asLong();
        long c1 = closeTrade("BTC", 1, "40", "PUT", "50.00", "2026-11-05T10:00");

        assertErrorShape(putTrade(l1, 1, "120.00", "2026-11-03T10:30", null), 422, "NO_CHANGES", TRADES + "/" + l1);
        assertErrorShape(putTrade(l1, 1, "121.00", "2026-11-13T10:30", null), 400, "VALIDATION_ERROR",
                TRADES + "/" + l1);
        Resp exceeds = putTrade(c1, 2, "50.00", "2026-11-05T10:00", null);
        assertThat(exceeds.status()).as("close more than the open lot -> %s", exceeds.body()).isEqualTo(422);
        // collateral 4000 at 2026-11-03 needs more than 3000 cash
        assertErrorShape(putTransfer(dep, "DEPOSIT", "3000.00", "2026-11-02T10:00", null), 422, "CASH_INSUFFICIENT",
                "/api/cash/transfers/" + dep);
        assertErrorShape(putTransfer(dep, "DEPOSIT", "5000.00", "2026-11-02T10:00", null), 422, "NO_CHANGES",
                "/api/cash/transfers/" + dep);

        assertThat(auditCount()).isZero();
        assertThat(tradeFlags()).containsOnlyKeys(l1, c1).doesNotContainValue(true);
        assertThat(closedFlags()).doesNotContainValue(true);
        assertThat(ledgerFlags()).doesNotContainValue(true);
        assertThat(storedTradeFlag(l1)).isFalse();
        assertThat(storedTradeFlag(c1)).isFalse();
        assertThat(storedTransferFlag(dep)).isFalse();
    }
}
