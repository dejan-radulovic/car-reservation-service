package com.example.carreservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock for integration tests: system time by default, or a fixed instant after
 * {@link #setInstant}. Tests that fix it must {@link #reset} it, since the context is shared.
 */
public class MutableClock extends Clock {

    private final Clock system = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    private volatile Instant fixed;

    public void setInstant(Instant instant) {
        this.fixed = instant;
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public Instant instant() {
        Instant current = fixed;
        return current != null ? current : system.instant();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the application works in UTC only");
    }
}
