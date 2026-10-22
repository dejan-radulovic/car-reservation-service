package com.example.carreservation.reservation;

import com.example.carreservation.car.CarLookup;
import com.example.carreservation.common.persistence.PostgresErrors;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation use cases. Identity is always passed in, never read from a request, so the service
 * does not change if authentication is added later.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final CarLookup cars;
    private final Clock clock;

    ReservationService(ReservationRepository reservations, CarLookup cars, Clock clock) {
        this.reservations = reservations;
        this.cars = cars;
        this.clock = clock;
    }

    /**
     * Creates a confirmed reservation.
     *
     * <p>Double booking is prevented by the database exclusion constraint, not by a prior check: of
     * any number of concurrent requests for overlapping slots, PostgreSQL lets exactly one commit
     * and fails the others with {@code 23P01}, at the default READ COMMITTED isolation level.
     *
     * @throws ReservationConflictException if the slot overlaps a confirmed reservation of the car
     */
    @Transactional
    public Reservation create(UUID userId, UUID carId, BookingPeriod period) {
        cars.requireBookable(carId);
        Reservation reservation = Reservation.confirm(carId, userId, period, clock.instant());
        try {
            // flush now, so that a constraint violation surfaces here and not at commit
            return reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            if (PostgresErrors.isExclusionViolation(e)) {
                throw new ReservationConflictException(carId, period, e);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Reservation get(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    /**
     * Cancels a reservation on behalf of {@code userId}; the slot becomes available immediately.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException if it was modified concurrently
     */
    @Transactional
    public Reservation cancel(UUID reservationId, UUID userId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        reservation.cancel(userId, clock.instant());
        // flush now, so that an optimistic locking failure surfaces here and not at commit
        return reservations.saveAndFlush(reservation);
    }
}
