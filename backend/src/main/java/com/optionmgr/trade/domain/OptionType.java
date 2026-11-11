package com.optionmgr.trade.domain;

/** Call or put. Persisted by name; {@link #code()} is the one-letter form used in labels ("C" / "P"). */
public enum OptionType {
    CALL("C"),
    PUT("P");

    private final String code;

    OptionType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
