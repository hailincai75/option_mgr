package com.optionmgr.trade.domain;

import com.optionmgr.common.time.MarketTime;

/** User-facing (Chinese) texts for rejected trades, see docs/api/api-contract.md FEAT-001. */
public final class TradeRejectionMessages {

    /** Q16, wording fixed by the requirement. */
    public static final String SELL_CALL_NOT_SUPPORTED = "需要股票持仓功能（FEAT-011）后才能卖出 call";

    private TradeRejectionMessages() {
    }

    /** Q7, for the conflict found by {@link PositionReplay#checkInsertion}. */
    public static String directionConflict(ContractKey contract, PositionInsertionCheck check, PositionStep step) {
        int held = Math.abs(step.positionBefore());
        if (check.isInserted(step)) {
            if (step.event().action() == TradeAction.BTO) {
                return "该合约（" + contract.label() + "）在成交时为空头持仓（" + held
                        + " 张），不能买入开仓；如要平仓请使用 Buy to Close（BTC）";
            }
            return "该合约（" + contract.label() + "）在成交时为多头持仓（" + held
                    + " 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）";
        }
        String side = step.positionBefore() > 0 ? "多头" : "空头";
        return "补录后该合约（" + contract.label() + "）在 " + MarketTime.displayMinute(step.event().occurredAt())
                + " 的 " + step.event().action().name() + " 将与" + side + "持仓（" + held
                + " 张）冲突；一个合约任一时刻只能纯多或纯空";
    }

    /** Q23, for the mismatch found by {@link PositionReplay#checkInsertion}. */
    public static String multiplierMismatch(ContractKey contract, PositionInsertionCheck check, PositionStep step) {
        if (check.isInserted(step)) {
            return "该合约（" + contract.label() + "）已有未平仓持仓，multiplier 为 " + step.positionMultiplier()
                    + "，新开仓的 multiplier 必须相同";
        }
        return "补录后该合约（" + contract.label() + "）在 " + MarketTime.displayMinute(step.event().occurredAt())
                + " 的交易 multiplier 为 " + step.event().multiplier() + "，与当时持仓的 multiplier "
                + step.positionMultiplier() + " 不一致";
    }
}
