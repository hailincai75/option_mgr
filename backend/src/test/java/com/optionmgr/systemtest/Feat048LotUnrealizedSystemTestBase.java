package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * Shared helpers of the QA system tests for FEAT-048 持仓批次的未实现盈亏 (contract docs/api/FEAT-048.md): every lot of
 * {@code GET /api/option-positions} / {@code GET /api/stock-positions} (and of the FEAT-043 adjustment response's
 * {@code position}) gets {@code marketValue}, {@code unrealizedPnl}, {@code unrealizedPnlPercent},
 * {@code unrealizedStatus}, {@code unrealizedLabel}. Lots are valued with the row's mark / price; all lots but the last
 * (FIFO) are rounded HALF_UP to the cent, the last one = row marketValue − the others (Q238 A), so the lots always sum
 * to the row exactly; status / label follow the row (Q239); percent denominators |remainingNetAmount| / remainingCost,
 * 0.00 → null (Q240 / Q155).
 *
 * <p>Reuses the FEAT-035 QA base (profiles {@code test} + {@code e2e}, random port — never 8080, in-memory H2, fake
 * CBOE / Finnhub only, settable clock, reset before / after every test; fictional accounts and tickers).
 *
 * <p>Requirement scenario (today 2026-11-16 Mon, the 10:00 pull succeeds; multiplier 100; fictional ZZQ). All trades
 * are entered with the clock at 2026-11-13 10:30 (so the AC6 2026-11-13 put can still be opened), then the 10:00 pull:
 * <pre>
 *  甲账户 (id 1)  deposit 50,000.00 (10-01)
 *    S1 转入 ZZQ 100 shares cost 950.00 (2026-10-01) · S2 BUY 100 shares 780.65 (11-03 10:00)      price 8.65
 *    C  = ZZQ 2026-12-18 9C SHORT: A STO 1 18.34 (11-10 10:00), B STO 1 37.34 (11-11 10:00)  bid 0.10 / ask 0.30
 *    P  = ZZQ 2026-12-18 8P LONG:  L1 BTO 1 25.65 (11-12 10:00), L2 BTO 2 41.30 (11-13 10:00) bid 0.15 / ask 0.19
 *  乙账户 (id 2)  deposit 20,000.00 (10-01)
 *    S3 BUY ZZQ 101 shares 880.00 (11-04 10:00) · S4 BUY 99 shares 860.00 (11-05 10:00)
 * </pre>
 */
abstract class Feat048LotUnrealizedSystemTestBase extends Feat035AllAccountsSystemTestBase {

    static final String C9 = "ZZQ 2026-12-18 9 C";
    static final String P8 = "ZZQ 2026-12-18 8 P";
    static final String P7 = "ZZQ 2026-12-18 7 P";
    static final String P8_EXPIRED = "ZZQ 2026-11-13 8 P";

    static final String OCC_C9 = "ZZQ261218C00009000";
    static final String OCC_P8 = "ZZQ261218P00008000";

    static final String NO_QUOTE_LABEL = "无报价";
    static final String EXPIRED_LABEL = "已到期，待处理";

    static final String NO_QUOTE_VAL = "null null null NO_QUOTE " + NO_QUOTE_LABEL;
    static final String EXPIRED_VAL = "null null null EXPIRED_PENDING " + EXPIRED_LABEL;

    /** The 5 new lot fields (same names as the FEAT-017 row fields). */
    static final List<String> LOT_FIELDS = List.of("marketValue", "unrealizedPnl", "unrealizedPnlPercent",
            "unrealizedStatus", "unrealizedLabel");
    /** Pre-existing option lot fields (FEAT-003) that must still be there. */
    static final List<String> OPTION_LOT_OLD_FIELDS = List.of("tradeId", "action", "tradedAt", "tradedAtLocal",
            "originalContracts", "remainingContracts", "originalNetAmount", "remainingNetAmount", "originalFrozen",
            "remainingFrozen", "note");
    /** Pre-existing stock lot fields (FEAT-011). */
    static final List<String> STOCK_LOT_OLD_FIELDS = List.of("tradeId", "type", "tradedAt", "tradedAtLocal",
            "tradeDate", "originalShares", "remainingShares", "originalCost", "remainingCost", "note");

    // ---- expected values ("marketValue unrealizedPnl unrealizedPnlPercent status label"), requirement AC / contract
    static final String AC1_ROW = okVal("-40.00", "15.68", "28.16");
    static final String AC1_A = okVal("-20.00", "-1.66", "-9.05");
    static final String AC1_B = okVal("-20.00", "17.34", "46.44");
    static final String AC2_ROW = okVal("51.00", "-15.95", "-23.82");
    static final String AC2_L1 = okVal("17.00", "-8.65", "-33.72");
    static final String AC2_L2 = okVal("34.00", "-7.30", "-17.68");
    static final String AC3_ROW = okVal("1730.00", "-0.65", "-0.04");
    static final String AC3_S1 = okVal("865.00", "-85.00", "-8.95");
    static final String AC3_S2 = okVal("865.00", "84.35", "10.81");
    static final String AC9_B_ROW = okVal("1730.00", "-10.00", "-0.57");
    static final String AC9_S3 = okVal("873.65", "-6.35", "-0.72");
    static final String AC9_S4 = okVal("856.35", "-3.65", "-0.42");

    // =========================================================================================== data

    long openN(String action, int contracts, String symbol, String expiration, String strike, String type,
               String net, String at) {
        ObjectNode body = trade(action, contracts, symbol, expiration, strike, type, net, at);
        Resp r = post(TRADES, body.toString());
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    Resp closeResp(String action, int contracts, String symbol, String expiration, String strike, String type,
                   String net, String at) {
        ObjectNode body = trade(action, contracts, symbol, expiration, strike, type, net, at);
        body.remove("multiplier");
        return post(TRADES + "/closes", body.toString());
    }

    long closeN(String action, int contracts, String symbol, String expiration, String strike, String type,
                String net, String at) {
        Resp r = closeResp(action, contracts, symbol, expiration, strike, type, net, at);
        assertThat(r.status()).as("POST closes %s %s -> %s", action, symbol, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    long buyId(String symbol, int shares, String amount, String tradedAtLocal) {
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

    void assignPut(String symbol, String expiration, String strike, int contracts, String assignmentDate) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", "PUT");
        n.put("contracts", contracts);
        n.put("assignmentDate", assignmentDate);
        n.put("assignmentFee", "0.00");
        n.putNull("note");
        Resp r = post(TRADES + "/assignments", n.toString());
        assertThat(r.status()).as("POST assignments %s -> %s", n, r.body()).isEqualTo(201);
    }

    void deleteOptionTrade(long id) {
        Resp r = exchange(HttpMethod.DELETE, TRADES + "/" + id, null);
        assertThat(r.status()).as("DELETE trade %s -> %s", id, r.body()).isEqualTo(204);
    }

    /**
     * The requirement scenario, data only (no sources, no pull). Trades entered at 2026-11-13 10:30 New York;
     * {@code extraA} runs in 甲 at that clock after 甲's scenario trades (AC6 adds the 7P / expired 8P there). Clock left
     * at 2026-11-16 09:50, API account 甲. Ids in {@link #ids}: S1 S2 A B L1 L2 S3 S4.
     */
    void scenarioZData(Runnable extraA, Runnable extraB) {
        setNow("2026-11-13T10:30");
        renameAccount(A, A_NAME);
        String b = createAccount(B_NAME);
        assertThat(b).as("乙账户 gets id 2 after the reset").isEqualTo(B);
        accountB = b;

        accountId = A;
        depositId("50000.00", "2026-10-01T09:00");
        ids.put("S1", transferInId("ZZQ", 100, "950.00", "2026-10-01"));
        ids.put("S2", buyId("ZZQ", 100, "780.65", "2026-11-03T10:00"));
        ids.put("A", openN("STO", 1, "ZZQ", "2026-12-18", "9", "CALL", "18.34", "2026-11-10T10:00"));
        ids.put("B", openN("STO", 1, "ZZQ", "2026-12-18", "9", "CALL", "37.34", "2026-11-11T10:00"));
        ids.put("L1", openN("BTO", 1, "ZZQ", "2026-12-18", "8", "PUT", "25.65", "2026-11-12T10:00"));
        ids.put("L2", openN("BTO", 2, "ZZQ", "2026-12-18", "8", "PUT", "41.30", "2026-11-13T10:00"));
        if (extraA != null) {
            extraA.run();
        }

        accountId = B;
        depositId("20000.00", "2026-10-01T09:00");
        ids.put("S3", buyId("ZZQ", 101, "880.00", "2026-11-04T10:00"));
        ids.put("S4", buyId("ZZQ", 99, "860.00", "2026-11-05T10:00"));
        if (extraB != null) {
            extraB.run();
        }
        accountId = A;
        setNow("2026-11-16T09:50");
    }

    /** Fake CBOE ZZQ chain: 9C bid 0.10 / ask 0.30 (mark 0.20), 8P 0.15 / 0.19 (mark 0.17), plus {@code extra}. */
    ObjectNode zzqChain(String price, String timestamp, ObjectNode... extra) {
        List<ObjectNode> opts = new ArrayList<>();
        opts.add(option(OCC_C9, "bid", "0.10", "ask", "0.30"));
        opts.add(option(OCC_P8, "bid", "0.15", "ask", "0.19"));
        opts.addAll(List.of(extra));
        return ok(cboeBody("ZZQ", price, timestamp, opts));
    }

    /** Scenario + extras + fake ZZQ (8.65) + the 10:00 pull; clock left at 2026-11-16 10:00, API account 甲. */
    void scenarioZ(Runnable extraA, Runnable extraB) {
        scenarioZData(extraA, extraB);
        sources().cboe("ZZQ", zzqChain("8.65", "2026-11-16 09:45:00")).apply();
        tickAt("2026-11-16T10:00", "SCHEDULED");
    }

    void scenarioZ() {
        scenarioZ(null, null);
    }

    // =========================================================================================== reading

    JsonNode optionsOf(String account) {
        return okAs(account, OPTION_POSITIONS);
    }

    JsonNode stocksOf(String account) {
        return okAs(account, STOCK_POSITIONS);
    }

    /** Option row of a single account (account = "1" / "2"). */
    JsonNode opt(String account, String contractLabel) {
        return optRowOf(optionsOf(account), Long.parseLong(account), contractLabel);
    }

    boolean hasOpt(String account, String contractLabel) {
        for (JsonNode p : optionsOf(account).get("positions")) {
            if (p.get("contractLabel").asText().equals(contractLabel)) {
                return true;
            }
        }
        return false;
    }

    JsonNode stk(String account, String symbol) {
        return stockRowOf(stocksOf(account), Long.parseLong(account), symbol);
    }

    static String okVal(String mv, String pnl, String pct) {
        return mv + " " + pnl + " " + pct + " OK null";
    }

    /** {@code val} of every lot of a row, FIFO order as returned. */
    static List<String> lotVals(JsonNode row) {
        List<String> out = new ArrayList<>();
        row.get("lots").forEach(l -> out.add(val(l)));
        return out;
    }

    static List<Long> lotIds(JsonNode row) {
        List<Long> out = new ArrayList<>();
        row.get("lots").forEach(l -> out.add(l.get("tradeId").asLong()));
        return out;
    }

    static BigDecimal money(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    // =========================================================================================== contract invariants

    /**
     * Contract invariants of one row's lots (option row if it has {@code contractLabel}, else stock):
     * fields present (null, not omitted) and old fields still there; status / label = row's (Q239); not OK → the three
     * numbers null; OK → scale-2 strings, Σ lot marketValue = row marketValue and Σ lot unrealizedPnl = row
     * unrealizedPnl exactly, every lot but the last = round(price × size) HALF_UP 2 with the direction sign, the last =
     * row − others (Q238 A), pnl = remainingNetAmount + mv / mv − remainingCost, percent = pnl × 100 ÷ |denominator|
     * HALF_UP 2 or null when the denominator is 0.00 (Q240); a single lot equals the row.
     */
    static void assertLotInvariants(JsonNode row) {
        boolean option = row.has("contractLabel");
        String key = option ? row.get("contractLabel").asText() : row.get("symbol").asText();
        JsonNode lots = row.get("lots");
        assertThat(lots.isArray()).as("%s lots array", key).isTrue();
        assertThat(lots.size()).as("%s lotCount = lots", key).isEqualTo(row.get("lotCount").asInt());
        String status = row.get("unrealizedStatus").asText();
        for (JsonNode l : lots) {
            for (String f : LOT_FIELDS) {
                assertThat(l.has(f)).as("%s lot %s has %s (null, not omitted): %s", key, l.get("tradeId"), f, l)
                        .isTrue();
            }
            assertThat(fieldNames(l)).as("%s lot keeps its existing fields", key)
                    .containsAll(option ? OPTION_LOT_OLD_FIELDS : STOCK_LOT_OLD_FIELDS);
            assertThat(l.get("unrealizedStatus").asText()).as("%s lot status = row (Q239)", key).isEqualTo(status);
            assertThat(text(l.get("unrealizedLabel"))).as("%s lot label = row (Q239)", key)
                    .isEqualTo(text(row.get("unrealizedLabel")));
        }
        if (!status.equals("OK")) {
            for (JsonNode l : lots) {
                assertThat(val(l)).as("%s lot not computable", key).isEqualTo(val(row));
            }
            return;
        }
        BigDecimal price = option ? money(row.get("optionQuote").get("mark")) : money(row.get("quote").get("price"));
        boolean shortPos = option && row.get("direction").asText().equals("SHORT");
        BigDecimal rowMv = money(row.get("marketValue"));
        BigDecimal sumMv = new BigDecimal("0.00");
        BigDecimal sumPnl = new BigDecimal("0.00");
        for (int i = 0; i < lots.size(); i++) {
            JsonNode l = lots.get(i);
            assertThat(l.get("marketValue").isTextual()).as("%s lot mv is a string", key).isTrue();
            assertThat(l.get("unrealizedPnl").isTextual()).as("%s lot pnl is a string", key).isTrue();
            BigDecimal mv = money(l.get("marketValue"));
            BigDecimal pnl = money(l.get("unrealizedPnl"));
            assertThat(mv.scale()).as("%s lot mv scale", key).isEqualTo(2);
            assertThat(pnl.scale()).as("%s lot pnl scale", key).isEqualTo(2);
            if (i < lots.size() - 1) {
                BigDecimal size = option
                        ? BigDecimal.valueOf((long) l.get("remainingContracts").asInt() * row.get("multiplier").asInt())
                        : BigDecimal.valueOf(l.get("remainingShares").asLong());
                BigDecimal own = price.multiply(size).setScale(2, RoundingMode.HALF_UP);
                if (shortPos) {
                    own = own.negate();
                }
                assertThat(mv.toPlainString()).as("%s lot %d = round(price × size) (Q238 A)", key, i)
                        .isEqualTo(own.toPlainString());
            } else {
                assertThat(mv.toPlainString()).as("%s last lot = row − others (Q238 A)", key)
                        .isEqualTo(rowMv.subtract(sumMv).toPlainString());
            }
            BigDecimal denom;
            if (option) {
                BigDecimal net = money(l.get("remainingNetAmount"));
                assertThat(pnl.toPlainString()).as("%s lot pnl = remainingNetAmount + mv", key)
                        .isEqualTo(net.add(mv).toPlainString());
                denom = net.abs();
            } else {
                BigDecimal cost = money(l.get("remainingCost"));
                assertThat(pnl.toPlainString()).as("%s lot pnl = mv − remainingCost", key)
                        .isEqualTo(mv.subtract(cost).toPlainString());
                denom = cost;
            }
            JsonNode pct = l.get("unrealizedPnlPercent");
            if (denom.signum() == 0) {
                assertThat(pct.isNull()).as("%s lot percent null when denominator 0.00 (Q155)", key).isTrue();
            } else {
                assertThat(pct.isTextual()).as("%s lot percent string", key).isTrue();
                assertThat(pct.asText()).as("%s lot percent without + / %%", key)
                        .doesNotContain("+").doesNotContain("%");
                assertThat(pct.asText()).as("%s lot percent = pnl × 100 ÷ |denominator| HALF_UP 2 (Q240)", key)
                        .isEqualTo(pnl.multiply(BigDecimal.valueOf(100)).divide(denom, 2, RoundingMode.HALF_UP)
                                .toPlainString());
            }
            sumMv = sumMv.add(mv);
            sumPnl = sumPnl.add(pnl);
        }
        assertThat(sumMv.toPlainString()).as("%s Σ lot marketValue = row (exact)", key).isEqualTo(rowMv.toPlainString());
        assertThat(sumPnl.toPlainString()).as("%s Σ lot unrealizedPnl = row (exact)", key)
                .isEqualTo(money(row.get("unrealizedPnl")).toPlainString());
        if (lots.size() == 1) {
            assertThat(val(lots.get(0))).as("%s single lot = row", key).isEqualTo(val(row));
        }
    }

    /** {@link #assertLotInvariants} for every option and stock row of {@code account} (may be ALL). */
    void assertAllLotInvariants(String account) {
        optionsOf(account).get("positions").forEach(Feat048LotUnrealizedSystemTestBase::assertLotInvariants);
        stocksOf(account).get("positions").forEach(Feat048LotUnrealizedSystemTestBase::assertLotInvariants);
    }
}
