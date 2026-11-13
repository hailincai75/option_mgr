package com.optionmgr.account.web;

/**
 * FEAT-033: the account a request works in, from the {@value #HEADER} request header. Declare it as the FIRST
 * parameter of an account-scoped controller method; {@link AccountRefArgumentResolver} rejects a missing, malformed
 * or unknown account before anything else is read.
 */
public record AccountRef(long id) {

    public static final String HEADER = "X-Account-Id";
}
