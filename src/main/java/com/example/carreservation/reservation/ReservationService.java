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
     * Books a car for a period.
     *
     * <p>Double booking is prevented by the database exclusion constraint, not by a prior check: of
     * any number of concurrent requests for overlapping slots, exactly one commits and the others
     * fail with {@code 23P01}, at the default READ COMMITTED isolation level. The car row lock only
     * makes losers fail fast and cleanly (see {@link CarLookup}).
     *
     * @throws com.example.carreservation.car.CarNotFoundException if there is no such car
     * @throws InvalidBookingPeriodException if the period does not start in the future
     * @throws ReservationConflictException if the slot overlaps a confirmed reservation of the car
     */
    @Transactional
    public Reservation book(UUID carId, UUID userId, BookingPeriod period) {
        cars.lockForBooking(carId);
        Reservation reservation = Reservation.book(carId, userId, period, clock.instant());
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

    /**
     * Returns a reservation of {@code userId}.
     *
     * @throws ReservationNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional(readOnly = true)
    public Reservation get(UUID reservationId, UUID userId) {
        Reservation reservation = find(reservationId);
        reservation.requireOwnedBy(userId);
        return reservation;
    }

    /**
     * Cancels a reservation of {@code userId}; the slot becomes available immediately.
     *
     * @throws ReservationNotFoundException if it does not exist or belongs to someone else
     * @throws ReservationAlreadyCancelledException if it is already cancelled
     * @throws CancellationWindowClosedException if it starts in less than 30 minutes
     * @throws org.springframework.dao.OptimisticLockingFailureException if it was modified concurrently
     */
    @Transactional
    public Reservation cancel(UUID reservationId, UUID userId) {
        Reservation reservation = find(reservationId);
        reservation.cancel(userId, clock.instant());
        // flush now, so that an optimistic locking failure surfaces here and not at commit
        reservations.flush();
        return reservation;
    }

    private Reservation find(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }
}
