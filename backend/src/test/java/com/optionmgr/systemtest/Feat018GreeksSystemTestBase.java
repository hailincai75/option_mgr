package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers of the QA system tests for FEAT-018 单合约 Greeks (contract docs/api/FEAT-018.md).
 *
 * <p>Reuses the FEAT-016 / FEAT-017 QA bases (profiles {@code test} + {@code e2e}, random port — never 8080,
 * in-memory fake CBOE / Finnhub, settable clock, reset before / after every test). Quotes (incl. iv / delta / gamma /
 * theta / vega) are injected with the fake sources and a scheduled {@code tick} at the test clock; the real CBOE /
 * Finnhub are never called.
 *
 * <p>Requirement scenario (today 2026-11-23 Mon; the 10:00 pull succeeds with CBOE snapshot time 09:45; multiplier
 * 100; fictional tickers):
 * <ul>
 *   <li>QRTV 2026-12-18 50P 1.20 / 1.30, iv 0.3512, delta −0.3021, gamma 0.0512, theta −0.0456, vega 0.0711;</li>
 *   <li>QRTV 2026-12-18 55C 1.80 / 1.90, iv 0.3320, delta 0.2850, gamma 0.0480, theta −0.0390, vega 0.0650;</li>
 *   <li>IXQZ 2026-12-18 65C 0.80 / 0.90, iv 0.2900, delta 0.18345, gamma 0.03125, theta −0.0247, vega 0.0503.</li>
 * </ul>
 * Account A (default account 1): P1 STO 1 QRTV 50P; P2 BTO 2 QRTV 55C; P3 STO 1 IXQZ 65C (covered by 100 IXQZ
 * shares, as in FEAT-017, so the STO call is accepted); P4 STO 1 QRTV 2026-12-18 47.5P (never in the chain).
 * Account B: P5 STO 3 QRTV 50P. Opening net amounts are those of FEAT-017 so the FEAT-017 summary of account A is
 * unchanged (AC4).
 */
abstract class Feat018GreeksSystemTestBase extends Feat017UnrealizedSystemTestBase {

    static final String C_P7 = "QRTV 2026-11-23 52 P";
    static final String OCC_C_P7 = "QRTV261123P00052000";

    /** Exact field set of {@code positions[].greeks} (no Rho, Q167). */
    static final List<String> GREEKS_FIELDS = List.of("status", "label", "ivPercent",
            "deltaPerContract", "deltaPosition", "gammaPerContract", "gammaPosition",
            "thetaPerContract", "thetaPosition", "vegaPerContract", "vegaPosition",
            "quoteTime", "quoteTimeLocal", "manualMarkNote");

    static final String[] GREEKS = {"delta", "gamma", "theta", "vega"};

    static String manualNote(String mmddHHmm) {
        return "Greeks 按 CBOE 报价（" + mmddHHmm + "），未随手动 Mark 调整";
    }

    // =========================================================================================== fake chain

    /** A CBOE options[] entry with quote and Greeks; a {@code null} value is omitted from the JSON (= missing). */
    ObjectNode greekOption(String occ, String bid, String ask, String iv, String delta, String gamma, String theta,
                           String vega) {
        return option(occ, "bid", bid, "ask", ask, "iv", iv, "delta", delta, "gamma", gamma, "theta", theta,
                "vega", vega, "rho", "-0.0123");
    }

    ObjectNode p1Greeks() {
        return greekOption(OCC_C_P1, "1.20", "1.30", "0.3512", "-0.3021", "0.0512", "-0.0456", "0.0711");
    }

    ObjectNode p2Greeks() {
        return greekOption(OCC_C_P2, "1.80", "1.90", "0.3320", "0.2850", "0.0480", "-0.0390", "0.0650");
    }

    ObjectNode p3Greeks() {
        return greekOption(OCC_C_P3, "0.80", "0.90", "0.2900", "0.18345", "0.03125", "-0.0247", "0.0503");
    }

    /** QRTV chain: the given entries (scenario default: P1, P2; no P4). */
    ObjectNode qrtv018(String timestamp, ObjectNode... options) {
        return ok(cboeBody("QRTV", "52.40", timestamp, List.of(options)));
    }

    ObjectNode ixqz018(String timestamp) {
        return ok(cboeBody("IXQZ", "61.10", timestamp, List.of(p3Greeks())));
    }

    /** The scenario's fake CBOE at snapshot 09:45: QRTV (P1, P2 + extras) and IXQZ (P3). */
    void sources018(ObjectNode... extraQrtvOptions) {
        List<ObjectNode> q = new ArrayList<>(List.of(p1Greeks(), p2Greeks()));
        q.addAll(List.of(extraQrtvOptions));
        sources().cboe("QRTV", qrtv018("2026-11-23 09:45:00", q.toArray(ObjectNode[]::new)))
                .cboe("IXQZ", ixqz018("2026-11-23 09:45:00")).apply();
    }

    // =========================================================================================== scenario

    /**
     * The requirement scenario (data only, no sources, no pull). Clock left at 2026-11-23 09:50 New York, API account
     * A ("1"); {@link #accountB} set.
     */
    void scenario018() {
        setNow("2026-11-23T09:50");
        accountId = "1";
        deposit("30000.00", "2026-11-16T10:00");
        transferInShares("IXQZ", 100, "5880.00", "2026-11-19");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-20T10:00");
        openN("BTO", 2, "QRTV", "2026-12-18", "55", "CALL", "301.30", "2026-11-20T10:05");
        openN("STO", 1, "IXQZ", "2026-12-18", "65", "CALL", "84.35", "2026-11-20T10:10");
        openN("STO", 1, "QRTV", "2026-12-18", "47.5", "PUT", "45.00", "2026-11-20T10:15");
        accountB = createAccount("账户B");
        accountId = accountB;
        deposit("20000.00", "2026-11-16T10:00");
        openN("STO", 3, "QRTV", "2026-12-18", "50", "PUT", "360.00", "2026-11-20T10:20");
        accountId = "1";
    }

    /** Scenario + sources + the 10:00 pull; clock left at 10:00. */
    void scenarioPulled018() {
        scenario018();
        sources018();
        tickAt("2026-11-23T10:00", "SCHEDULED");
    }

    // =========================================================================================== reading

    /** The {@code greeks} object of an option row; asserts it is a non-null object with the exact contract shape. */
    JsonNode greeks(String account, String contractLabel) {
        JsonNode row = optRow(account, contractLabel);
        return greeksOf(row);
    }

    JsonNode greeksOf(JsonNode row) {
        JsonNode g = row.get("greeks");
        assertThat(g).as("greeks of %s present", row.get("contractLabel")).isNotNull();
        assertThat(g.isObject()).as("greeks never null: %s", row).isTrue();
        assertGreeksShape(row, g);
        return g;
    }

    /**
     * Contract shape of one greeks object: exact field set (no Rho), status / label pairing, all values null unless
     * OK, scale 2 / 4, per-contract and position null together, positions without '+', no "-0", quoteTime
     * consistent with the row's optionQuote, manual note only when OK and manual.
     */
    void assertGreeksShape(JsonNode row, JsonNode g) {
        String label = row.get("contractLabel").asText();
        assertThat(fieldNames(g)).as("greeks fields of %s (no Rho)", label)
                .containsExactlyInAnyOrderElementsOf(GREEKS_FIELDS);
        String st = g.get("status").asText();
        assertThat(st).isIn("OK", "NO_QUOTE", "EXPIRED_PENDING");
        switch (st) {
            case "OK" -> assertThat(g.get("label").isNull()).as("OK → label null").isTrue();
            case "NO_QUOTE" -> assertThat(text(g.get("label"))).isEqualTo(NO_QUOTE_LABEL);
            default -> assertThat(text(g.get("label"))).isEqualTo(EXPIRED_LABEL);
        }
        if (!st.equals("OK")) {
            assertThat(g.get("ivPercent").isNull()).as("%s ivPercent null when %s", label, st).isTrue();
            for (String k : GREEKS) {
                assertThat(g.get(k + "PerContract").isNull()).as("%s %sPerContract null when %s", label, k, st)
                        .isTrue();
                assertThat(g.get(k + "Position").isNull()).as("%s %sPosition null when %s", label, k, st).isTrue();
            }
            assertThat(g.get("manualMarkNote").isNull()).as("%s manualMarkNote null when %s", label, st).isTrue();
        } else {
            if (!g.get("ivPercent").isNull()) {
                assertThat(g.get("ivPercent").isTextual()).as("ivPercent is a string").isTrue();
                String iv = g.get("ivPercent").asText();
                assertThat(iv).doesNotContain("%").doesNotContain("+");
                assertThat(d(iv).scale()).as("ivPercent scale 2: %s", iv).isEqualTo(2);
            }
            for (String k : GREEKS) {
                JsonNode per = g.get(k + "PerContract");
                JsonNode pos = g.get(k + "Position");
                assertThat(per.isNull()).as("%s %s per-contract / position null together", label, k)
                        .isEqualTo(pos.isNull());
                if (!per.isNull()) {
                    assertThat(per.isTextual()).as("%sPerContract is a string", k).isTrue();
                    assertThat(pos.isTextual()).as("%sPosition is a string", k).isTrue();
                    assertThat(d(per.asText()).scale()).as("%s %sPerContract scale 4", label, k).isEqualTo(4);
                    assertThat(d(pos.asText()).scale()).as("%s %sPosition scale 2", label, k).isEqualTo(2);
                    assertThat(pos.asText()).as("position without +").doesNotStartWith("+");
                    assertThat(per.asText()).doesNotStartWith("+");
                    assertThat(pos.asText()).as("no negative zero").isNotEqualTo("-0.00");
                    assertThat(per.asText()).as("no negative zero").isNotEqualTo("-0.0000");
                }
            }
            JsonNode oq = row.get("optionQuote");
            boolean manual = oq.get("manual").asBoolean();
            if (manual) {
                assertThat(text(g.get("manualMarkNote"))).as("manual mark note of %s", label)
                        .isEqualTo(manualNote(mmddHHmm(text(g.get("quoteTimeLocal")))));
            } else {
                assertThat(g.get("manualMarkNote").isNull()).as("no manual note of %s", label).isTrue();
            }
            assertThat(g.get("quoteTime").isNull()).as("OK → CBOE quote time present").isFalse();
        }
        // quote time: same as optionQuote (same read)
        assertThat(text(g.get("quoteTime"))).as("greeks.quoteTime = optionQuote.quoteTime of %s", label)
                .isEqualTo(text(row.get("optionQuote").get("quoteTime")));
        assertThat(text(g.get("quoteTimeLocal"))).as("greeks.quoteTimeLocal = optionQuote.quoteTimeLocal of %s", label)
                .isEqualTo(text(row.get("optionQuote").get("quoteTimeLocal")));
        if (!g.get("quoteTime").isNull()) {
            Instant at = Instant.parse(g.get("quoteTime").asText());
            assertThat(LocalDateTime.parse(g.get("quoteTimeLocal").asText()).atZone(NY).toInstant()).isEqualTo(at);
        } else {
            assertThat(g.get("quoteTimeLocal").isNull()).isTrue();
        }
    }

    static String mmddHHmm(String isoLocal) {
        // "2026-11-23T09:45:00" → "11-23 09:45"
        return isoLocal.substring(5, 10) + " " + isoLocal.substring(11, 16);
    }

    /**
     * "status label | iv | dPos dPer | gPos gPer | tPos tPer | vPos vPer | quoteTimeLocal | note" — everything the
     * page shows for the Greeks of one row.
     */
    static String shownGreeks(JsonNode g) {
        StringBuilder sb = new StringBuilder();
        sb.append(g.get("status").asText()).append(' ').append(text(g.get("label")));
        sb.append(" | ").append(text(g.get("ivPercent")));
        for (String k : GREEKS) {
            sb.append(" | ").append(text(g.get(k + "Position"))).append(' ').append(text(g.get(k + "PerContract")));
        }
        sb.append(" | ").append(text(g.get("manualMarkNote")));
        return sb.toString();
    }

    /** Expected {@link #shownGreeks} of an OK row without a manual note. */
    static String okG(String iv, String dPos, String dPer, String gPos, String gPer, String tPos, String tPer,
                      String vPos, String vPer) {
        return okGNote(iv, dPos, dPer, gPos, gPer, tPos, tPer, vPos, vPer, null);
    }

    static String okGNote(String iv, String dPos, String dPer, String gPos, String gPer, String tPos, String tPer,
                          String vPos, String vPer, String note) {
        return "OK null | " + iv + " | " + dPos + " " + dPer + " | " + gPos + " " + gPer + " | " + tPos + " " + tPer
                + " | " + vPos + " " + vPer + " | " + note;
    }

    static final String NO_QUOTE_G = "NO_QUOTE " + NO_QUOTE_LABEL
            + " | null | null null | null null | null null | null null | null";
    static final String EXPIRED_G = "EXPIRED_PENDING " + EXPIRED_LABEL
            + " | null | null null | null null | null null | null null | null";

    /** AC1: P1 (STO 1 QRTV 50P) at the 09:45 values. */
    static final String P1_G = okG("35.12", "30.21", "-0.3021", "-5.12", "0.0512", "4.56", "-0.0456", "-7.11",
            "0.0711");
    /** AC2: P2 (BTO 2 QRTV 55C). */
    static final String P2_G = okG("33.20", "57.00", "0.2850", "9.60", "0.0480", "-7.80", "-0.0390", "13.00",
            "0.0650");
    /** AC3: P3 (STO 1 IXQZ 65C; raw delta 0.18345 / gamma 0.03125). */
    static final String P3_G = okG("29.00", "-18.35", "0.1835", "-3.13", "0.0313", "2.47", "-0.0247", "-5.03",
            "0.0503");
    /** AC5: P5 (STO 3 QRTV 50P). */
    static final String P5_G = okG("35.12", "90.63", "-0.3021", "-15.36", "0.0512", "13.68", "-0.0456", "-21.33",
            "0.0711");

    /**
     * Independent recomputation from the raw values stored by FEAT-016 ({@code GET /api/test-support/market-data/
     * stored}) for every OK row of the account: perContract = raw HALF_UP 4; position = raw × contracts × multiplier
     * × (LONG +1 / SHORT −1) HALF_UP 2 (unrounded raw); ivPercent = iv × 100 HALF_UP 2; null raw → null.
     */
    void assertGreeksMatchStored(String account) {
        for (JsonNode row : optionPositions(account).get("positions")) {
            JsonNode g = greeksOf(row);
            if (!g.get("status").asText().equals("OK")) {
                continue;
            }
            JsonNode raw = storedContract(row.get("symbol").asText(), row.get("expirationDate").asText(),
                    row.get("strike").asText(), row.get("optionType").asText());
            String label = row.get("contractLabel").asText();
            BigDecimal sign = row.get("direction").asText().equals("LONG") ? BigDecimal.ONE : BigDecimal.ONE.negate();
            BigDecimal factor = BigDecimal.valueOf(row.get("contracts").asLong())
                    .multiply(BigDecimal.valueOf(row.get("multiplier").asLong())).multiply(sign);
            String iv = text(raw.get("iv"));
            assertThat(text(g.get("ivPercent"))).as("%s ivPercent from stored iv %s", label, iv)
                    .isEqualTo(iv == null ? null
                            : d(iv).movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString());
            for (String k : GREEKS) {
                String r = text(raw.get(k));
                assertThat(text(g.get(k + "PerContract"))).as("%s %sPerContract from stored %s", label, k, r)
                        .isEqualTo(r == null ? null : d(r).setScale(4, RoundingMode.HALF_UP).toPlainString());
                assertThat(text(g.get(k + "Position"))).as("%s %sPosition from stored %s × %s", label, k, r, factor)
                        .isEqualTo(r == null ? null
                                : d(r).multiply(factor).setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        }
    }

    /** Every option row of the account carries a well-formed greeks object; stock rows carry none (FEAT-019). */
    void assertAllRowsShape(String account) {
        for (JsonNode row : optionPositions(account).get("positions")) {
            greeksOf(row);
        }
        for (JsonNode s : stockPositions(account).get("positions")) {
            assertThat(s.has("greeks")).as("stock row has no greeks: %s", s).isFalse();
        }
    }
}
