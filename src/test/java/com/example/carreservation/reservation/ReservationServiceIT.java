package com.example.carreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.carreservation.IntegrationTest;
import com.example.carreservation.MutableClock;
import com.example.carreservation.car.CarLookup;
import com.example.carreservation.car.CarNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;

@IntegrationTest
class ReservationServiceIT {

    private static final Instant START = Instant.parse("2030-01-15T08:00:00Z");
    private static final BookingPeriod PERIOD = new BookingPeriod(START, START.plus(Duration.ofHours(4)));

    @Autowired
    private ReservationService service;

    @Autowired
    private CarLookup carLookup;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcClient jdbc;

    private final UUID user = UUID.randomUUID();
    private UUID carId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation").update();
        carId = jdbc.sql("SELECT id FROM car ORDER BY license_plate LIMIT 1").query(UUID.class).single();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void booksAConfirmedReservationWithATimeOrderedId() {
        Reservation booked = service.book(carId, user, PERIOD);

        assertThat(booked.getId()).isNotNull();
        assertThat(booked.getId().version()).isEqualTo(7);
        Reservation loaded = service.get(booked.getId(), user);
        assertThat(loaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(loaded.getCarId()).isEqualTo(carId);
        assertThat(loaded.getUserId()).isEqualTo(user);
        assertThat(loaded.getPeriod()).isEqualTo(PERIOD);
        // the clock ticks in microseconds, so what the API returns on write is what it returns on read
        assertThat(loaded.getCreatedAt()).isEqualTo(booked.getCreatedAt());
    }

    @Test
    void rejectsAnOverlappingReservationWithAConflict() {
        service.book(carId, user, PERIOD);
        var overlapping = new BookingPeriod(START.plus(Duration.ofHours(3)), START.plus(Duration.ofHours(6)));

        assertThatThrownBy(() -> service.book(carId, UUID.randomUUID(), overlapping))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void rejectsAPeriodThatHasAlreadyStarted() {
        clock.setInstant(START.plusSeconds(1));

        assertThatThrownBy(() -> service.book(carId, user, PERIOD))
                .isInstanceOf(InvalidBookingPeriodException.class);
    }

    @Test
    void rejectsAnUnknownCar() {
        assertThatThrownBy(() -> service.book(UUID.randomUUID(), user, PERIOD))
                .isInstanceOf(CarNotFoundException.class);
    }

    @Test
    void rejectsAnUnknownReservation() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), user))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), user))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void hidesReservationsFromOtherUsers() {
        Reservation reservation = service.book(carId, user, PERIOD);

        assertThatThrownBy(() -> service.get(reservation.getId(), UUID.randomUUID()))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void nonOwnerCannotCancel() {
        Reservation reservation = service.book(carId, user, PERIOD);

        assertThatThrownBy(() -> service.cancel(reservation.getId(), UUID.randomUUID()))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThat(service.get(reservation.getId(), user).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void cancellationFreesTheSlotImmediately() {
        Reservation first = service.book(carId, user, PERIOD);

        Reservation cancelled = service.cancel(first.getId(), user);
        Reservation rebooked = service.book(carId, UUID.randomUUID(), PERIOD);

        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(service.get(first.getId(), user).getCancelledAt()).isEqualTo(cancelled.getCancelledAt());
        assertThat(rebooked.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void cancellationClosesThirtyMinutesBeforeTheStart() {
        Reservation reservation = service.book(carId, user, PERIOD);
        clock.setInstant(START.minus(Duration.ofMinutes(29)));

        assertThatThrownBy(() -> service.cancel(reservation.getId(), user))
                .isInstanceOf(CancellationWindowClosedException.class);
        assertThat(service.get(reservation.getId(), user).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void theCarLockCannotBeTakenOutsideABookingTransaction() {
        // a lock released at the end of its own transaction would protect nothing
        assertThatThrownBy(() -> carLookup.lockForBooking(carId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
