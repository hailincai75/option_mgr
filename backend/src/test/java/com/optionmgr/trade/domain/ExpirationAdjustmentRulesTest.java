package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** FEAT-043 rules 3.1-3.6 and their order (fictional QRTV). */
class ExpirationAdjustmentRulesTest {

    static final LocalDate DEC4 = LocalDate.parse("2026-12-04");
    static final LocalDate DEC3 = LocalDate.parse("2026-12-03");
    static final ContractKey P1 = new ContractKey("QRTV", DEC4, new BigDecimal("50"), OptionType.PUT);
    /** Weekends and the 2026-12-04 closure are not trading days. */
    static final Predicate<LocalDate> CALENDAR = d -> !d.equals(DEC4)
            && d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    static List<ExpirationAdjustmentRules.Record> p1Records() {
        return List.of(new ExpirationAdjustmentRules.Record(1, TradeAction.STO, ny("2026-11-20T10:00")),
                new ExpirationAdjustmentRules.Record(2, TradeAction.STO, ny("2026-11-24T10:00")),
                new ExpirationAdjustmentRules.Record(3, TradeAction.BTC, ny("2026-11-25T10:00")));
    }

    static ExpirationAdjustmentRules.Request request(List<ExpirationAdjustmentRules.Record> records, LocalDate newDate,
                                                     boolean closure, boolean adjusted, boolean target) {
        return new ExpirationAdjustmentRules.Request(P1, records, newDate, closure, adjusted, CALENDAR, target);
    }

    static Optional<ExpirationAdjustmentRules.Rejection> rejection(ExpirationAdjustmentRules.Request r) {
        return ExpirationAdjustmentRules.check(r).map(ExpirationAdjustmentRules.Violation::rejection);
    }

    static String message(ExpirationAdjustmentRules.Request r) {
        return ExpirationAdjustmentRules.check(r).orElseThrow().message();
    }

    @Test
    void validAdjustmentPasses() {
        assertThat(ExpirationAdjustmentRules.check(request(p1Records(), DEC3, true, false, false))).isEmpty();
    }

    @Test
    void notAnActiveClosureIsRejectedFirst() {
        // AC 6: even with every other rule broken too
        ExpirationAdjustmentRules.Request r = request(List.of(new ExpirationAdjustmentRules.Record(1, TradeAction.EXP,
                ny("2026-12-04T16:00"))), LocalDate.parse("2026-12-05"), false, true, true);
        assertThat(rejection(r)).contains(ExpirationAdjustmentRules.Rejection.NOT_ADHOC_CLOSURE);
        assertThat(message(r)).isEqualTo("只能调整到期日为临时休市日的合约");
    }

    @Test
    void adjustedContractCannotBeAdjustedAgain() {
        assertThat(message(request(p1Records(), DEC3, true, true, false))).isEqualTo("该合约已调整过到期日，不能再次调整");
    }

    @Test
    void settledContractsAreRejected_includingPartialAssignmentAndExercise() {
        for (TradeAction a : List.of(TradeAction.EXP, TradeAction.ASG, TradeAction.EXR)) {
            List<ExpirationAdjustmentRules.Record> records = new java.util.ArrayList<>(p1Records());
            records.add(new ExpirationAdjustmentRules.Record(9, a, ny("2026-11-26T10:00")));
            ExpirationAdjustmentRules.Request r = request(records, DEC3, true, false, false);
            assertThat(rejection(r)).as(a.name()).contains(ExpirationAdjustmentRules.Rejection.ALREADY_SETTLED);
            assertThat(message(r)).isEqualTo("合约已作废 / 被指派 / 行权，不能调整到期日");
        }
    }

    @Test
    void newDateMustBeATradingDay() {
        // AC 7
        ExpirationAdjustmentRules.Request r = request(p1Records(), LocalDate.parse("2026-12-05"), true, false, false);
        assertThat(rejection(r)).contains(ExpirationAdjustmentRules.Rejection.NEW_DATE_NOT_TRADING_DAY);
        assertThat(message(r)).isEqualTo("新到期日 2026-12-05 不是美股交易日");
    }

    @Test
    void newDateMustBeEarlierThanTheCurrentOne() {
        ExpirationAdjustmentRules.Request r = request(p1Records(), LocalDate.parse("2026-12-07"), true, false, false);
        assertThat(rejection(r)).contains(ExpirationAdjustmentRules.Rejection.NEW_DATE_OUT_OF_RANGE);
        assertThat(message(r)).isEqualTo("新到期日必须早于当前到期日 2026-12-04");
    }

    @Test
    void newDateMustNotBeBeforeTheLastTradeDate() {
        ExpirationAdjustmentRules.Request r = request(p1Records(), LocalDate.parse("2026-11-24"), true, false, false);
        assertThat(rejection(r)).contains(ExpirationAdjustmentRules.Rejection.NEW_DATE_OUT_OF_RANGE);
        assertThat(message(r)).isEqualTo("新到期日不能早于该合约最后一笔记录的成交日期 2026-11-25");
        // the last trade date itself is allowed, and a past date is fine (Q131)
        assertThat(ExpirationAdjustmentRules.check(request(p1Records(), LocalDate.parse("2026-11-25"), true, false,
                false))).isEmpty();
    }

    @Test
    void everyRecordMustBeAtOrBefore16OnTheNewDate() {
        List<ExpirationAdjustmentRules.Record> records = List.of(
                new ExpirationAdjustmentRules.Record(1, TradeAction.STO, ny("2026-12-03T16:00")),
                new ExpirationAdjustmentRules.Record(2, TradeAction.BTC, ny("2026-12-03T16:30")));
        ExpirationAdjustmentRules.Request r = request(records, DEC3, true, false, false);
        assertThat(rejection(r)).contains(ExpirationAdjustmentRules.Rejection.TRADE_AFTER_NEW_CUTOFF);
        assertThat(message(r)).isEqualTo("交易 #2 BTC 的成交时间 2026-12-03 16:30 晚于新到期日 2026-12-03 16:00");
        assertThat(ExpirationAdjustmentRules.check(request(records.subList(0, 1), DEC3, true, false, false))).isEmpty();
    }

    @Test
    void existingTargetContractIsRejected() {
        ExpirationAdjustmentRules.Request r = request(p1Records(), DEC3, true, false, true);
        assertThat(rejection(r)).contains(ExpirationAdjustmentRules.Rejection.TARGET_CONTRACT_EXISTS);
        assertThat(message(r)).isEqualTo("该账户已有合约 QRTV 2026-12-03 50 P 的记录，不能合并");
    }

    @Test
    void defaultNewDateIsThePreviousTradingDay() {
        assertThat(ExpirationAdjustmentRules.defaultNewDate(DEC4, CALENDAR)).isEqualTo(DEC3);
        // a Monday closure falls back over the weekend; over the weekend and another closure
        assertThat(ExpirationAdjustmentRules.defaultNewDate(LocalDate.parse("2026-12-14"), CALENDAR))
                .isEqualTo(LocalDate.parse("2026-12-11"));
        assertThat(ExpirationAdjustmentRules.defaultNewDate(LocalDate.parse("2026-12-07"), CALENDAR))
                .isEqualTo(DEC3);
    }

    @Test
    void summaryAndLastTradeDate() {
        assertThat(ExpirationAdjustmentRules.summary(P1, DEC3, 3))
                .isEqualTo("调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）");
        assertThat(ExpirationAdjustmentRules.lastTradeDate(p1Records())).isEqualTo(LocalDate.parse("2026-11-25"));
    }
}
