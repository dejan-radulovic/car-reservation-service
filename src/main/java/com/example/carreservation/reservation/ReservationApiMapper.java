package com.example.carreservation.reservation;

import com.example.carreservation.api.model.ReservationDto;
import com.example.carreservation.api.model.ReservationStatusDto;

/** Maps domain reservations to the generated API model; generated types never leave the web layer. */
final class ReservationApiMapper {

    private ReservationApiMapper() {
    }

    static ReservationDto toDto(Reservation reservation) {
        return new ReservationDto()
                .id(reservation.getId())
                .carId(reservation.getCarId())
                .userId(reservation.getUserId())
                .startTime(reservation.getPeriod().startTime())
                .endTime(reservation.getPeriod().endTime())
                .status(ReservationStatusDto.fromValue(reservation.getStatus().name()))
                .createdAt(reservation.getCreatedAt())
                .cancelledAt(reservation.getCancelledAt());
    }
}
