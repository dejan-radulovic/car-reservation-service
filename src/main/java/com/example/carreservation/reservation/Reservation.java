package com.example.carreservation.reservation;

import com.example.carreservation.common.persistence.UuidV7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A car reservation and its business rules.
 *
 * <p>Overlap between reservations is deliberately not checked here: it is enforced by the database
 * exclusion constraint, which is the only check that stays correct under concurrent requests.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    /** Cancellation is allowed only while the start is at least this far in the future. */
    public static final Duration CANCELLATION_CUTOFF = Duration.ofMinutes(30);

    @Id
    @UuidGenerator(algorithm = UuidV7Generator.class)
    private UUID id;

    // referenced by id: the car belongs to another module, and the FK lives in the database
    @Column(name = "car_id", nullable = false, updatable = false)
    private UUID carId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // mapped to start_time / end_time by Spring Boot's snake_case naming strategy
    @Embedded
    private BookingPeriod period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    // optimistic locking: two concurrent cancellations cannot both succeed
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Reservation() {
        // for JPA
    }

    private Reservation(UUID carId, UUID userId, BookingPeriod period, Instant createdAt) {
        this.carId = Objects.requireNonNull(carId, "carId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.period = Objects.requireNonNull(period, "period must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = ReservationStatus.CONFIRMED;
    }

    /** Books the car for the period; the period must start after {@code now}. */
    public static Reservation book(UUID carId, UUID userId, BookingPeriod period, Instant now) {
        Objects.requireNonNull(period, "period must not be null");
        period.requireStartsAfter(now);
        return new Reservation(carId, userId, period, now);
    }

    /**
     * Reservations are visible only to their owner; to anyone else they do not exist.
     *
     * @throws ReservationNotFoundException if {@code actorId} is not the owner
     */
    public void requireOwnedBy(UUID actorId) {
        if (!userId.equals(actorId)) {
            throw new ReservationNotFoundException(id);
        }
    }

    /**
     * Cancels this reservation on behalf of {@code actorId}.
     *
     * @throws ReservationNotFoundException if {@code actorId} is not the owner
     * @throws ReservationAlreadyCancelledException if it is already cancelled
     * @throws CancellationWindowClosedException if the start is less than 30 minutes away
     */
    public void cancel(UUID actorId, Instant now) {
        requireOwnedBy(actorId);
        if (status == ReservationStatus.CANCELLED) {
            throw new ReservationAlreadyCancelledException(id);
        }
        if (now.isAfter(period.startTime().minus(CANCELLATION_CUTOFF))) {
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
