package com.example.carreservation.car;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The half-open interval {@code [from, to)} searched for available cars. Unlike a booking it has no
 * minimum length, but it is capped so that a single search stays cheap.
 */
public record SearchWindow(Instant from, Instant to) {

    public static final Duration MAX_LENGTH = Duration.ofDays(31);

    public SearchWindow {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (!from.isBefore(to)) {
            throw new InvalidSearchWindowException("'from' must be before 'to'");
        }
        if (Duration.between(from, to).compareTo(MAX_LENGTH) > 0) {
            throw new InvalidSearchWindowException("The search window must not be longer than 31 days");
        }
    }
}
