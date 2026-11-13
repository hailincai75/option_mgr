package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.audit.domain.SideEffect;
import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-012: 16:00 / 17:30 New York on the expiration date (DST), expiry replay, texts. */
class ExpirationTimesTest {

    private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);
    private static final ContractKey XYZ = new ContractKey("XYZ", NOV_20, new BigDecimal("45"), OptionType.PUT);

    @Test
    void cutoffAndExpirableTimesFollowNewYorkDst() {
        assertThat(ExpirationTimes.tradingCutoff(NOV_20)).isEqualTo(Instant.parse("2026-11-20T21:00:00Z")); // EST
        assertThat(ExpirationTimes.expirableFrom(NOV_20)).isEqualTo(Instant.parse("2026-11-20T22:30:00Z"));
        LocalDate mar19 = LocalDate.of(2027, 3, 19);
        assertThat(ExpirationTimes.tradingCutoff(mar19)).isEqualTo(Instant.parse("2027-03-19T20:00:00Z")); // EDT
        assertThat(ExpirationTimes.expirableFrom(mar19)).isEqualTo(Instant.parse("2027-03-19T21:30:00Z"));
    }

    @Test
    void closablePendingExpirableAtTheBoundaries() {
        record Case(String at, boolean closable, boolean pending, boolean expirable) {
        }
        for (Case c : List.of(
                new Case("2026-11-17T15:00:00Z", true, false, false),
                new Case("2026-11-20T20:59:59Z", true, false, false),   // 15:59:59
                new Case("2026-11-20T21:00:00Z", false, true, false),   // 16:00
                new Case("2026-11-20T22:29:59Z", false, true, false),   // 17:29:59
                new Case("2026-11-20T22:30:00Z", false, false, true),   // 17:30
                new Case("2026-11-21T14:00:00Z", false, false, true))) {
            Instant now = Instant.parse(c.at());
            assertThat(ExpirationTimes.closable(NOV_20, now)).as(c.at()).isEqualTo(c.closable());
            assertThat(ExpirationTimes.expiryPending(NOV_20, now)).as(c.at()).isEqualTo(c.pending());
            assertThat(ExpirationTimes.expirable(NOV_20, now)).as(c.at()).isEqualTo(c.expirable());
        }
    }

    @Test
    void tradeTimeExactlyAtCutoffIsAllowed() {
        assertThat(ExpirationTimes.afterCutoff(NOV_20, Instant.parse("2026-11-20T21:00:00Z"))).isFalse();
        assertThat(ExpirationTimes.afterCutoff(NOV_20, Instant.parse("2026-11-20T21:00:01Z"))).isTrue();
        assertThat(ExpirationTimes.afterCutoffMessage(NOV_20)).isEqualTo("成交时间不能晚于到期日（2026-11-20）美东 16:00");
    }

    private static LotTrade sto(long id, String at, int contracts, String net, String frozen) {
        return new LotTrade(id, Instant.parse(at), TradeAction.STO, contracts, 100, Money.of(net), Money.of(frozen), null);
    }

    private static LotTrade exp(long id, int contracts, int multiplier) {
        return new LotTrade(id, ExpirationTimes.tradingCutoff(NOV_20), TradeAction.EXP, contracts, multiplier,
                Money.ZERO, Money.ZERO, null);
    }

    @Test
    void expiryCoversWholeLotsLikeACloseWithoutSide() {
        LotTrade e1 = sto(1, "2026-11-03T15:00:00Z", 2, "240.00", "9000.00");
        LotTrade e3 = sto(3, "2026-11-05T15:00:00Z", 1, "130.00", "4500.00");
        ContractHistory h = ContractReplay.replay(List.of(e1, e3, exp(7, 3, 100)));
        CloseMatch m = h.step(7).match().orElseThrow();
        assertThat(m.coveredLots()).extracting(PositionLot::tradeId).containsExactly(1L, 3L);
        assertThat(m.realizedPnl()).isEqualByComparingTo("370.00");
        assertThat(m.releasedFrozen()).isEqualByComparingTo("13500.00");
        assertThat(h.openLots()).isEmpty();

        // a long position expires the same way: P&L = - opening paid
        LotTrade bto = new LotTrade(2, Instant.parse("2026-11-04T15:00:00Z"), TradeAction.BTO, 1, 100,
                Money.of("-85.65"), Money.ZERO, null);
        CloseMatch longMatch = ContractReplay.replay(List.of(bto, exp(8, 1, 100))).step(8).match().orElseThrow();
        assertThat(longMatch.realizedPnl()).isEqualByComparingTo("-85.65");
        assertThat(longMatch.releasedFrozen()).isEqualByComparingTo("0.00");
        assertThat(new ClosedTrade(TradeAction.EXP, 1, Money.ZERO, longMatch).closedDirection())
                .isEqualTo(PositionDirection.LONG);
        assertThat(new ClosedTrade(TradeAction.EXP, 3, Money.ZERO, m).closedDirection())
                .isEqualTo(PositionDirection.SHORT);
    }

    @Test
    void expiryViolationsAndTheirTexts() {
        LotTrade n = sto(9, "2026-11-02T16:00:00Z", 2, "200.00", "9000.00");
        LotTrade e1 = sto(1, "2026-11-03T15:00:00Z", 2, "240.00", "9000.00");
        LotTrade e3 = sto(3, "2026-11-05T15:00:00Z", 1, "130.00", "4500.00");
        ReplayedTrade notWhole = ContractReplay.replay(List.of(n, e1, e3, exp(7, 3, 100))).step(7);
        assertThat(notWhole.violations()).containsExactly(ViolationKind.CLOSE_NOT_WHOLE_LOTS);
        assertThat(TradeRejectionMessages.of(XYZ, notWhole, ViolationKind.CLOSE_NOT_WHOLE_LOTS, false, "补录"))
                .isEqualTo("补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张将不是整笔批次"
                        + "（最早一笔是 2 张，可为 2 张、4 张或 5 张），请先删除该到期作废");

        ReplayedTrade exceeds = ContractReplay.replay(List.of(e1, exp(7, 3, 100))).step(7);
        assertThat(TradeRejectionMessages.expiredOpenChanged(exceeds, ViolationKind.CLOSE_EXCEEDS_POSITION, "删除"))
                .isEqualTo("该开仓被 2026-11-20 16:00 的到期作废 3 张覆盖（删除后作废将超过当时持仓 2 张），请先删除该到期作废");

        ReplayedTrade mult = ContractReplay.replay(List.of(e1, exp(7, 2, 10))).step(7);
        assertThat(mult.violations()).containsExactly(ViolationKind.MULTIPLIER_MISMATCH);
        assertThat(TradeRejectionMessages.of(XYZ, mult, ViolationKind.MULTIPLIER_MISMATCH, false, "修改"))
                .isEqualTo("修改后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 multiplier 为 10，"
                        + "与当时持仓的 multiplier 100 不一致，请先删除该到期作废");

        ReplayedTrade none = ContractReplay.replay(List.of(exp(7, 1, 100))).step(7);
        assertThat(none.violations()).containsExactly(ViolationKind.NO_POSITION);
        assertThat(TradeRejectionMessages.noPositionToExpire(XYZ)).isEqualTo("该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废");
    }

    @Test
    void expiryReplayLooksAtTheCutoffAndFindsLateTrades() {
        LotTrade e1 = sto(1, "2026-11-03T15:00:00Z", 2, "240.00", "9000.00");
        LotTrade e3 = sto(3, "2026-11-05T15:00:00Z", 1, "130.00", "4500.00");
        LotTrade lateClose = new LotTrade(9, Instant.parse("2026-11-20T21:10:00Z"), TradeAction.BTC, 2, 100,
                Money.of("-30.00"), Money.ZERO, null);
        LotTrade atCutoff = sto(10, "2026-11-20T21:00:00Z", 1, "50.00", "4500.00");
        assertThat(ExpiryReplay.contractsAtCutoff(XYZ, List.of(e1, e3, lateClose))).isEqualTo(3);
        assertThat(ExpiryReplay.firstLateTrade(XYZ, List.of(e1, e3, lateClose))).contains(lateClose);
        assertThat(ExpiryReplay.contractsAtCutoff(XYZ, List.of(e1, e3, atCutoff))).isEqualTo(4);
        assertThat(ExpiryReplay.firstLateTrade(XYZ, List.of(e1, e3, atCutoff))).isEmpty();
    }

    @Test
    void ledgerDescriptionAndSideEffectTextOfAnExpiry() {
        assertThat(OpenTradeTerms.describe(TradeAction.EXP, 3, 100, XYZ)).isEqualTo("到期作废 3 张 XYZ 2026-11-20 45 P");
        ContractKey qfz = new ContractKey("QFZ", NOV_20, new BigDecimal("12.345"), OptionType.PUT);
        assertThat(OpenTradeTerms.describe(TradeAction.EXP, 2, 1, qfz))
                .isEqualTo("到期作废 2 张 QFZ 2026-11-20 12.345 P（multiplier 1）");
        assertThat(new SideEffect(17, "EXP", "QFZ 2026-11-20 12.345 P", "frozenChange", "−24.70", "−24.69").text())
                .isEqualTo("到期作废 #17 冻结变动 −24.70 → −24.69");
        assertThat(new SideEffect(3, "BTC", "QFZ 2026-11-20 12.345 P", "frozenChange", "−24.70", "−24.69").text())
                .isEqualTo("交易 #3 BTC 冻结变动 −24.70 → −24.69");
    }
}
