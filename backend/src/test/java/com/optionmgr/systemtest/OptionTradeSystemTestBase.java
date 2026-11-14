package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;

/**
 * Helpers for FEAT-001 system tests. "Now" = 2026-11-12T15:00:00Z (10:00 New York), so the acceptance times
 * 2026-11-10 / 2026-11-11 are in the past; single tests move the clock when they need a later "now".
 */
abstract class OptionTradeSystemTestBase extends CashSystemTestBase {

    static final Instant FEAT001_NOW = Instant.parse("2026-11-12T15:00:00Z");
    static final String TRADES = "/api/option-trades";

    @Override
    Instant now() {
        return FEAT001_NOW;
    }

    /** Request body; multiplier omitted when null. */
    ObjectNode trade(String action, int contracts, String symbol, String expiration, String strike, String type,
                     String netAmount, String tradedAtLocal, Integer multiplier) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", netAmount);
        n.put("tradedAtLocal", tradedAtLocal);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        n.putNull("note");
        return n;
    }

    /** AAPL 2026-12-18 &lt;strike&gt; &lt;type&gt;, multiplier 100. */
    ObjectNode aapl(String action, String strike, String type, String netAmount, String at) {
        return trade(action, 1, "AAPL", "2026-12-18", strike, type, netAmount, at, 100);
    }

    Resp postTrade(ObjectNode body) {
        return postRaw(TRADES, body.toString(), MediaType.APPLICATION_JSON);
    }

    JsonNode okTrade(ObjectNode body) {
        Resp r = postTrade(body);
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body();
    }

    /** Must be rejected with the given 422 code, nothing stored; returns the message. */
    String rejectedTrade(ObjectNode body, String code) {
        State before = state();
        Resp r = postTrade(body);
        assertErrorShape(r, 422, code, TRADES);
        assertThat(r.body().get("details")).isEmpty();
        assertThat(state()).as("nothing stored after rejected %s", body).isEqualTo(before);
        return r.body().get("message").asText();
    }

    /** Must be rejected with 400 VALIDATION_ERROR whose details are exactly these (field, message) pairs. */
    void validationError(ObjectNode body, String... fieldMessagePairs) {
        State before = state();
        Resp r = postTrade(body);
        assertErrorShape(r, 400, "VALIDATION_ERROR", TRADES);
        List<String> actual = new java.util.ArrayList<>();
        r.body().get("details").forEach(d -> actual.add(d.get("field").asText() + "=" + d.get("message").asText()));
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 0; i < fieldMessagePairs.length; i += 2) {
            expected.add(fieldMessagePairs[i] + "=" + fieldMessagePairs[i + 1]);
        }
        assertThat(actual).as("details for %s", body).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(state()).isEqualTo(before);
    }

    void badRequest(String rawBody, String field) {
        State before = state();
        Resp r = postRaw(TRADES, rawBody, MediaType.APPLICATION_JSON);
        assertErrorShape(r, 400, "BAD_REQUEST", TRADES);
        assertThat(detailFields(r)).as("details for %s", rawBody).containsExactly(field);
        assertThat(state()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ FEAT-039 helpers (covered call)

    /**
     * FEAT-039: STO calls need shares. Posts a stock 转入 (TRANSFER_IN: no cash, no ledger row) of {@code shares}
     * {@code symbol} on {@code tradeDate} (NY date, counted from 00:00, Q86) in the current account; returns its id.
     */
    long transferInShares(String symbol, int shares, String tradeDate) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", "1.00");
        n.put("tradeDate", tradeDate);
        n.putNull("note");
        Resp r = postRaw("/api/stock-trades", n.toString(), MediaType.APPLICATION_JSON);
        assertThat(r.status()).as("TRANSFER_IN %d %s %s -> %s", shares, symbol, tradeDate, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /**
     * FEAT-039 scenario L / old behaviour: an STO call "entered before FEAT-039 went live" (coverage_status LEGACY,
     * Q79). Systemtests run with profile "test" (no /api/test-support), so: a temporary 转入 of exactly the needed
     * shares on the trade date lets the normal POST pass E1/E3; then via JDBC the temporary stock row is removed and
     * the new trade (plus any batch that was LEGACY before and may have been auto-included because of the temporary
     * shares) is set to LEGACY. A 转入 has no ledger row, so cash / ledger are exactly as for a pre-launch trade.
     */
    JsonNode okLegacyTrade(ObjectNode stoCallBody) {
        assertThat(stoCallBody.get("action").asText()).isEqualTo("STO");
        assertThat(stoCallBody.get("optionType").asText()).isEqualTo("CALL");
        List<Long> legacyBefore = jdbc.queryForList(
                "SELECT id FROM option_trade WHERE coverage_status = 'LEGACY'", Long.class);
        int multiplier = stoCallBody.has("multiplier") ? stoCallBody.get("multiplier").asInt() : 100;
        int shares = stoCallBody.get("contracts").asInt() * multiplier;
        long temp = transferInShares(stoCallBody.get("symbol").asText(), shares,
                stoCallBody.get("tradedAtLocal").asText().substring(0, 10));
        JsonNode t = okTrade(stoCallBody);
        assertThat(jdbc.update("DELETE FROM stock_trade WHERE id = ?", temp)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE option_trade SET coverage_status = 'LEGACY' WHERE id = ?",
                t.get("id").asLong())).isEqualTo(1);
        for (Long id : legacyBefore) {
            jdbc.update("UPDATE option_trade SET coverage_status = 'LEGACY' WHERE id = ?", id);
        }
        return t;
    }

    /**
     * FEAT-009 (Q126): a new opening can no longer expire on a weekend / NYSE holiday. Tests that need such a stored
     * position ("entered before FEAT-009", not re-checked) open it on a trading day and then move the expiration via
     * JDBC; nothing else stored depends on the expiration date.
     */
    void moveExpiration(long tradeId, String expirationDate) {
        assertThat(jdbc.update("UPDATE option_trade SET expiration_date = CAST(? AS DATE) WHERE id = ?",
                expirationDate, tradeId)).isEqualTo(1);
    }

    String coverageStatus(long tradeId) {
        return jdbc.queryForObject("SELECT coverage_status FROM option_trade WHERE id = ?", String.class, tradeId);
    }

    // ------------------------------------------------------------------ state snapshots

    record State(String total, String frozen, String available, long ledgerEntries, long trades) {
    }

    State state() {
        JsonNode s = summary();
        return new State(s.get("totalCash").asText(), s.get("frozenCash").asText(), s.get("availableCash").asText(),
                ledgerCount(), tradeCount());
    }

    long tradeCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class);
        return n == null ? 0 : n;
    }

    JsonNode getTrade(long id) {
        Resp r = get(TRADES + "/" + id);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    /** Ledger row (newest first) with the given sourceType + sourceId. */
    JsonNode ledgerRowFor(String sourceType, long sourceId) {
        for (JsonNode row : ledger(1, 100).get("items")) {
            if (row.get("sourceType").asText().equals(sourceType) && row.get("sourceId").asLong() == sourceId) {
                return row;
            }
        }
        throw new AssertionError("no ledger row " + sourceType + "#" + sourceId);
    }
}
