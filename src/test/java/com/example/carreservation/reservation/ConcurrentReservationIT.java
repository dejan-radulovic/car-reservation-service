package com.example.carreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.carreservation.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fires many requests at the same moment, each in its own transaction, and checks the outcome both
 * from the callers' perspective and in the database.
 *
 * <p>Calls go to the service bean directly: the guarantee comes from the database, so testing at
 * this level isolates exactly what is being proven (and stays independent of HTTP and auth).
 */
@IntegrationTest
class ConcurrentReservationIT {

    private static final int THREADS = 32;
    private static final Instant START = Instant.parse("2030-01-15T08:00:00Z");

    @Autowired
    private ReservationService service;

    @Autowired
    private JdbcClient jdbc;

    private List<UUID> cars;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation").update();
        cars = jdbc.sql("SELECT id FROM car ORDER BY license_plate LIMIT :n").param("n", THREADS)
                .query(UUID.class).list();
    }

    @RepeatedTest(5)
    void exactlyOneOfManyIdenticalRequestsWins() throws Exception {
        UUID car = cars.getFirst();
        var period = new BookingPeriod(START, START.plus(Duration.ofHours(4)));

        List<Outcome> outcomes = runConcurrently(i -> () -> service.create(UUID.randomUUID(), car, period));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.failedWith(ReservationConflictException.class)).hasSize(THREADS - 1);
        assertThat(confirmedReservations(car)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void exactlyOneOfManyPartiallyOverlappingRequestsWins() throws Exception {
        UUID car = cars.getFirst();
        // shifted by i minutes, every interval still contains [START + 31 min, START + 3 h): pairwise overlapping
        IntFunction<BookingPeriod> period = i -> new BookingPeriod(
                START.plus(Duration.ofMinutes(i)), START.plus(Duration.ofMinutes(i)).plus(Duration.ofHours(3)));

        List<Outcome> outcomes = runConcurrently(i -> () -> service.create(UUID.randomUUID(), car, period.apply(i)));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.failedWith(ReservationConflictException.class)).hasSize(THREADS - 1);
        assertThat(confirmedReservations(car)).isEqualTo(1);
    }

    @Test
    void requestsForDifferentCarsDoNotBlockEachOther() throws Exception {
        var period = new BookingPeriod(START, START.plus(Duration.ofHours(4)));

        List<Outcome> outcomes = runConcurrently(i -> () -> service.create(UUID.randomUUID(), cars.get(i), period));

        assertThat(outcomes).allMatch(Outcome::succeeded);
    }

    @Test
    void requestsForTheSameCarInDisjointSlotsAllSucceed() throws Exception {
        UUID car = cars.getFirst();
        // back-to-back 2 h slots: half-open intervals touch but do not overlap
        IntFunction<BookingPeriod> period = i -> new BookingPeriod(
                START.plus(Duration.ofHours(2L * i)), START.plus(Duration.ofHours(2L * i + 2)));

        List<Outcome> outcomes = runConcurrently(i -> () -> service.create(UUID.randomUUID(), car, period.apply(i)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(confirmedReservations(car)).isEqualTo(THREADS);
    }

    @RepeatedTest(5)
    void exactlyOneOfManyConcurrentCancellationsWins() throws Exception {
        UUID owner = UUID.randomUUID();
        Reservation reservation = service.create(owner, cars.getFirst(), new BookingPeriod(START, START.plus(Duration.ofHours(4))));

        List<Outcome> outcomes = runConcurrently(i -> () -> service.cancel(reservation.getId(), owner));

        // losers either saw the committed cancellation or lost the optimistic-locking race
        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).allMatch(o ->
                o.failedWith(ReservationAlreadyCancelledException.class) || o.failedWith(OptimisticLockingFailureException.class));
        assertThat(service.get(reservation.getId()).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    private List<Outcome> runConcurrently(IntFunction<Callable<?>> task) throws InterruptedException {
        var ready = new CountDownLatch(THREADS);
        var go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREADS; i++) {
                Callable<?> call = task.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown(); // release all threads at once
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get();
                outcomes.add(new Outcome(null));
            } catch (ExecutionException e) {
                outcomes.add(new Outcome(e.getCause()));
            }
        }
        return outcomes;
    }

    private long confirmedReservations(UUID car) {
        return jdbc.sql("SELECT count(*) FROM reservation WHERE car_id = :car AND status = 'CONFIRMED'")
                .param("car", car).query(Long.class).single();
    }

    private record Outcome(Throwable failure) {

        boolean succeeded() {
            return failure == null;
        }

        boolean failedWith(Class<? extends Throwable> type) {
            return type.isInstance(failure);
        }
    }
}
