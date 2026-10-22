package com.example.carreservation.reservation;

import java.util.UUID;

/** The car already has a confirmed reservation overlapping the requested period. */
public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(UUID carId, BookingPeriod period, Throwable cause) {
        super("Car " + carId + " is already reserved for a time slot overlapping "
                + period.start() + " - " + period.end(), cause);
    }
}
