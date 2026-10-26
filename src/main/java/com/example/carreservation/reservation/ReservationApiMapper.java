package com.example.carreservation.reservation;

import com.example.carreservation.api.model.ReservationDto;
import com.example.carreservation.api.model.ReservationStatusDto;

/** Maps domain reservations to the generated API model; generated types never leave the web layer. */
final class ReservationApiMapper {

    private ReservationApiMapper() {
    }

    static ReservationDto toDto(Reservation reservation) {
        // required fields go through the constructor, so a new required field in the contract breaks the build
        return new ReservationDto(
                reservation.getId(),
                reservation.getCarId(),
                reservation.getUserId(),
                reservation.getPeriod().startTime(),
                reservation.getPeriod().endTime(),
                toDto(reservation.getStatus()),
                reservation.getCreatedAt())
                .cancelledAt(reservation.getCancelledAt());
    }

    static ReservationStatusDto toDto(ReservationStatus status) {
        return switch (status) {
            case CONFIRMED -> ReservationStatusDto.CONFIRMED;
            case CANCELLED -> ReservationStatusDto.CANCELLED;
        };
    }
}
