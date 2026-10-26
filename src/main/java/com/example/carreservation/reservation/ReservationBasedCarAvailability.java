package com.example.carreservation.reservation;

import com.example.carreservation.car.Car;
import com.example.carreservation.car.CarAvailability;
import com.example.carreservation.car.SearchWindow;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Availability is the absence of an overlapping confirmed reservation.
 *
 * <p>The anti-join probes the GiST index behind the exclusion constraint, which covers exactly
 * {@code (car_id, period) WHERE status = 'CONFIRMED'}, once per car, while the cars are read in
 * license plate order from the unique index on {@code license_plate}; with {@code LIMIT} the scan
 * stops after one page. It uses the same half-open {@code tstzrange} as the constraint, so
 * availability and booking can never disagree about what "overlapping" means.
 *
 * <p>{@code @Repository}, not {@code @Component}: it uses the {@code EntityManager} directly, so only
 * the repository proxy translates its JPA exceptions (e.g. a statement timeout) into Spring's
 * {@code DataAccessException}s, which the error handler maps to proper statuses.
 */
@Repository
class ReservationBasedCarAvailability implements CarAvailability {

    private static final String AVAILABLE_CARS = """
            SELECT c.*
            FROM car c
            WHERE NOT EXISTS (
                SELECT 1
                FROM reservation r
                WHERE r.car_id = c.id
                  AND r.status = 'CONFIRMED'
                  AND r.period && tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)')
            )
            ORDER BY c.license_plate
            OFFSET :offset
            LIMIT :limit
            """;

    private final EntityManager entityManager;

    ReservationBasedCarAvailability(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Car> findAvailable(SearchWindow window, int offset, int limit) {
        return entityManager.createNativeQuery(AVAILABLE_CARS, Car.class)
                .setParameter("from", window.from())
                .setParameter("to", window.to())
                .setParameter("offset", offset)
                .setParameter("limit", limit)
                .getResultList();
    }
}
