package com.example.carreservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the database-level invariants created by the Liquibase changelog, independently of
 * any application code: these constraints are the last line of defence for data integrity.
 */
@IntegrationTest
class ReservationSchemaIT {

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String CHECK_VIOLATION = "23514";
    private static final Instant START = Instant.parse("2030-01-15T10:00:00Z");

    @Autowired
    private JdbcClient jdbc;

    private UUID carId;
    private UUID otherCarId;

    @BeforeEach
    void setUp() {
        // the seeded cars are kept; Liquibase would not re-run the seed changeset
        jdbc.sql("TRUNCATE reservation").update();
        var cars = jdbc.sql("SELECT id FROM car ORDER BY license_plate LIMIT 2").query(UUID.class).list();
        carId = cars.get(0);
        otherCarId = cars.get(1);
    }

    @Test
    void seedsTheTenThousandCarFleet() {
        assertThat(jdbc.sql("SELECT count(*) FROM car").query(Long.class).single()).isEqualTo(10_000L);
    }

    @Test
    void rejectsOverlappingConfirmedReservationsForTheSameCar() {
        insert(carId, START, START.plus(Duration.ofHours(4)), "CONFIRMED");

        assertThatThrownBy(() -> insert(carId, START.plus(Duration.ofHours(2)), START.plus(Duration.ofHours(6)), "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(EXCLUSION_VIOLATION));
    }

    @Test
    void allowsBackToBackReservationsBecauseIntervalsAreHalfOpen() {
        insert(carId, START, START.plus(Duration.ofHours(2)), "CONFIRMED");

        assertThatCode(() -> insert(carId, START.plus(Duration.ofHours(2)), START.plus(Duration.ofHours(4)), "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsOverlapWithCancelledReservations() {
        insert(carId, START, START.plus(Duration.ofHours(4)), "CANCELLED");

        assertThatCode(() -> insert(carId, START, START.plus(Duration.ofHours(4)), "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsTheSameIntervalForDifferentCars() {
        insert(carId, START, START.plus(Duration.ofHours(4)), "CONFIRMED");

        assertThatCode(() -> insert(otherCarId, START, START.plus(Duration.ofHours(4)), "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsDurationsAtBothInclusiveBounds() {
        assertThatCode(() -> {
            insert(carId, START, START.plus(Duration.ofHours(2)), "CONFIRMED");
            insert(otherCarId, START, START.plus(Duration.ofHours(24)), "CONFIRMED");
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsDurationsOutsideTwoToTwentyFourHours() {
        assertThatThrownBy(() -> insert(carId, START, START.plus(Duration.ofHours(2).minusSeconds(1)), "CONFIRMED"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
        assertThatThrownBy(() -> insert(carId, START, START.plus(Duration.ofHours(24).plusSeconds(1)), "CONFIRMED"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> insert(carId, START, START.plus(Duration.ofHours(2)), "PENDING"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
    }

    private void insert(UUID car, Instant start, Instant end, String status) {
        jdbc.sql("""
                        INSERT INTO reservation (id, car_id, user_id, start_time, end_time, status, version, created_at)
                        VALUES (:id, :carId, :userId, :start, :end, :status, 0, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("carId", car)
                .param("userId", UUID.randomUUID())
                .param("start", OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(end, ZoneOffset.UTC))
                .param("status", status)
                .update();
    }

    private static String sqlState(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }
}
