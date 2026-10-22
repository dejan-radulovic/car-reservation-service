package com.example.carreservation.car;

import java.util.List;

/**
 * Finds cars that are free in a window. Implemented by the reservation module, which owns
 * reservation data; defining the port here keeps the module dependency one-way (reservation → car).
 */
public interface CarAvailability {

    /** Cars without a confirmed reservation overlapping {@code window}, ordered by license plate. */
    List<Car> findAvailable(SearchWindow window, int offset, int limit);
}
