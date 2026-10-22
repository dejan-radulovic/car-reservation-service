package com.example.carreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final Instant START = Instant.parse("2030-01-15T08:00:00Z");
    private static final BookingPeriod PERIOD = new BookingPeriod(START, START.plus(Duration.ofHours(4)));

    private final UUID carId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @Nested
    class Book {

        @Test
        void booksAConfirmedReservation() {
            Instant now = START.minus(Duration.ofDays(1));

            var reservation = Reservation.book(carId, owner, PERIOD, now);

            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reservation.getCarId()).isEqualTo(carId);
            assertThat(reservation.getUserId()).isEqualTo(owner);
            assertThat(reservation.getPeriod()).isEqualTo(PERIOD);
            assertThat(reservation.getCreatedAt()).isEqualTo(now);
            assertThat(reservation.getCancelledAt()).isNull();
        }

        @Test
        void rejectsAPeriodThatHasAlreadyStarted() {
            assertThatThrownBy(() -> Reservation.book(carId, owner, PERIOD, START))
                    .isInstanceOf(InvalidBookingPeriodException.class);
        }
    }

    @Nested
    class Cancel {

        private final Reservation reservation = Reservation.book(carId, owner, PERIOD, START.minus(Duration.ofDays(1)));

        @Test
        void cancelsWellBeforeTheStart() {
            Instant now = START.minus(Duration.ofHours(5));

            reservation.cancel(owner, now);

            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservation.getCancelledAt()).isEqualTo(now);
        }

        @Test
        void allowsCancellationExactlyThirtyMinutesBeforeTheStart() {
            reservation.cancel(owner, START.minus(Duration.ofMinutes(30)));

            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        }

        @Test
        void rejectsCancellationLessThanThirtyMinutesBeforeTheStart() {
            Instant now = START.minus(Duration.ofMinutes(30)).plusNanos(1_000);

            assertThatThrownBy(() -> reservation.cancel(owner, now))
                    .isInstanceOf(CancellationWindowClosedException.class);
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        void rejectsCancellationAfterTheStart() {
            assertThatThrownBy(() -> reservation.cancel(owner, START.plusSeconds(1)))
                    .isInstanceOf(CancellationWindowClosedException.class);
        }

        @Test
        void rejectsCancellingTwice() {
            reservation.cancel(owner, START.minus(Duration.ofHours(5)));

            assertThatThrownBy(() -> reservation.cancel(owner, START.minus(Duration.ofHours(4))))
                    .isInstanceOf(ReservationAlreadyCancelledException.class);
        }

        @Test
        void hidesTheReservationFromOtherUsers() {
            assertThatThrownBy(() -> reservation.cancel(UUID.randomUUID(), START.minus(Duration.ofHours(5))))
                    .isInstanceOf(ReservationNotFoundException.class);
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        void checksOwnershipBeforeRevealingTheState() {
            reservation.cancel(owner, START.minus(Duration.ofHours(5)));

            // a stranger must not learn that the reservation exists, even though it is already cancelled
            assertThatThrownBy(() -> reservation.cancel(UUID.randomUUID(), START.minus(Duration.ofHours(4))))
                    .isInstanceOf(ReservationNotFoundException.class);
        }
    }
}
