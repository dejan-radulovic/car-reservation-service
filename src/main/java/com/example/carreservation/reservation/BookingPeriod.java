package com.example.carreservation.reservation;

import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The half-open interval {@code [startTime, endTime)} a car is reserved for.
 *
 * <p>Duration is measured as elapsed time on the timeline, so it is immune to time zones and DST:
 * 10:00 to 10:00 the next day in local time is 23 h across a spring-forward night (accepted) and
 * 25 h across a fall-back night (rejected). The database enforces the same bounds.
 *
 * <p>Both instants are truncated to microseconds, PostgreSQL's precision, so the validated value is
 * exactly the stored one.
 */
@Embeddable
public record BookingPeriod(Instant startTime, Instant endTime) {

    public static final Duration MIN_DURATION = Duration.ofHours(2);
    public static final Duration MAX_DURATION = Duration.ofHours(24);

    public BookingPeriod {
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
        startTime = startTime.truncatedTo(ChronoUnit.MICROS);
        endTime = endTime.truncatedTo(ChronoUnit.MICROS);

        Duration duration = Duration.between(startTime, endTime);
        if (duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(MAX_DURATION) > 0) {
            throw new InvalidBookingPeriodException(
                    "A reservation must last between 2 and 24 hours, but was " + duration);
        }
    }

    public Duration duration() {
        return Duration.between(startTime, endTime);
    }

    /** Throws unless the period starts strictly after {@code now}. */
    public void requireStartsAfter(Instant now) {
        if (!startTime.isAfter(now)) {
            throw new InvalidBookingPeriodException("A reservation must start in the future");
        }
    }
}
