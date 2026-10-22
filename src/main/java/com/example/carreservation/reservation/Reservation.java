package com.example.carreservation.reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A car reservation and its business rules.
 *
 * <p>Overlap between reservations is deliberately not checked here: it is enforced by the database
 * exclusion constraint, which is the only check that stays correct under concurrent requests.
 */
public class Reservation {

    /** Cancellation is allowed only while the start is at least this far in the future. */
    public static final Duration CANCELLATION_CUTOFF = Duration.ofMinutes(30);

    private UUID id;
    private UUID carId;
    private UUID userId;
    private BookingPeriod period;
    private ReservationStatus status;
    private Instant createdAt;
    private Instant cancelledAt;

    private Reservation(UUID carId, UUID userId, BookingPeriod period, Instant createdAt) {
        this.carId = Objects.requireNonNull(carId, "carId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.period = Objects.requireNonNull(period, "period must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = ReservationStatus.CONFIRMED;
    }

    /** Creates a confirmed reservation; the period must start after {@code now}. */
    public static Reservation confirm(UUID carId, UUID userId, BookingPeriod period, Instant now) {
        Objects.requireNonNull(period, "period must not be null");
        period.requireStartsAfter(now);
        return new Reservation(carId, userId, period, now);
    }

    /**
     * Cancels this reservation on behalf of {@code actorId}.
     *
     * @throws ReservationNotFoundException if {@code actorId} is not the owner, so that other users
     *     cannot learn that the reservation exists
     * @throws ReservationAlreadyCancelledException if it is already cancelled
     * @throws CancellationWindowClosedException if the start is less than 30 minutes away
     */
    public void cancel(UUID actorId, Instant now) {
        if (!userId.equals(actorId)) {
            throw new ReservationNotFoundException(id);
        }
        if (status == ReservationStatus.CANCELLED) {
            throw new ReservationAlreadyCancelledException(id);
        }
        if (now.isAfter(period.start().minus(CANCELLATION_CUTOFF))) {
            throw new CancellationWindowClosedException(id);
        }
        status = ReservationStatus.CANCELLED;
        cancelledAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCarId() {
        return carId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BookingPeriod getPeriod() {
        return period;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
