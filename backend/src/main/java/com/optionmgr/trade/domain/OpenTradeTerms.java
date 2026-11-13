package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What an opening trade (BTO / STO) is, and its cash effect (Q6, Q13).
 *
 * @param contract   contract identity
 * @param action     BTO or STO
 * @param contracts  positive number of contracts
 * @param multiplier positive contract multiplier (usually 100)
 * @param netAmount  user-entered net amount incl. fees, positive, scale 2: STO = received, BTO = paid
 */
public record OpenTradeTerms(ContractKey contract, TradeAction action, int contracts, int multiplier,
                             BigDecimal netAmount) {

    public static final int DEFAULT_MULTIPLIER = 100;

    /** Collateral is rounded up to the cent (only matters for 3-decimal strikes with multiplier not a multiple of 10). */
    public static final RoundingMode COLLATERAL_ROUNDING = RoundingMode.CEILING;

    public OpenTradeTerms {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(netAmount, "netAmount");
        if (!action.isOpening()) {
            throw new IllegalArgumentException("not an opening action: " + action);
        }
        if (contracts <= 0 || multiplier <= 0) {
            throw new IllegalArgumentException("contracts and multiplier must be positive");
        }
        if (netAmount.signum() <= 0) {
            throw new IllegalArgumentException("net amount must be positive");
        }
        netAmount = Money.of(netAmount);
    }

    /** Signed change of total cash: STO +net, BTO -net. */
    public BigDecimal cashChange() {
        return action.receivesCash() ? netAmount : netAmount.negate();
    }

    /**
     * True for a cash-secured put sale, the only opening trade that freezes cash. A short call (FEAT-038) freezes
     * nothing and has no Q18 check; the user is responsible for share coverage until FEAT-011.
     */
    public boolean freezesCollateral() {
        return action == TradeAction.STO && contract.optionType() == OptionType.PUT;
    }

    /** strike x multiplier x contracts, scale 2 (rounded up to the cent). */
    public BigDecimal collateral() {
        return contract.strike()
                .multiply(BigDecimal.valueOf(multiplier))
                .multiply(BigDecimal.valueOf(contracts))
                .setScale(Money.SCALE, COLLATERAL_ROUNDING);
    }

    /** Signed change of frozen cash: STO put + collateral, otherwise 0. */
    public BigDecimal frozenChange() {
        return freezesCollateral() ? collateral() : Money.ZERO;
    }

    /**
     * Ledger description of any trade (open or close), e.g. {@code BTC 2 张 AAPL 2026-12-18 200 P}; an expiry reads
     * {@code 到期作废 3 张 XYZ 2026-11-20 45 P}.
     */
    public static String describe(TradeAction action, int contracts, int multiplier, ContractKey contract) {
        String text = action.displayName() + " " + contracts + " 张 " + contract.label();
        return multiplier == DEFAULT_MULTIPLIER ? text : text + "（multiplier " + multiplier + "）";
    }

    /** Ledger description, e.g. {@code STO 1 张 AAPL 2026-12-18 200 P} (+ "（multiplier 10）" if not 100). */
    public String description() {
        return describe(action, contracts, multiplier, contract);
    }
}
