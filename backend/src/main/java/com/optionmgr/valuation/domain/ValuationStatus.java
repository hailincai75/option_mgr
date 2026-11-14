package com.optionmgr.valuation.domain;

/** FEAT-017: whether a position row's market value / unrealized P&L can be computed. */
public enum ValuationStatus {
    /** Computed and counted in the totals. */
    OK,
    /** No displayed mark / price (never quoted, index, bid 0 without theo, ...): {@code 无报价}. */
    NO_QUOTE,
    /** Option expired (expiration date before today, New York) and not yet handled: {@code 已到期，待处理} (Q156). */
    EXPIRED_PENDING;

    /** The text the page shows in both columns; {@code null} for {@link #OK}. */
    public String label() {
        return switch (this) {
            case OK -> null;
            case NO_QUOTE -> "无报价";
            case EXPIRED_PENDING -> "已到期，待处理";
        };
    }
}
