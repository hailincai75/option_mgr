package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * FEAT-013 figures of a short-put assignment (Q8, Q74, Q96) and its time rules (Q76, Q94). Pure Java.
 * <p>FEAT-014: also the figures of a long-call exercise (Q111): with the cash-signed premium of long lots (negative)
 * and nothing released, the stock cost is purchase (rounded up) + |premium| + fee and the cash change -(purchase + fee).
 *
 * @param strike     the contract's strike
 * @param contracts  assigned contracts
 * @param multiplier the position's multiplier
 * @param fee        assignment fee, &gt;= 0, scale 2
 * @param premium    sum of the assigned lots' opening net amounts (short: positive), scale 2
 * @param released   sum of the assigned lots' frozen cash, scale 2
 */
public record AssignmentTerms(BigDecimal strike, int contracts, int multiplier, BigDecimal fee, BigDecimal premium,
                              BigDecimal released) {

    /** Q96: the purchase amount is rounded up to the cent (like the collateral, Q28). */
    public static final RoundingMode PURCHASE_ROUNDING = RoundingMode.CEILING;
    /** Q88: per-share cost, display only. */
    public static final int PER_SHARE_SCALE = 4;

    public AssignmentTerms {
        Objects.requireNonNull(strike, "strike");
        fee = Money.of(Objects.requireNonNull(fee, "fee"));
        premium = Money.of(Objects.requireNonNull(premium, "premium"));
        released = Money.of(Objects.requireNonNull(released, "released"));
    }

    /** Terms of an assignment that covers {@code match}'s lots. */
    public static AssignmentTerms of(ContractKey contract, int contracts, int multiplier, BigDecimal fee,
                                     CloseMatch match) {
        return new AssignmentTerms(contract.strike(), contracts, multiplier, fee, premium(match.coveredLots()),
                match.releasedFrozen());
    }

    /** Sum of the lots' opening net amounts (cash sign). */
    public static BigDecimal premium(List<PositionLot> lots) {
        return lots.stream().map(PositionLot::originalNetAmount).reduce(Money.ZERO, BigDecimal::add);
    }

    public int shares() {
        return contracts * multiplier;
    }

    /** strike x shares, rounded up to the cent (Q96). */
    public BigDecimal purchaseAmount() {
        return strike.multiply(BigDecimal.valueOf(shares())).setScale(Money.SCALE, PURCHASE_ROUNDING);
    }

    /** Q8: purchase amount - premium + fee. Must be &gt; 0 to be recorded. */
    public BigDecimal stockCost() {
        return purchaseAmount().subtract(premium).add(fee);
    }

    public BigDecimal stockCostPerShare() {
        return stockCost().divide(BigDecimal.valueOf(shares()), PER_SHARE_SCALE, RoundingMode.HALF_UP);
    }

    /** Signed change of total cash: -(purchase amount + fee). */
    public BigDecimal cashChange() {
        return purchaseAmount().add(fee).negate();
    }

    /** Signed change of frozen cash: -released. */
    public BigDecimal frozenChange() {
        return released.negate();
    }

    /** How much the assignment lowers available cash: purchase + fee - released (the "需要" of CASH_INSUFFICIENT). */
    public BigDecimal availableDecrease() {
        return purchaseAmount().add(fee).subtract(released);
    }

    // ------------------------------------------------------------------ time rules

    /** Q76: an assignment is recorded at 16:00 New York of its date (DST-correct). */
    public static Instant recordedAt(LocalDate assignmentDate) {
        return ExpirationTimes.tradingCutoff(assignmentDate);
    }

    /**
     * Latest assignment date selectable at {@code now}: the expiration date or today (from 16:00 New York on) /
     * yesterday (before 16:00, Q94), whichever is earlier.
     */
    public static LocalDate latestDate(LocalDate expirationDate, Instant now) {
        LocalDate today = MarketTime.toLocal(now).toLocalDate();
        LocalDate latestRecordable = now.isBefore(recordedAt(today)) ? today.minusDays(1) : today;
        return latestRecordable.isBefore(expirationDate) ? latestRecordable : expirationDate;
    }

    /**
     * Q76 / Q94 date rule, or null if the date is allowed: not later than the expiration date, not later than today,
     * and today only from 16:00 New York on.
     */
    public static String dateProblem(LocalDate assignmentDate, LocalDate expirationDate, Instant now) {
        return dateProblem("指派日期", assignmentDate, expirationDate, now);
    }

    /** As above with the field's name in the texts, e.g. "行权日期" (FEAT-014, Q81). */
    public static String dateProblem(String dateName, LocalDate date, LocalDate expirationDate, Instant now) {
        LocalDate today = MarketTime.toLocal(now).toLocalDate();
        if (date.isAfter(expirationDate)) {
            return dateName + "不能晚于到期日（" + expirationDate + "）";
        }
        if (date.isAfter(today)) {
            return dateName + "不能晚于今天";
        }
        if (recordedAt(date).isAfter(now)) {
            return dateName + "为今天时，美东 16:00 之后才能记录";
        }
        return null;
    }

    /** Ledger description: "被指派 3 张 XYZ 2026-11-20 45 P，买入 300 股 XYZ" (+ "（multiplier 1）" after the contract). */
    public static String describe(ContractKey contract, int contracts, int multiplier) {
        return describe(TradeAction.ASG, contract, contracts, multiplier);
    }

    /**
     * Ledger description of a record that buys the shares (ASG put, FEAT-014 EXR call):
     * "行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV".
     */
    public static String describe(TradeAction action, ContractKey contract, int contracts, int multiplier) {
        return OpenTradeTerms.describe(action, contracts, multiplier, contract) + "，买入 "
                + contracts * multiplier + " 股 " + contract.symbol();
    }
}
