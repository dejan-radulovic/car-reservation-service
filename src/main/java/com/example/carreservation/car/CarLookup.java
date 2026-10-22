package com.example.carreservation.car;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only entry point the reservation module uses to reach cars.
 *
 * <p>{@link #lockForBooking} serializes bookings of the same car; bookings of different cars are
 * unaffected. The exclusion constraint alone already guarantees that no two confirmed reservations
 * overlap, but it inserts the index entry before checking for conflicts: two in-flight conflicting
 * inserts then wait for each other, and PostgreSQL's deadlock detector aborts one of them with
 * {@code 40P01} after {@code deadlock_timeout} (1 s) instead of a clean {@code 23P01}. Under a burst
 * of identical requests that hits most losers. With the row lock, losers wait briefly, see the
 * winner's committed reservation and fail fast with a conflict.
 *
 * <p>The wait is bounded by the connection's {@code lock_timeout} (see {@code application.yml}), so a
 * stalled booking cannot pile up waiters until the connection pool is exhausted.
 */
@Service
public class CarLookup {

    private final CarRepository cars;

    CarLookup(CarRepository cars) {
        this.cars = cars;
    }

    /**
     * Ensures the car exists and locks it until the caller's transaction ends.
     *
     * @throws CarNotFoundException if there is no such car
     * @throws org.springframework.dao.PessimisticLockingFailureException if the lock is not granted
     *     within {@code lock_timeout}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForBooking(UUID carId) {
        if (cars.lockById(carId).isEmpty()) {
            throw new CarNotFoundException(carId);
        }
    }
}
