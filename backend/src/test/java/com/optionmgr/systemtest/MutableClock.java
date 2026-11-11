package com.optionmgr.systemtest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock whose "now" each system test sets explicitly (shared Spring context, so it is reset per test). */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
