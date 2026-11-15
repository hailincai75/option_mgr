package com.optionmgr.account.web;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FEAT-035: the accounts a read request covers, from the {@value AccountRef#HEADER} request header: one account
 * ({@code X-Account-Id: <id>}, as {@link AccountRef}) or every account that exists at request time
 * ({@code X-Account-Id: ALL}, read-only). Only the endpoints listed in docs/api/FEAT-035.md declare this parameter;
 * every other account-scoped endpoint keeps {@link AccountRef}, which rejects {@code ALL}.
 *
 * @param all      true for {@code ALL}
 * @param accounts the accounts covered, by id ascending (one for a single account; at least one for {@code ALL})
 */
public record AccountScope(boolean all, List<Member> accounts) {

    /** The header value for all accounts (compared after strip, case-insensitively). */
    public static final String ALL = "ALL";

    /** One account of the scope: id and its current name. */
    public record Member(long id, String name) {
    }

    public AccountScope {
        accounts = List.copyOf(accounts);
    }

    public List<Long> ids() {
        return accounts.stream().map(Member::id).toList();
    }

    /** FEAT-046: account id to its current name (for rows that carry their account). */
    public Map<Long, String> names() {
        return accounts.stream().collect(Collectors.toUnmodifiableMap(Member::id, Member::name));
    }

    /** True when a (non-blank) header value means all accounts. */
    public static boolean isAll(String raw) {
        return raw != null && raw.strip().equalsIgnoreCase(ALL);
    }
}
