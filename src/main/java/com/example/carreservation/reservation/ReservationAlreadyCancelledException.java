package com.example.carreservation.reservation;

import java.util.UUID;

public class ReservationAlreadyCancelledException extends RuntimeException {

    public ReservationAlreadyCancelledException(UUID reservationId) {
        super("Reservation " + reservationId + " is already cancelled");
    }
}
