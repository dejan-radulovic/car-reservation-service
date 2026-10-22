package com.example.carreservation.reservation;

/** The requested period violates the booking rules (duration or start time). */
public class InvalidBookingPeriodException extends RuntimeException {

    public InvalidBookingPeriodException(String message) {
        super(message);
    }
}
