package com.example.carreservation.reservation;

import java.util.UUID;

/** Cancellation was requested less than 30 minutes before the reservation starts. */
public class CancellationWindowClosedException extends RuntimeException {

    public CancellationWindowClosedException(UUID reservationId) {
        super("Reservation " + reservationId
                + " can no longer be cancelled: cancellation closes 30 minutes before the start");
    }
}
