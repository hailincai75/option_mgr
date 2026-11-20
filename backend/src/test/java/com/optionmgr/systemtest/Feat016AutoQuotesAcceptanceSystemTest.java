package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-016 自动行情, acceptance criteria 1–10 (AC 10's real {@code bootRun} part is in
 * {@link Feat016RealHttpSystemTest}). See {@link Feat016QuoteSystemTestBase} for the scenario and the fakes.
 */
@DisplayName("FEAT-016 自动行情（验收标准，假 CBOE / Finnhub + 假时钟）")
class Feat016AutoQuotesAcceptanceSystemTest extends Feat016QuoteSystemTestBase {

    // ============================================================================================== AC 1

    @Test
    @DisplayName("AC1 10:00 拉取成功：A 的 P1、B 的 P3 = 52.40、1.20 / 1.30、Mark 1.25、11-23 09:45；P2 = 61.10、2.10 / 2.30、2.20；IXQZ 股票现价 61.10")
    void ac1FirstPull() {
        scenario();
        scenarioSources();

        // before any pull: nothing quoted yet (never succeeded, Q146 warning)
        JsonNode before = optionQuote("1", P1);
        assertThat(shown(before)).isEqualTo("null/null null null NEVER_QUOTED");
        assertThat(before.get("fetchFailed").asBoolean()).isFalse();
        assertThat(before.get("staleLabel").isNull()).isTrue();
        assertThat(underlyingQuote("1", P1).get("price").isNull()).isTrue();

        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(results(t)).containsExactly("IXQZ SUCCESS SUCCESS null CBOE", "QRTV SUCCESS SUCCESS null CBOE");
        assertThat(requests()).containsExactlyInAnyOrder("CBOE QRTV", "CBOE IXQZ");

        // account A, P1
        JsonNode p1 = optionQuote("1", P1);
        assertThat(shown(p1)).isEqualTo("1.20/1.30 1.25 2026-11-23T09:45:00 QUOTED");
        assertThat(p1.get("automaticMark").asText()).isEqualTo("1.25");
        assertThat(p1.get("markBasis").asText()).isEqualTo("MID");
        assertThat(p1.get("source").asText()).isEqualTo("CBOE");
        assertThat(p1.get("quoteTime").asText()).isEqualTo("2026-11-23T14:45:00Z");
        assertThat(p1.get("contractStatusLabel").isNull()).isTrue();
        assertThat(p1.get("manual").asBoolean()).isFalse();
        assertThat(p1.get("manualEnteredAt").isNull()).isTrue();
        assertThat(p1.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p1.get("lastSuccessAt").asText()).isEqualTo("2026-11-23T15:00:00Z");
        assertThat(p1.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(p1.get("staleLabel").isNull()).isTrue();

        JsonNode u1 = underlyingQuote("1", P1);
        assertThat(u1.get("symbol").asText()).isEqualTo("QRTV");
        assertThat(u1.get("price").asText()).isEqualTo("52.40");
        assertThat(u1.get("automaticPrice").asText()).isEqualTo("52.40");
        assertThat(u1.get("source").asText()).isEqualTo("CBOE");
        assertThat(u1.get("quoteTime").asText()).isEqualTo("2026-11-23T14:45:00Z");
        assertThat(u1.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:45:00");
        assertThat(u1.get("manual").asBoolean()).isFalse();
        assertThat(u1.get("fetchFailed").asBoolean()).isFalse();
        assertThat(u1.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(u1.get("staleLabel").isNull()).isTrue();

        // account B: P3 identical to P1 (one shared quote, rule 1), P2, IXQZ stock
        assertThat(optionQuote(accountB, P3)).isEqualTo(p1);
        assertThat(underlyingQuote(accountB, P3)).isEqualTo(u1);
        JsonNode p2 = optionQuote(accountB, P2);
        assertThat(shown(p2)).isEqualTo("2.10/2.30 2.20 2026-11-23T09:45:00 QUOTED");
        assertThat(underlyingQuote(accountB, P2).get("price").asText()).isEqualTo("61.10");
        JsonNode stock = stockQuote(accountB, "IXQZ");
        assertThat(stock.get("price").asText()).isEqualTo("61.10");
        assertThat(stock.get("source").asText()).isEqualTo("CBOE");
        assertThat(stock.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:45:00");
        assertThat(stock.get("fetchFailed").asBoolean()).isFalse();
        assertThat(stock).isEqualTo(underlyingQuote(accountB, P2));

        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(s.get("lastAttemptAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(s.get("lastRefreshAt").isNull()).isTrue();
        assertThat(s.get("refreshAvailableAt").isNull()).isTrue();
        assertThat(s.get("refreshAllowed").asBoolean()).isTrue();
        assertThat(s.get("trackedSymbolCount").asInt()).isEqualTo(2);
    }

    // ============================================================================================== AC 2

    /** 3,400 QRTV contracts: 3,399 others + P1. */
    List<ObjectNode> bigQrtvChain() {
        List<ObjectNode> out = new ArrayList<>();
        String[] exps = {"261127", "261204", "261211", "261218", "270115", "270219"};
        outer:
        for (String exp : exps) {
            for (String cp : new String[]{"C", "P"}) {
                for (int s = 0; s < 300; s++) {
                    String code = String.format("QRTV%s%s%08d", exp, cp, 10000 + s * 500);
                    if (code.equals(OCC_P1)) {
                        continue;
                    }
                    out.add(option(code, "bid", "0.50", "ask", "0.60", "theo", "0.55", "iv", "0.4"));
                    if (out.size() == 3399) {
                        break outer;
                    }
                }
            }
        }
        out.add(p1Option());
        assertThat(out).hasSize(3400);
        return out;
    }

    @Test
    @DisplayName("AC2 QRTV 含 3,400 个合约：只请求 QRTV、IXQZ，只存 2 个持有合约；P1 存 iv / delta / theta / theo（页面不给）；P1、P3 都 BTC 后不再请求 QRTV")
    void ac2OnlyHeldContractsStored() {
        scenario();
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 09:45:00", bigQrtvChain())))
                .cboe("IXQZ", ixqzOk()).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        assertThat(requests()).containsExactlyInAnyOrder("CBOE QRTV", "CBOE IXQZ");
        JsonNode contracts = stored().get("contracts");
        List<String> occ = new ArrayList<>();
        contracts.forEach(c -> occ.add(c.get("occCode").asText()));
        assertThat(occ).containsExactlyInAnyOrder(OCC_P1, OCC_P2);

        JsonNode p1 = storedContract("QRTV", "2026-12-18", "50", "PUT");
        assertNum(p1, "iv", "0.3512");
        assertNum(p1, "delta", "-0.3021");
        assertNum(p1, "theta", "-0.0456");
        assertNum(p1, "theo", "1.24");
        assertNum(p1, "gamma", "0.0512");
        assertNum(p1, "vega", "0.0711");
        assertNum(p1, "rho", "-0.0123");
        assertNum(p1, "bid", "1.20");
        assertNum(p1, "ask", "1.30");
        assertNum(p1, "last", "1.22");
        assertThat(p1.get("volume").asLong()).isEqualTo(120);
        assertThat(p1.get("openInterest").asLong()).isEqualTo(3400);
        assertThat(p1.get("quoteTime").asText()).isEqualTo("2026-11-23T14:45:00Z");
        assertThat(p1.get("fetchedAt").asText()).isEqualTo("2026-11-23T15:00:00Z");
        assertThat(p1.get("missingSince").isNull()).isTrue();
        // the page objects carry no IV / Greeks (exact field set asserted in position())
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.25");

        setNow("2026-11-23T10:10");
        close("BTC", "QRTV", "2026-12-18", "50", "PUT", "130.00", "2026-11-23T10:05");
        accountId = accountB;
        close("BTC", "QRTV", "2026-12-18", "50", "PUT", "130.00", "2026-11-23T10:06");
        accountId = "1";
        clearRequests();

        JsonNode t = tickAt("2026-11-23T11:00", "SCHEDULED");
        assertThat(requests()).containsExactly("CBOE IXQZ");
        assertThat(results(t)).containsExactly("IXQZ SUCCESS SUCCESS null CBOE");
        assertThat(t.get("status").get("trackedSymbolCount").asInt()).isEqualTo(1);
        // history rows are kept, not deleted
        assertThat(storedContract("QRTV", "2026-12-18", "50", "PUT").get("occCode").asText()).isEqualTo(OCC_P1);
    }

    static void assertNum(JsonNode node, String field, String expected) {
        assertThat(node.get(field).isTextual()).as("%s is a string", field).isTrue();
        assertThat(new BigDecimal(node.get(field).asText())).as(field).isEqualByComparingTo(expected);
    }

    // ============================================================================================== AC 3

    @Test
    @DisplayName("AC3 P2 bid 0 / ask 0.05 / theo 0.03 → Mark 0.03（THEO）；theo 也缺失 → Mark 为空（无报价）")
    void ac3MarkFallsBackToTheo() {
        scenario();
        sources().cboe("QRTV", qrtvOk())
                .cboe("IXQZ", ok(cboeBody("IXQZ", "61.10", "2026-11-23 09:45:00",
                        List.of(option(OCC_P2, "bid", "0", "ask", "0.05", "theo", "0.03"))))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        JsonNode p2 = optionQuote(accountB, P2);
        assertThat(shown(p2)).isEqualTo("0.00/0.05 0.03 2026-11-23T09:45:00 QUOTED");
        assertThat(p2.get("automaticMark").asText()).isEqualTo("0.03");
        assertThat(p2.get("markBasis").asText()).isEqualTo("THEO");

        sources().cboe("QRTV", qrtvOk())
                .cboe("IXQZ", ok(cboeBody("IXQZ", "61.10", "2026-11-23 10:00:00",
                        List.of(option(OCC_P2, "bid", "0", "ask", "0.05"))))).apply();
        tickAt("2026-11-23T11:00", "SCHEDULED");
        p2 = optionQuote(accountB, P2);
        assertThat(shown(p2)).isEqualTo("0.00/0.05 null 2026-11-23T10:00:00 QUOTED");
        assertThat(p2.get("automaticMark").isNull()).isTrue();
        assertThat(p2.get("markBasis").isNull()).isTrue();
        assertThat(p2.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p2.get("staleLabel").isNull()).isTrue();
    }

    // ============================================================================================== AC 4

    @Test
    @DisplayName("AC4 首次拉取 IXQZ CBOE 500、Finnhub 61.05：IXQZ 现价 61.05（FINNHUB）；P2 无报价 + 行情未更新（从未成功）；P1、P3 = 1.25")
    void ac4FinnhubFallback() {
        scenario();
        sources().cboe("QRTV", qrtvOk()).cboe("IXQZ", httpError(500))
                .finnhub("IXQZ", finnhubOk("61.05", "2026-11-23T09:59:58")).apply();
        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(results(t)).containsExactly("IXQZ FALLBACK HTTP_ERROR SUCCESS FINNHUB",
                "QRTV SUCCESS SUCCESS null CBOE");
        assertThat(requests()).containsExactlyInAnyOrder("CBOE QRTV", "CBOE IXQZ", "FINNHUB IXQZ");

        JsonNode stock = stockQuote(accountB, "IXQZ");
        assertThat(stock.get("price").asText()).isEqualTo("61.05");
        assertThat(stock.get("automaticPrice").asText()).isEqualTo("61.05");
        assertThat(stock.get("source").asText()).isEqualTo("FINNHUB");
        assertThat(stock.get("quoteTime").asText()).isEqualTo("2026-11-23T14:59:58Z");
        assertThat(stock.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:59:58");
        assertThat(stock.get("fetchFailed").asBoolean()).isFalse();
        assertThat(stock.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(stock.get("staleLabel").isNull()).isTrue();
        assertThat(underlyingQuote(accountB, P2)).isEqualTo(stock);

        JsonNode p2 = optionQuote(accountB, P2);
        assertThat(shown(p2)).isEqualTo("null/null null null NEVER_QUOTED");
        assertThat(p2.get("fetchFailed").asBoolean()).isTrue();
        assertThat(p2.get("lastSuccessAt").isNull()).isTrue();
        assertThat(p2.get("staleLabel").asText()).isEqualTo(NEVER_SUCCEEDED_ROW);

        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.25");
        assertThat(optionQuote(accountB, P3).get("mark").asText()).isEqualTo("1.25");
        assertThat(status().get("stale").asBoolean()).as("QRTV chain succeeded → run succeeded").isFalse();
    }

    // ============================================================================================== AC 5

    @Test
    @DisplayName("AC5 P4 链中从无 → 无报价；P1 11:00（Q270 下一次整点）从链中消失 → 仍 1.25，标注 数据源已无该合约（最后报价：11-23 09:45）")
    void ac5MissingFromSource() {
        scenario();
        open("STO", "QRTV", "2026-12-18", "47.5", "PUT", "100.00", "2026-11-20T10:20");
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(optionQuote("1", P4).get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");

        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.50", "2026-11-23 10:00:00", List.of(
                        option("QRTV261218P00055000", "bid", "3.10", "ask", "3.30")))))
                .cboe("IXQZ", ixqzOk()).apply();
        JsonNode t = tickAt("2026-11-23T11:00", "SCHEDULED");
        assertThat(results(t)).contains("QRTV SUCCESS SUCCESS null CBOE");

        JsonNode p4 = optionQuote("1", P4);
        assertThat(shown(p4)).isEqualTo("null/null null null NEVER_QUOTED");
        assertThat(p4.get("contractStatusLabel").isNull()).isTrue();
        assertThat(p4.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p4.get("staleLabel").isNull()).isTrue();
        assertThat(p4.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T11:00:00");

        JsonNode p1 = optionQuote("1", P1);
        assertThat(shown(p1)).isEqualTo("1.20/1.30 1.25 2026-11-23T09:45:00 MISSING_FROM_SOURCE");
        assertThat(p1.get("contractStatusLabel").asText()).isEqualTo("数据源已无该合约（最后报价：11-23 09:45）");
        assertThat(p1.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p1.get("staleLabel").isNull()).isTrue();
        assertThat(p1.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T11:00:00");
        assertThat(optionQuote(accountB, P3)).isEqualTo(p1);

        JsonNode u = underlyingQuote("1", P1);
        assertThat(u.get("price").asText()).isEqualTo("52.50");
        assertThat(u.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(storedContract("QRTV", "2026-12-18", "50", "PUT").get("missingSince").asText())
                .isEqualTo("2026-11-23T16:00:00Z");
    }

    // ============================================================================================== AC 6

    @Test
    @DisplayName("AC6a 11:00 起（Q270） IXQZ 的 CBOE 和 Finnhub 都失败、QRTV 正常：IXQZ 行 行情未更新（上次：11-23 10:00），P2 仍 2.20、现价仍 61.10，无顶部提示")
    void ac6OneSymbolFails() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 10:00:00", List.of(p1Option()))))
                .cboe("IXQZ", unreachable()).finnhub("IXQZ", httpError(500)).apply();
        JsonNode t = tickAt("2026-11-23T11:00", "SCHEDULED");
        assertThat(results(t)).containsExactly("IXQZ FAILED NETWORK_ERROR HTTP_ERROR null",
                "QRTV SUCCESS SUCCESS null CBOE");
        assertThat(t.get("results").get(0).get("failureDetail").asText()).isNotBlank();

        setNow("2026-11-23T11:05");
        String label = "行情未更新（上次：11-23 10:00）";
        JsonNode p2 = optionQuote(accountB, P2);
        assertThat(shown(p2)).isEqualTo("2.10/2.30 2.20 2026-11-23T09:45:00 QUOTED");
        assertThat(p2.get("fetchFailed").asBoolean()).isTrue();
        assertThat(p2.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(p2.get("staleLabel").asText()).isEqualTo(label);
        JsonNode stock = stockQuote(accountB, "IXQZ");
        assertThat(stock.get("price").asText()).isEqualTo("61.10");
        assertThat(stock.get("fetchFailed").asBoolean()).isTrue();
        assertThat(stock.get("staleLabel").asText()).isEqualTo(label);
        assertThat(underlyingQuote(accountB, P2)).isEqualTo(stock);

        JsonNode p1 = optionQuote("1", P1);
        assertThat(p1.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p1.get("staleLabel").isNull()).isTrue();
        assertThat(p1.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T11:00:00");

        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("warning").isNull()).isTrue();
    }

    @Test
    @DisplayName("AC6b（Q270 90 分钟）11:00 两个标的全部失败：11:30 无提示，11:31 顶部 行情未更新（上次：2026-11-23 10:00 美东），值全部保留；12:00 成功后提示消失")
    void ac6AllFailTopWarning() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        sources().apply(); // everything unreachable (CBOE and Finnhub)
        JsonNode t = tickAt("2026-11-23T11:00", "SCHEDULED");
        assertThat(results(t)).containsExactly("IXQZ FAILED NETWORK_ERROR NETWORK_ERROR null",
                "QRTV FAILED NETWORK_ERROR NETWORK_ERROR null");

        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).as("11:00: 60 minutes, under the 90-minute threshold").isFalse();
        assertThat(s.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(s.get("lastAttemptAtLocal").asText()).isEqualTo("2026-11-23T11:00:00");
        assertThat(s.get("lastAttemptAt").asText()).isEqualTo("2026-11-23T16:00:00Z");

        setNow("2026-11-23T11:30");
        assertThat(status().get("stale").asBoolean()).as("11:30: exactly 90 minutes, not more").isFalse();

        setNow("2026-11-23T11:31");
        s = status();
        assertThat(s.get("stale").asBoolean()).isTrue();
        assertThat(s.get("warning").asText()).isEqualTo("行情未更新（上次：2026-11-23 10:00 美东）");

        String label = "行情未更新（上次：11-23 10:00）";
        JsonNode p1 = optionQuote("1", P1);
        assertThat(shown(p1)).isEqualTo("1.20/1.30 1.25 2026-11-23T09:45:00 QUOTED");
        assertThat(p1.get("staleLabel").asText()).isEqualTo(label);
        assertThat(underlyingQuote("1", P1).get("price").asText()).isEqualTo("52.40");
        assertThat(underlyingQuote("1", P1).get("staleLabel").asText()).isEqualTo(label);
        assertThat(optionQuote(accountB, P2).get("mark").asText()).isEqualTo("2.20");
        assertThat(stockQuote(accountB, "IXQZ").get("price").asText()).isEqualTo("61.10");

        scenarioSources();
        tickAt("2026-11-23T12:00", "SCHEDULED");
        s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("warning").isNull()).isTrue();
        assertThat(optionQuote("1", P1).get("staleLabel").isNull()).isTrue();
        assertThat(optionQuote("1", P1).get("fetchFailed").asBoolean()).isFalse();
    }

    // ============================================================================================== AC 7

    @Test
    @DisplayName("AC7 16:20 拉取另存 11-23 收盘快照；之后到次日 10:00 前（Q270，含 09:30 / 09:45）不再定时拉取、不提示；次日 10:00 照常")
    void ac7CloseSnapshot() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(stored().get("closeSnapshots")).as("no snapshot from a SCHEDULED run").isEmpty();

        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.90", "2026-11-23 16:15:00",
                        List.of(p1Option("1.10", "1.20")))))
                .cboe("IXQZ", ok(cboeBody("IXQZ", "61.50", "2026-11-23 16:15:00",
                        List.of(option(OCC_P2, "bid", "2.00", "ask", "2.20"))))).apply();
        tickAt("2026-11-23T16:20", "CLOSE");

        JsonNode snaps = stored().get("closeSnapshots");
        assertThat(snaps).hasSize(1);
        JsonNode snap = snaps.get(0);
        assertThat(snap.get("date").asText()).isEqualTo("2026-11-23");
        List<String> us = new ArrayList<>();
        snap.get("underlyings").forEach(u -> us.add(u.get("symbol").asText() + " "
                + new BigDecimal(u.get("price").asText()).stripTrailingZeros().toPlainString()));
        assertThat(us).containsExactlyInAnyOrder("QRTV 52.9", "IXQZ 61.5");
        List<String> cs = new ArrayList<>();
        snap.get("contracts").forEach(c -> cs.add(c.get("symbol").asText() + " "
                + new BigDecimal(c.get("bid").asText()).stripTrailingZeros().toPlainString()));
        assertThat(cs).containsExactlyInAnyOrder("QRTV 1.1", "IXQZ 2");
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.15");

        clearRequests();
        for (String at : List.of("2026-11-23T16:21", "2026-11-23T16:30", "2026-11-23T16:45", "2026-11-23T17:00",
                "2026-11-23T20:00", "2026-11-24T00:00", "2026-11-24T09:00", "2026-11-24T09:15", "2026-11-24T09:30",
                "2026-11-24T09:45")) {
            tickSkipped(at, "NOT_A_SLOT");
            assertThat(status().get("stale").asBoolean()).as("no top warning at %s", at).isFalse();
        }
        setNow("2026-11-24T09:59");
        assertThat(status().get("stale").asBoolean()).isFalse();
        assertThat(requests()).as("no pull between 16:20 and the next 10:00").isEmpty();

        tickAt("2026-11-24T10:00", "SCHEDULED");
        assertThat(requests()).containsExactlyInAnyOrder("CBOE QRTV", "CBOE IXQZ");
        assertThat(stored().get("closeSnapshots")).hasSize(1);
    }

    @Test
    @DisplayName("AC7 2026-11-26 感恩节：全天任何时刻都不定时拉取（NON_TRADING_DAY），也不提示")
    void ac7ThanksgivingNoPull() {
        scenario();
        scenarioSources();
        tickAt("2026-11-25T16:20", "CLOSE");
        clearRequests();
        for (String hm : List.of("10:00", "11:00", "12:00", "15:00", "16:00", "16:20")) {
            tickSkipped("2026-11-26T" + hm, "NON_TRADING_DAY");
        }
        setNow("2026-11-26T12:00");
        assertThat(status().get("stale").asBoolean()).isFalse();
        assertThat(requests()).isEmpty();
        assertThat(stored().get("closeSnapshots")).hasSize(1);
        assertThat(stored().get("closeSnapshots").get(0).get("date").asText()).isEqualTo("2026-11-25");
    }

    // ============================================================================================== AC 8

    @Test
    @DisplayName("AC8 10:05 手动 P1 Mark 1.40、QRTV 标的价 52.00（手动 11-23 10:05，B 的 P3 同样）；11:00 QRTV 失败仍 1.40；12:00 成功恢复 1.25、52.40")
    void ac8ManualOverride() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        setNow("2026-11-23T10:05");
        JsonNode o = overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");
        assertThat(o.get("mark").asText()).isEqualTo("1.40");
        assertThat(o.get("automaticMark").asText()).isEqualTo("1.25");
        assertThat(o.get("manual").asBoolean()).isTrue();
        assertThat(o.get("manualEnteredAt").asText()).isEqualTo("2026-11-23T15:05:00Z");
        assertThat(o.get("manualEnteredAtLocal").asText()).isEqualTo("2026-11-23T10:05:00");
        JsonNode u = overrideUnderlying("QRTV", "52.00");
        assertThat(u.get("price").asText()).isEqualTo("52.00");
        assertThat(u.get("automaticPrice").asText()).isEqualTo("52.40");
        assertThat(u.get("manual").asBoolean()).isTrue();
        assertThat(u.get("manualEnteredAtLocal").asText()).isEqualTo("2026-11-23T10:05:00");

        assertManual("1", P1);
        assertManual(accountB, P3);
        assertThat(optionQuote(accountB, P3)).isEqualTo(optionQuote("1", P1));

        sources().cboe("IXQZ", ixqzOk()).apply(); // QRTV: CBOE and Finnhub unreachable
        JsonNode t = tickAt("2026-11-23T11:00", "SCHEDULED");
        assertThat(results(t)).contains("QRTV FAILED NETWORK_ERROR NETWORK_ERROR null");
        assertManual("1", P1);
        assertManual(accountB, P3);

        scenarioSources();
        tickAt("2026-11-23T12:00", "SCHEDULED");
        for (String[] row : new String[][]{{"1", P1}, {accountB, P3}}) {
            JsonNode q = optionQuote(row[0], row[1]);
            assertThat(q.get("mark").asText()).isEqualTo("1.25");
            assertThat(q.get("manual").asBoolean()).isFalse();
            assertThat(q.get("manualEnteredAt").isNull()).isTrue();
            assertThat(q.get("manualEnteredAtLocal").isNull()).isTrue();
            JsonNode uq = underlyingQuote(row[0], row[1]);
            assertThat(uq.get("price").asText()).isEqualTo("52.40");
            assertThat(uq.get("manual").asBoolean()).isFalse();
            assertThat(uq.get("manualEnteredAt").isNull()).isTrue();
        }
        assertThat(storedContract("QRTV", "2026-12-18", "50", "PUT").get("manualMark").isNull()).isTrue();
        assertThat(storedUnderlying("QRTV").get("manualPrice").isNull()).isTrue();
    }

    void assertManual(String account, String contract) {
        JsonNode q = optionQuote(account, contract);
        assertThat(q.get("mark").asText()).as("mark of %s in %s", contract, account).isEqualTo("1.40");
        assertThat(q.get("automaticMark").asText()).isEqualTo("1.25");
        assertThat(q.get("bid").asText()).isEqualTo("1.20");
        assertThat(q.get("manual").asBoolean()).isTrue();
        assertThat(q.get("manualEnteredAtLocal").asText()).isEqualTo("2026-11-23T10:05:00");
        JsonNode u = underlyingQuote(account, contract);
        assertThat(u.get("price").asText()).isEqualTo("52.00");
        assertThat(u.get("automaticPrice").asText()).isEqualTo("52.40");
        assertThat(u.get("manual").asBoolean()).isTrue();
        assertThat(u.get("manualEnteredAtLocal").asText()).isEqualTo("2026-11-23T10:05:00");
    }

    // ============================================================================================== AC 9

    @Test
    @DisplayName("AC9 10:02 P1 改为 1.30 / 1.40，立即刷新 → Mark 1.35、冷却到 10:03；10:02:30 再请求 → COOLDOWN、不发 CBOE 请求")
    void ac9RefreshAndCooldown() {
        scenario();
        scenarioSources();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        setNow("2026-11-23T10:02");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 09:47:00",
                List.of(p1Option("1.30", "1.40"))))).cboe("IXQZ", ixqzOk()).apply();
        clearRequests();
        JsonNode r = refresh();
        assertThat(r.get("performed").asBoolean()).isTrue();
        assertThat(r.get("reason").isNull()).isTrue();
        assertThat(results(r)).containsExactly("IXQZ SUCCESS SUCCESS null CBOE", "QRTV SUCCESS SUCCESS null CBOE");
        JsonNode s = r.get("status");
        assertThat(s.get("lastRefreshAt").asText()).isEqualTo("2026-11-23T15:02:00Z");
        assertThat(s.get("lastRefreshAtLocal").asText()).isEqualTo("2026-11-23T10:02:00");
        assertThat(s.get("refreshAvailableAt").asText()).isEqualTo("2026-11-23T15:03:00Z");
        assertThat(s.get("refreshAllowed").asBoolean()).isFalse();
        assertThat(s.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:02:00");
        assertThat(requests()).containsExactlyInAnyOrder("CBOE QRTV", "CBOE IXQZ");
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.35");
        assertThat(optionQuote(accountB, P3).get("mark").asText()).isEqualTo("1.35");
        assertThat(status().get("refreshAllowed").asBoolean()).isFalse();

        setNow("2026-11-23T10:02:30");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 09:47:30",
                List.of(p1Option("1.50", "1.60"))))).cboe("IXQZ", ixqzOk()).apply();
        clearRequests();
        r = refresh();
        assertThat(r.get("performed").asBoolean()).isFalse();
        assertThat(r.get("reason").asText()).isEqualTo("COOLDOWN");
        assertThat(r.get("results")).isEmpty();
        assertThat(r.get("status").get("lastRefreshAtLocal").asText()).isEqualTo("2026-11-23T10:02:00");
        assertThat(r.get("status").get("refreshAllowed").asBoolean()).isFalse();
        assertThat(requests()).as("no CBOE / Finnhub request during the cooldown").isEmpty();
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.35");

        setNow("2026-11-23T10:02:59");
        assertThat(refresh().get("performed").asBoolean()).isFalse();
        assertThat(requests()).isEmpty();

        setNow("2026-11-23T10:03");
        assertThat(status().get("refreshAllowed").asBoolean()).isTrue();
        r = refresh();
        assertThat(r.get("performed").asBoolean()).isTrue();
        assertThat(r.get("status").get("refreshAvailableAt").asText()).isEqualTo("2026-11-23T15:04:00Z");
        assertThat(optionQuote("1", P1).get("mark").asText()).isEqualTo("1.55");
    }

    // ============================================================================================== AC 10

    @Test
    @DisplayName("AC10（e2e 部分）无 key、CBOE 不可达、从未成功：所有持仓无报价、顶部 行情未更新（从未成功获取）（任何时间）；录入照常")
    void ac10NeverSucceeded() {
        scenario();
        sources().noKey().apply(); // CBOE unreachable for every symbol, Finnhub NO_KEY
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).as("tracked symbols, never succeeded, before any pull").isTrue();
        assertThat(s.get("warning").asText()).isEqualTo(NEVER_SUCCEEDED_WARNING);
        assertThat(s.get("lastAttemptAt").isNull()).isTrue();

        JsonNode t = tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(results(t)).containsExactly("IXQZ FAILED NETWORK_ERROR NO_KEY null",
                "QRTV FAILED NETWORK_ERROR NO_KEY null");
        for (JsonNode res : t.get("results")) {
            assertThat(res.get("failureDetail").asText()).isNotBlank();
        }
        assertThat(requests()).as("NO_KEY sends (and records) no Finnhub request")
                .containsExactlyInAnyOrder("CBOE QRTV", "CBOE IXQZ");

        for (String[] row : new String[][]{{"1", P1}, {accountB, P2}, {accountB, P3}}) {
            JsonNode q = optionQuote(row[0], row[1]);
            assertThat(shown(q)).isEqualTo("null/null null null NEVER_QUOTED");
            assertThat(q.get("fetchFailed").asBoolean()).isTrue();
            assertThat(q.get("staleLabel").asText()).isEqualTo(NEVER_SUCCEEDED_ROW);
            JsonNode u = underlyingQuote(row[0], row[1]);
            assertThat(u.get("price").isNull()).isTrue();
            assertThat(u.get("fetchFailed").asBoolean()).isTrue();
            assertThat(u.get("staleLabel").asText()).isEqualTo(NEVER_SUCCEEDED_ROW);
        }
        JsonNode stock = stockQuote(accountB, "IXQZ");
        assertThat(stock.get("price").isNull()).isTrue();
        assertThat(stock.get("staleLabel").asText()).isEqualTo(NEVER_SUCCEEDED_ROW);

        // Q146: any time, also outside trading hours and on weekends
        for (String at : List.of("2026-11-23T10:00", "2026-11-23T20:00", "2026-11-24T08:00", "2026-11-28T12:00",
                "2026-11-26T11:00")) {
            setNow(at);
            s = status();
            assertThat(s.get("stale").asBoolean()).as("stale at %s", at).isTrue();
            assertThat(s.get("warning").asText()).isEqualTo(NEVER_SUCCEEDED_WARNING);
        }

        // rule 9: bookkeeping unaffected
        setNow("2026-11-23T10:10");
        open("STO", "QRTV", "2027-01-15", "45", "PUT", "90.00", "2026-11-23T10:05");
        assertThat(optionQuote("1", "QRTV 2027-01-15 45 P").get("mark").isNull()).isTrue();
        close("BTC", "QRTV", "2026-12-18", "50", "PUT", "130.00", "2026-11-23T10:06");
    }
}
