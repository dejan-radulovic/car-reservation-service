package com.example.carreservation.car;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only entry point the reservation module uses to reach cars.
 *
 * <p>{@link #requireBookable} locks the car row for the rest of the caller's transaction, which
 * serializes bookings of the same car (bookings of different cars are unaffected). The exclusion
 * constraint alone already guarantees that no two confirmed reservations overlap, but concurrent
 * conflicting inserts into an exclusion constraint wait for each other and are resolved by
 * PostgreSQL's deadlock detector: every loser fails with {@code 40P01} after {@code deadlock_timeout}
 * (1 s) instead of a clean {@code 23P01}. With the row lock, losers wait briefly, see the winner's
 * committed reservation and fail fast with a conflict.
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
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireBookable(UUID carId) {
        if (cars.lockById(carId).isEmpty()) {
            throw new CarNotFoundException(carId);
        }
    }
}
