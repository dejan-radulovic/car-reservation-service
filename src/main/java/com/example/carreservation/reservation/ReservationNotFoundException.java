package com.example.carreservation.reservation;

import java.util.UUID;

/** The reservation does not exist, or is not visible to the requesting user. */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID reservationId) {
        super("No reservation with id " + reservationId);
    }
}
