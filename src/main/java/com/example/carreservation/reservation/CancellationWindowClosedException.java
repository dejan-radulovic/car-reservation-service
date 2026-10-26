package com.example.carreservation.reservation;

import java.util.UUID;

/** Cancellation was requested less than {@link Reservation#CANCELLATION_CUTOFF} before the reservation starts. */
public class CancellationWindowClosedException extends RuntimeException {

    public CancellationWindowClosedException(UUID reservationId) {
        super("Reservation " + reservationId
                + " can no longer be cancelled: cancellation closes "
                + Reservation.CANCELLATION_CUTOFF.toMinutes() + " minutes before the start");
    }
}
