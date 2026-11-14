package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * FEAT-041 figures of a short-call assignment (Q8 provisional, Q74, Q99). The shares are delivered at the strike;
 * the premium of the assigned lots is added to the sale proceeds, so the option's realized P&amp;L is 0 and the stock
 * sale carries it. The time rules are those of {@link AssignmentTerms}. Pure Java.
 * <p>FEAT-014: also the figures of a long-put exercise (Q111): with the cash-signed premium of long lots (negative)
 * the proceeds are strike amount (rounded down) - |premium| - fee and the cash change +(strike amount - fee).
 *
 * @param strike     the contract's strike
 * @param contracts  assigned contracts
 * @param multiplier the position's multiplier
 * @param fee        assignment fee, &gt;= 0, scale 2
 * @param premium    sum of the assigned lots' opening net amounts (short: positive), scale 2
 */
public record CallAssignmentTerms(BigDecimal strike, int contracts, int multiplier, BigDecimal fee,
                                  BigDecimal premium) {

    /** Q99: the strike amount is rounded down to the cent. */
    public static final RoundingMode SALE_ROUNDING = RoundingMode.DOWN;

    public CallAssignmentTerms {
        Objects.requireNonNull(strike, "strike");
        fee = Money.of(Objects.requireNonNull(fee, "fee"));
        premium = Money.of(Objects.requireNonNull(premium, "premium"));
    }

    /** Terms of an assignment that covers {@code match}'s lots. */
    public static CallAssignmentTerms of(ContractKey contract, int contracts, int multiplier, BigDecimal fee,
                                         CloseMatch match) {
        return new CallAssignmentTerms(contract.strike(), contracts, multiplier, fee,
                AssignmentTerms.premium(match.coveredLots()));
    }

    public int shares() {
        return contracts * multiplier;
    }

    /** strike x shares, rounded down to the cent (Q99). */
    public BigDecimal saleAmount() {
        return strike.multiply(BigDecimal.valueOf(shares())).setScale(Money.SCALE, SALE_ROUNDING);
    }

    /** Q8 (provisional): sale amount + premium - fee. Must be &gt; 0 to be recorded. */
    public BigDecimal proceeds() {
        return saleAmount().add(premium).subtract(fee);
    }

    /** Signed change of total cash: +(sale amount - fee); the premium was received at opening. */
    public BigDecimal cashChange() {
        return saleAmount().subtract(fee);
    }

    /** A short call freezes nothing (FEAT-038). */
    public BigDecimal frozenChange() {
        return Money.ZERO;
    }

    /** How much the assignment lowers available cash (the "需要" of CASH_INSUFFICIENT): fee - sale amount. */
    public BigDecimal availableDecrease() {
        return fee.subtract(saleAmount());
    }

    /** Ledger description: "被指派 3 张 IXQZ 2026-11-20 9 C，卖出 300 股 IXQZ" (+ "（multiplier 1）" after the contract). */
    public static String describe(ContractKey contract, int contracts, int multiplier) {
        return describe(TradeAction.ASG, contract, contracts, multiplier);
    }

    /**
     * Ledger description of a record that sells the shares (ASG call, FEAT-014 EXR put):
     * "行权 1 张 XYZ 2026-11-20 45 P，卖出 100 股 XYZ".
     */
    public static String describe(TradeAction action, ContractKey contract, int contracts, int multiplier) {
        return OpenTradeTerms.describe(action, contracts, multiplier, contract) + "，卖出 "
                + contracts * multiplier + " 股 " + contract.symbol();
    }
}
