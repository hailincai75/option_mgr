package com.optionmgr.trade.domain;

import com.optionmgr.common.time.MarketTime;
import java.util.List;

/** User-facing (Chinese) texts for rejected trades, see docs/api/api-contract.md FEAT-001 / FEAT-004. */
public final class TradeRejectionMessages {

    private TradeRejectionMessages() {
    }

    /**
     * Message for a violation found by the contract replay.
     *
     * @param atNewTrade true if {@code step} is the trade being recorded, false for a later existing trade
     */
    public static String of(ContractKey contract, ReplayedTrade step, ViolationKind kind, boolean atNewTrade) {
        return of(contract, step, kind, atNewTrade, "补录");
    }

    /** As above; {@code verb} (补录 / 修改 / 删除) names the change that made a later trade invalid. */
    public static String of(ContractKey contract, ReplayedTrade step, ViolationKind kind, boolean atNewTrade,
                            String verb) {
        return atNewTrade ? atNewTrade(contract, step, kind) : atLaterTrade(contract, step, kind, verb);
    }

    /**
     * FEAT-005: deleting an open that a later close covered and that close becomes invalid:
     * "该开仓被 T 的 BTC 2 张平掉（删除后那笔平仓将超过当时持仓 1 张），请先修改或删除那笔平仓".
     */
    public static String deletedOpenStillClosed(ReplayedTrade close, ViolationKind kind) {
        return "该开仓被 " + MarketTime.displayMinute(close.trade().tradedAt()) + " 的 " + close.trade().action().name()
                + " " + close.trade().contracts() + " 张平掉（删除后那笔平仓将" + closeReason(close, kind)
                + "），请先修改或删除那笔平仓";
    }

    /**
     * FEAT-012: editing / deleting an open that an expiry (EXP) covers makes that expiry invalid:
     * "该开仓被 2026-11-20 16:00 的到期作废 3 张覆盖（删除后作废将超过当时持仓 2 张），请先删除该到期作废".
     */
    public static String expiredOpenChanged(ReplayedTrade expiry, ViolationKind kind, String verb) {
        return "该开仓被 " + MarketTime.displayMinute(expiry.trade().tradedAt()) + " 的到期作废 "
                + expiry.trade().contracts() + " 张覆盖（" + verb + "后作废将" + expiryReason(expiry, kind)
                + "），请先删除该到期作废";
    }

    /** Why an expiry is invalid at its point in the replay (no "将" prefix). */
    private static String expiryReason(ReplayedTrade s, ViolationKind kind) {
        return switch (kind) {
            case NO_POSITION -> "没有持仓可作废";
            case CLOSE_EXCEEDS_POSITION -> "超过当时持仓 " + Math.abs(s.positionBefore()) + " 张";
            case CLOSE_NOT_WHOLE_LOTS -> "不是整笔批次（最早一笔是 " + s.earliestLotContracts() + " 张，"
                    + allowedText("可为 ", s.allowedCloseContracts()) + "）";
            case MULTIPLIER_MISMATCH -> "与当时持仓的 multiplier " + s.multiplierBefore() + " 不一致";
            // an expiry has no side: these cannot happen; kept generic
            case CLOSE_WRONG_DIRECTION, OPEN_AGAINST_POSITION -> "与持仓方向冲突";
        };
    }

    private static String atLaterExpiry(ContractKey c, ReplayedTrade s, ViolationKind kind, String verb) {
        String head = verb + "后该合约（" + c.label() + "）在 " + MarketTime.displayMinute(s.trade().tradedAt())
                + " 的到期作废";
        String body = kind == ViolationKind.MULTIPLIER_MISMATCH
                ? " multiplier 为 " + s.effectiveMultiplier() + "，与当时持仓的 multiplier " + s.multiplierBefore()
                        + " 不一致"
                : " " + s.trade().contracts() + " 张将" + expiryReason(s, kind);
        return head + body + "，请先删除该到期作废";
    }

    private static String closeReason(ReplayedTrade s, ViolationKind kind) {
        int held = Math.abs(s.positionBefore());
        String side = s.positionBefore() > 0 ? "多头" : "空头";
        return switch (kind) {
            case NO_POSITION -> "没有持仓可平";
            case CLOSE_WRONG_DIRECTION -> "与持仓方向不符（当时为" + side + "持仓 " + held + " 张）";
            case CLOSE_EXCEEDS_POSITION -> "超过当时持仓 " + held + " 张";
            case CLOSE_NOT_WHOLE_LOTS -> "不是整笔开仓（最早一笔是 " + s.earliestLotContracts() + " 张，"
                    + allowedText(s.allowedCloseContracts()) + "）";
            case MULTIPLIER_MISMATCH -> "与当时持仓的 multiplier " + s.multiplierBefore() + " 不一致";
            case OPEN_AGAINST_POSITION -> "与持仓方向冲突";
        };
    }

    private static String atNewTrade(ContractKey c, ReplayedTrade s, ViolationKind kind) {
        if (s.trade().action() == TradeAction.EXP) {
            return kind == ViolationKind.NO_POSITION
                    ? noPositionToExpire(c)
                    : "到期作废 " + s.trade().contracts() + " 张将" + expiryReason(s, kind);
        }
        int held = Math.abs(s.positionBefore());
        String side = s.positionBefore() > 0 ? "多头" : "空头";
        return switch (kind) {
            case OPEN_AGAINST_POSITION -> s.trade().action() == TradeAction.BTO
                    ? "该合约（" + c.label() + "）在成交时为空头持仓（" + held
                            + " 张），不能买入开仓；如要平仓请使用 Buy to Close（BTC）"
                    : "该合约（" + c.label() + "）在成交时为多头持仓（" + held
                            + " 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）";
            case MULTIPLIER_MISMATCH -> "该合约（" + c.label() + "）已有未平仓持仓，multiplier 为 "
                    + s.multiplierBefore() + "，新开仓的 multiplier 必须相同";
            case NO_POSITION -> "该合约（" + c.label() + "）在成交时没有持仓，无法平仓";
            case CLOSE_WRONG_DIRECTION -> "该合约（" + c.label() + "）在成交时为" + side + "持仓（" + held + " 张），应使用 "
                    + (s.positionBefore() > 0 ? "Sell to Close（STC）" : "Buy to Close（BTC）");
            case CLOSE_EXCEEDS_POSITION -> "平仓 " + s.trade().contracts() + " 张超过当时持仓 " + held + " 张";
            case CLOSE_NOT_WHOLE_LOTS -> "必须一次平掉整笔开仓：最早一笔是 " + s.earliestLotContracts() + " 张，"
                    + allowedText(s.allowedCloseContracts());
        };
    }

    private static String atLaterTrade(ContractKey c, ReplayedTrade s, ViolationKind kind, String verb) {
        if (s.trade().action() == TradeAction.EXP) {
            return atLaterExpiry(c, s, kind, verb);
        }
        String when = MarketTime.displayMinute(s.trade().tradedAt());
        int held = Math.abs(s.positionBefore());
        String side = s.positionBefore() > 0 ? "多头" : "空头";
        String prefix = verb + "后该合约（" + c.label() + "）在 " + when + " 的 " + s.trade().action().name() + " "
                + s.trade().contracts() + " 张将";
        return switch (kind) {
            // FEAT-001 wordings kept for opens
            case OPEN_AGAINST_POSITION -> verb + "后该合约（" + c.label() + "）在 " + when + " 的 "
                    + s.trade().action().name() + " 将与" + side + "持仓（" + held
                    + " 张）冲突；一个合约任一时刻只能纯多或纯空";
            case MULTIPLIER_MISMATCH -> verb + "后该合约（" + c.label() + "）在 " + when + " 的交易 multiplier 为 "
                    + s.effectiveMultiplier() + "，与当时持仓的 multiplier " + s.multiplierBefore() + " 不一致";
            case NO_POSITION -> prefix + "没有持仓可平";
            case CLOSE_WRONG_DIRECTION -> prefix + "与持仓方向不符（当时为" + side + "持仓 " + held + " 张）";
            case CLOSE_EXCEEDS_POSITION -> prefix + "超过当时持仓 " + held + " 张";
            case CLOSE_NOT_WHOLE_LOTS -> prefix + "不是整笔开仓（最早一笔是 " + s.earliestLotContracts() + " 张，"
                    + allowedText(s.allowedCloseContracts()) + "）";
        };
    }

    /** FEAT-012 operation step 3: "该合约（XYZ 2026-11-20 50 P）没有持仓，无法作废". */
    public static String noPositionToExpire(ContractKey c) {
        return "该合约（" + c.label() + "）没有持仓，无法作废";
    }

    /** "可平 1 张" / "可平 2 张或 3 张" / "可平 1 张、3 张或 4 张". */
    static String allowedText(List<Integer> allowed) {
        return allowedText("可平 ", allowed);
    }

    /** As above with another lead-in, e.g. "可为 2 张、4 张或 5 张" for an expiry. */
    static String allowedText(String lead, List<Integer> allowed) {
        StringBuilder sb = new StringBuilder(lead);
        for (int i = 0; i < allowed.size(); i++) {
            if (i > 0) {
                sb.append(i == allowed.size() - 1 ? "或 " : "、");
            }
            sb.append(allowed.get(i)).append(" 张");
        }
        return sb.toString();
    }
}
