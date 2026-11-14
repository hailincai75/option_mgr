package com.optionmgr.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * FEAT-009 (e2e profile only): the system UTC clock, unless a fixed "now" was set through
 * {@code PUT /api/test-support/clock}. A fixed clock does not move.
 */
public final class SettableClock extends Clock {

    private volatile Instant fixed;

    public void set(Instant now) {
        this.fixed = now;
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Instant f = fixed;
        return f == null ? Clock.system(zone) : Clock.fixed(f, zone);
    }

    @Override
    public Instant instant() {
        Instant f = fixed;
        return f == null ? Instant.now() : f;
    }
}
