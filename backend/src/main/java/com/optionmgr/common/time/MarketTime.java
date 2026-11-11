package com.optionmgr.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * US market time (America/New_York) conversions. Users enter local NY date-times; we store UTC instants.
 * Pure Java, no Spring.
 */
public final class MarketTime {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Input format for user-entered NY local date-times: {@code YYYY-MM-DDTHH:mm} or {@code YYYY-MM-DDTHH:mm:ss}.
     * STRICT resolver ('uuuu' = proleptic year) so impossible dates such as 2026-02-30 are rejected instead of
     * being adjusted to the end of the month (BUG-001). No fractional seconds, no offset/zone suffix.
     */
    public static final DateTimeFormatter LOCAL_INPUT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);

    private MarketTime() {
    }

    /**
     * Parses a user-entered New York local date-time (see {@link #LOCAL_INPUT}).
     *
     * @throws DateTimeParseException if the text is malformed or names a non-existent date/time
     */
    public static LocalDateTime parseLocal(String text) {
        return LocalDateTime.parse(text, LOCAL_INPUT);
    }

    /**
     * Interprets {@code local} as New York wall-clock time, using java.time default rules
     * ({@link ZonedDateTime#of}): a time inside the spring-forward gap is shifted later by the gap length
     * (02:30 becomes 03:30 EDT); an ambiguous time in the fall-back overlap takes the earlier offset (EDT).
     */
    public static Instant toInstant(LocalDateTime local) {
        return ZonedDateTime.of(local, ZONE).toInstant();
    }

    public static LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE);
    }

    /** {@code yyyy-MM-dd HH:mm} in New York time, for user-facing messages. */
    public static String displayMinute(Instant instant) {
        return MINUTE.format(toLocal(instant));
    }
}
