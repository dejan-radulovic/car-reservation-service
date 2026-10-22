package com.example.carreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BookingPeriodTest {

    private static final Instant START = Instant.parse("2030-01-15T08:00:00Z");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PT2H", "PT2H0.000001S", "PT12H", "PT23H59M59.999999S", "PT24H"})
    void acceptsDurationsWithinTwoToTwentyFourHoursInclusive(Duration duration) {
        var period = new BookingPeriod(START, START.plus(duration));

        assertThat(period.duration()).isEqualTo(duration);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PT1H59M59.999999S", "PT24H0.000001S", "PT0S", "-PT2H"})
    void rejectsDurationsOutsideTheBounds(Duration duration) {
        assertThatThrownBy(() -> new BookingPeriod(START, START.plus(duration)))
                .isInstanceOf(InvalidBookingPeriodException.class)
                .hasMessageContaining("between 2 and 24 hours");
    }

    @Test
    void rejectsMissingInstants() {
        assertThatNullPointerException().isThrownBy(() -> new BookingPeriod(null, START));
        assertThatNullPointerException().isThrownBy(() -> new BookingPeriod(START, null));
    }

    @Test
    void validatesTheMicrosecondValuePostgresWillStoreNotTheRawInput() {
        // raw duration is 24 h + 999 ns (too long), but PostgreSQL stores microseconds: exactly 24 h
        var period = new BookingPeriod(START, START.plus(Duration.ofHours(24)).plusNanos(999));

        assertThat(period.end()).isEqualTo(START.plus(Duration.ofHours(24)));
        assertThat(period.duration()).isEqualTo(BookingPeriod.MAX_DURATION);
    }

    @Test
    void truncatesBothInstantsToMicroseconds() {
        var period = new BookingPeriod(START.plusNanos(1_999), START.plus(Duration.ofHours(3)).plusNanos(1));

        assertThat(period.start()).isEqualTo(START.plusNanos(1_000));
        assertThat(period.end()).isEqualTo(START.plus(Duration.ofHours(3)));
    }

    @Nested
    class StartInTheFuture {

        private final BookingPeriod period = new BookingPeriod(START, START.plus(Duration.ofHours(2)));

        @Test
        void acceptsAStartAfterNow() {
            assertThatCode(() -> period.requireStartsAfter(START.minusNanos(1))).doesNotThrowAnyException();
        }

        @Test
        void rejectsAStartAtNow() {
            assertThatThrownBy(() -> period.requireStartsAfter(START))
                    .isInstanceOf(InvalidBookingPeriodException.class)
                    .hasMessageContaining("future");
        }

        @Test
        void rejectsAStartInThePast() {
            assertThatThrownBy(() -> period.requireStartsAfter(START.plusSeconds(1)))
                    .isInstanceOf(InvalidBookingPeriodException.class);
        }
    }

    /** Duration is elapsed time, so "10:00 to 10:00 next day" is not always 24 h across DST changes. */
    @Nested
    class DaylightSavingTime {

        private final ZoneId belgrade = ZoneId.of("Europe/Belgrade");

        @Test
        void acceptsTwentyThreeElapsedHoursAcrossSpringForward() {
            // EU clocks jump from 02:00 to 03:00 on 2030-03-31
            Instant start = LocalDateTime.parse("2030-03-30T10:00").atZone(belgrade).toInstant();
            Instant end = LocalDateTime.parse("2030-03-31T10:00").atZone(belgrade).toInstant();

            var period = new BookingPeriod(start, end);

            assertThat(period.duration()).isEqualTo(Duration.ofHours(23));
        }

        @Test
        void rejectsTwentyFiveElapsedHoursAcrossFallBack() {
            // EU clocks go back from 03:00 to 02:00 on 2030-10-27
            Instant start = LocalDateTime.parse("2030-10-26T10:00").atZone(belgrade).toInstant();
            Instant end = LocalDateTime.parse("2030-10-27T10:00").atZone(belgrade).toInstant();

            assertThat(Duration.between(start, end)).isEqualTo(Duration.ofHours(25));
            assertThatThrownBy(() -> new BookingPeriod(start, end))
                    .isInstanceOf(InvalidBookingPeriodException.class);
        }
    }
}
