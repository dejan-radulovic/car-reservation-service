package com.example.carreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.carreservation.IntegrationTest;
import com.example.carreservation.car.CarNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class ReservationServiceIT {

    private static final Instant START = Instant.parse("2030-01-15T08:00:00Z");
    private static final BookingPeriod PERIOD = new BookingPeriod(START, START.plus(Duration.ofHours(4)));

    @Autowired
    private ReservationService service;

    @Autowired
    private JdbcClient jdbc;

    private final UUID user = UUID.randomUUID();
    private UUID carId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation").update();
        carId = jdbc.sql("SELECT id FROM car ORDER BY license_plate LIMIT 1").query(UUID.class).single();
    }

    @Test
    void createsAConfirmedReservationWithATimeOrderedId() {
        Reservation created = service.create(user, carId, PERIOD);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getId().version()).isEqualTo(7);
        Reservation loaded = service.get(created.getId());
        assertThat(loaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(loaded.getCarId()).isEqualTo(carId);
        assertThat(loaded.getUserId()).isEqualTo(user);
        assertThat(loaded.getPeriod()).isEqualTo(PERIOD);
    }

    @Test
    void rejectsAnOverlappingReservationWithAConflict() {
        service.create(user, carId, PERIOD);
        var overlapping = new BookingPeriod(START.plus(Duration.ofHours(3)), START.plus(Duration.ofHours(6)));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), carId, overlapping))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void rejectsAnUnknownCar() {
        assertThatThrownBy(() -> service.create(user, UUID.randomUUID(), PERIOD))
                .isInstanceOf(CarNotFoundException.class);
    }

    @Test
    void rejectsAnUnknownReservation() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), user))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void cancellationFreesTheSlotImmediately() {
        Reservation first = service.create(user, carId, PERIOD);

        Reservation cancelled = service.cancel(first.getId(), user);
        Reservation rebooked = service.create(UUID.randomUUID(), carId, PERIOD);

        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(rebooked.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void persistsTheOwnerCheck() {
        Reservation reservation = service.create(user, carId, PERIOD);

        assertThatThrownBy(() -> service.cancel(reservation.getId(), UUID.randomUUID()))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThat(service.get(reservation.getId()).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }
}
