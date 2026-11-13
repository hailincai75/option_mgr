package com.optionmgr.account.domain;

import java.util.Locale;

/** Q51 account name / broker note rules. Pure Java. */
public final class AccountNames {

    public static final int MAX_NAME_LENGTH = 30;
    public static final int MAX_BROKER_NOTE_LENGTH = 50;
    /** Q52: the account that holds all M1 data, and the only account of a new database. */
    public static final long DEFAULT_ACCOUNT_ID = 1L;

    private AccountNames() {
    }

    /** Trimmed name (null stays null). */
    public static String normalize(String name) {
        return name == null ? null : name.strip();
    }

    /** Uniqueness key: trimmed, lower-cased (case-insensitive comparison; Chinese text is unchanged). */
    public static String key(String name) {
        return normalize(name).toLowerCase(Locale.ROOT);
    }

    /** Trimmed broker note; blank = none. */
    public static String normalizeBrokerNote(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }
}
