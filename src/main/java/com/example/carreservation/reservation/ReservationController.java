package com.example.carreservation.reservation;

import com.example.carreservation.api.ReservationsApi;
import com.example.carreservation.api.model.CancelReservationRequestDto;
import com.example.carreservation.api.model.CreateReservationRequestDto;
import com.example.carreservation.api.model.ReservationDto;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Implements the generated contract. Mappings and parameter constraints come from {@link ReservationsApi}
 * and must not be repeated here (Bean Validation forbids strengthening them in an implementation).
 */
@RestController
class ReservationController implements ReservationsApi {

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Override
    public ResponseEntity<ReservationDto> createReservation(CreateReservationRequestDto request) {
        var period = new BookingPeriod(request.getStartTime(), request.getEndTime());
        Reservation reservation = reservations.book(request.getCarId(), request.getUserId(), period);

        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .queryParam("userId", reservation.getUserId())
                .buildAndExpand(reservation.getId())
                .toUri();
        return ResponseEntity.created(location).body(ReservationApiMapper.toDto(reservation));
    }

    @Override
    public ResponseEntity<ReservationDto> getReservation(UUID reservationId, UUID userId) {
        return ResponseEntity.ok(ReservationApiMapper.toDto(reservations.get(reservationId, userId)));
    }

    @Override
    public ResponseEntity<ReservationDto> cancelReservation(UUID reservationId, CancelReservationRequestDto request) {
        return ResponseEntity.ok(ReservationApiMapper.toDto(reservations.cancel(reservationId, request.getUserId())));
    }
}
