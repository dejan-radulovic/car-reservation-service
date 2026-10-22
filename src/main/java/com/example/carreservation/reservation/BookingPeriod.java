package com.example.carreservation.reservation;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The half-open interval {@code [start, end)} a car is reserved for.
 *
 * <p>Duration is measured as elapsed time on the timeline, so it is immune to time zones and DST:
 * 10:00 to 10:00 the next day in local time is 23 h across a spring-forward night (accepted) and
 * 25 h across a fall-back night (rejected). The database enforces the same bounds.
 *
 * <p>Both instants are truncated to microseconds, PostgreSQL's precision, so the validated value is
 * exactly the stored one.
 */
public record BookingPeriod(Instant start, Instant end) {

    public static final Duration MIN_DURATION = Duration.ofHours(2);
    public static final Duration MAX_DURATION = Duration.ofHours(24);

    public BookingPeriod {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        start = start.truncatedTo(ChronoUnit.MICROS);
        end = end.truncatedTo(ChronoUnit.MICROS);

        Duration duration = Duration.between(start, end);
        if (duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(MAX_DURATION) > 0) {
            throw new InvalidBookingPeriodException(
                    "A reservation must last between 2 and 24 hours, but was " + duration);
        }
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    /** Throws unless the period starts strictly after {@code now}. */
    public void requireStartsAfter(Instant now) {
        if (!start.isAfter(now)) {
            throw new InvalidBookingPeriodException("A reservation must start in the future");
        }
    }
}
