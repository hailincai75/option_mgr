package com.optionmgr.valuation.domain;

/** FEAT-018: whether a position row's IV / Greeks can be shown (Q170 / Q171). */
public enum GreeksStatus {
    /** Shown; a single missing field is {@code null} only (Q172). */
    OK,
    /** The contract never had a CBOE quote (never matched, index Q149), even with a manual mark: {@code 无报价}. */
    NO_QUOTE,
    /** Expiration date before today (New York) and not yet handled: {@code 已到期，待处理} (Q171). */
    EXPIRED_PENDING;

    /** The text the page shows in all Greeks columns; {@code null} for {@link #OK}. */
    public String label() {
        return switch (this) {
            case OK -> null;
            case NO_QUOTE -> "无报价";
            case EXPIRED_PENDING -> "已到期，待处理";
        };
    }
}
