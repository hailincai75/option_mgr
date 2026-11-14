package com.optionmgr.calendar.service;

/** FEAT-009: the raw outcome of one Finnhub request (or of not making one), before parsing. Never holds the key. */
public sealed interface FetchAttempt {

    /** No key configured: no request was made. */
    record NoKey() implements FetchAttempt {
    }

    /** Connection failure / timeout; {@code detail} is a short description without URL query or key. */
    record NetworkError(String detail) implements FetchAttempt {
    }

    /** A response arrived: its HTTP status and body (body may be null). */
    record Response(int status, String body) implements FetchAttempt {
    }
}
