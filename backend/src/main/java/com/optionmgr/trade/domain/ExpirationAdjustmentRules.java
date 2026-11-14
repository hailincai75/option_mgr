package com.optionmgr.trade.domain;

import com.optionmgr.common.time.MarketTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * FEAT-043 (exception to Q43): rules for moving a contract's expiration date off an ACTIVE ad-hoc closure. Pure
 * domain logic: the caller supplies the contract's records, the calendar and the stored facts.
 */
public final class ExpirationAdjustmentRules {

    public static final String NOT_ADHOC_CLOSURE = "只能调整到期日为临时休市日的合约";
    public static final String ALREADY_ADJUSTED = "该合约已调整过到期日，不能再次调整";
    public static final String ALREADY_SETTLED = "合约已作废 / 被指派 / 行权，不能调整到期日";

    /** Looking further back than this for a previous trading day means the calendar is broken. */
    private static final int MAX_LOOKBACK_DAYS = 31;

    private ExpirationAdjustmentRules() {
    }

    /** One stored record of the contract. */
    public record Record(long tradeId, TradeAction action, Instant tradedAt) {
        public Record {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(tradedAt, "tradedAt");
        }
    }

    /** Why an adjustment is rejected, in the check order of the contract. */
    public enum Rejection {
        NOT_ADHOC_CLOSURE,
        ALREADY_ADJUSTED,
        ALREADY_SETTLED,
        NEW_DATE_NOT_TRADING_DAY,
        NEW_DATE_OUT_OF_RANGE,
        TRADE_AFTER_NEW_CUTOFF,
        TARGET_CONTRACT_EXISTS
    }

    public record Violation(Rejection rejection, String message) {
    }

    /**
     * Everything the checks 3-9 need.
     *
     * @param records          the contract's records in the account (non-empty)
     * @param onActiveClosure  the current expiration date is an ACTIVE ad-hoc closure (FEAT-042)
     * @param alreadyAdjusted  the contract is the result of an earlier adjustment (Q131)
     * @param isTradingDay     FEAT-009 calendar at request time
     * @param targetHasRecords the contract on the new date already has records in the account (Q132)
     */
    public record Request(ContractKey contract, List<Record> records, LocalDate newExpirationDate,
                          boolean onActiveClosure, boolean alreadyAdjusted, Predicate<LocalDate> isTradingDay,
                          boolean targetHasRecords) {
        public Request {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(newExpirationDate, "newExpirationDate");
            Objects.requireNonNull(isTradingDay, "isTradingDay");
            records = List.copyOf(records);
            if (records.isEmpty()) {
                throw new IllegalArgumentException("a contract without records cannot be adjusted");
            }
        }
    }

    /** The first violated rule (contract check order 3-9), or empty when the adjustment may go ahead. */
    public static Optional<Violation> check(Request r) {
        LocalDate current = r.contract().expirationDate();
        LocalDate newDate = r.newExpirationDate();
        if (!r.onActiveClosure()) {
            return violation(Rejection.NOT_ADHOC_CLOSURE, NOT_ADHOC_CLOSURE);
        }
        if (r.alreadyAdjusted()) {
            return violation(Rejection.ALREADY_ADJUSTED, ALREADY_ADJUSTED);
        }
        if (settled(r.records())) {
            return violation(Rejection.ALREADY_SETTLED, ALREADY_SETTLED);
        }
        if (!r.isTradingDay().test(newDate)) {
            return violation(Rejection.NEW_DATE_NOT_TRADING_DAY, "新到期日 " + newDate + " 不是美股交易日");
        }
        if (!newDate.isBefore(current)) {
            return violation(Rejection.NEW_DATE_OUT_OF_RANGE, "新到期日必须早于当前到期日 " + current);
        }
        LocalDate lastTradeDate = lastTradeDate(r.records());
        if (newDate.isBefore(lastTradeDate)) {
            return violation(Rejection.NEW_DATE_OUT_OF_RANGE,
                    "新到期日不能早于该合约最后一笔记录的成交日期 " + lastTradeDate);
        }
        Instant cutoff = ExpirationTimes.tradingCutoff(newDate);
        Optional<Record> late = r.records().stream().filter(t -> t.tradedAt().isAfter(cutoff))
                .min(Comparator.comparing(Record::tradedAt).thenComparingLong(Record::tradeId));
        if (late.isPresent()) {
            Record t = late.get();
            return violation(Rejection.TRADE_AFTER_NEW_CUTOFF, "交易 #" + t.tradeId() + " " + t.action().name()
                    + " 的成交时间 " + MarketTime.displayMinute(t.tradedAt()) + " 晚于新到期日 " + newDate + " 16:00");
        }
        if (r.targetHasRecords()) {
            ContractKey target = moved(r.contract(), newDate);
            return violation(Rejection.TARGET_CONTRACT_EXISTS, "该账户已有合约 " + target.label() + " 的记录，不能合并");
        }
        return Optional.empty();
    }

    /** Q133: any expiry, assignment or exercise record (also a partial one). */
    public static boolean settled(List<Record> records) {
        return records.stream().anyMatch(t -> t.action() == TradeAction.EXP || t.action() == TradeAction.ASG
                || t.action() == TradeAction.EXR);
    }

    /** New York date of the latest record. */
    public static LocalDate lastTradeDate(List<Record> records) {
        Instant last = records.stream().map(Record::tradedAt).max(Comparator.naturalOrder()).orElseThrow();
        return MarketTime.toLocal(last).toLocalDate();
    }

    /** Default new date: the closest trading day before {@code closureDate}. */
    public static LocalDate defaultNewDate(LocalDate closureDate, Predicate<LocalDate> isTradingDay) {
        LocalDate d = closureDate.minusDays(1);
        for (int i = 0; i < MAX_LOOKBACK_DAYS; i++, d = d.minusDays(1)) {
            if (isTradingDay.test(d)) {
                return d;
            }
        }
        throw new IllegalStateException("no trading day within " + MAX_LOOKBACK_DAYS + " days before " + closureDate);
    }

    /** The same contract on another expiration date. */
    public static ContractKey moved(ContractKey contract, LocalDate newExpirationDate) {
        return new ContractKey(contract.symbol(), newExpirationDate, contract.strike(), contract.optionType());
    }

    /** Audit summary: "调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）". */
    public static String summary(ContractKey contract, LocalDate newExpirationDate, int recordCount) {
        return "调整到期日 " + contract.symbol() + " " + ContractKey.strikeText(contract.strike()) + " "
                + contract.optionType().code() + "：" + contract.expirationDate() + " → " + newExpirationDate
                + "（" + recordCount + " 条记录）";
    }

    private static Optional<Violation> violation(Rejection rejection, String message) {
        return Optional.of(new Violation(rejection, message));
    }
}
