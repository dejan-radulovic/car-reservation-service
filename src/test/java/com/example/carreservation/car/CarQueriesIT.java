package com.example.carreservation.car;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.carreservation.IntegrationTest;
import com.example.carreservation.reservation.BookingPeriod;
import com.example.carreservation.reservation.ReservationService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Listing and availability against the seeded 10,000-car fleet. */
@IntegrationTest
class CarQueriesIT {

    private static final Instant T10 = Instant.parse("2030-01-15T10:00:00Z");

    @Autowired
    private CarQueries cars;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcClient jdbc;

    private List<Car> firstCars;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation").update();
        firstCars = cars.list(0, 5).cars();
    }

    @Test
    void listsTheFleetInLicensePlateOrderOnePageAtATime() {
        CarSlice first = cars.list(0, 50);
        CarSlice second = cars.list(1, 50);

        assertThat(first.cars()).hasSize(50).extracting(Car::getLicensePlate).isSorted();
        assertThat(first.cars().getFirst().getLicensePlate()).isEqualTo("BG-00001");
        assertThat(second.cars().getFirst().getLicensePlate()).isEqualTo("BG-00051");
        assertThat(first.hasNext()).isTrue();
    }

    @Test
    void theLastPageHasNoNext() {
        CarSlice last = cars.list(49, 200);
        CarSlice beyond = cars.list(50, 200);

        assertThat(last.cars()).hasSize(200);
        assertThat(last.hasNext()).isFalse();
        assertThat(beyond.cars()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void excludesCarsWithAnOverlappingConfirmedReservation() {
        book(firstCars.get(0), T10, T10.plus(Duration.ofHours(4)));

        List<Car> available = availableAmongFirstFive(T10.plus(Duration.ofHours(1)), T10.plus(Duration.ofHours(2)));

        // the booked car is skipped and the next one in license plate order takes its place
        assertThat(available).extracting(Car::getLicensePlate)
                .containsExactly("BG-00002", "BG-00003", "BG-00004", "BG-00005", "BG-00006");
    }

    @Test
    void treatsIntervalsAsHalfOpenLikeTheBookingConstraint() {
        // booked [10:00, 14:00): free in [08:00, 10:00) and [14:00, 16:00), busy as soon as one instant overlaps
        book(firstCars.get(0), T10, T10.plus(Duration.ofHours(4)));
        UUID booked = firstCars.get(0).getId();

        assertThat(availableAmongFirstFive(T10.minus(Duration.ofHours(2)), T10)).extracting(Car::getId).contains(booked);
        assertThat(availableAmongFirstFive(T10.plus(Duration.ofHours(4)), T10.plus(Duration.ofHours(6)))).extracting(Car::getId).contains(booked);
        assertThat(availableAmongFirstFive(T10.minus(Duration.ofHours(2)), T10.plusNanos(1_000))).extracting(Car::getId).doesNotContain(booked);
    }

    @Test
    void ignoresCancelledReservations() {
        UUID owner = UUID.randomUUID();
        var reservation = reservations.book(firstCars.get(0).getId(), owner, new BookingPeriod(T10, T10.plus(Duration.ofHours(4))));
        reservations.cancel(reservation.getId(), owner);

        assertThat(availableAmongFirstFive(T10, T10.plus(Duration.ofHours(1))))
                .extracting(Car::getId).contains(firstCars.get(0).getId());
    }

    @Test
    void pagesThroughAvailableCarsWithoutGapsOrDuplicates() {
        book(firstCars.get(1), T10, T10.plus(Duration.ofHours(4)));
        var window = new SearchWindow(T10, T10.plus(Duration.ofHours(1)));

        CarSlice first = cars.available(window, 0, 2);
        CarSlice second = cars.available(window, 1, 2);

        // BG-00002 is booked, so the available cars are BG-00001, BG-00003, BG-00004, BG-00005, ...
        assertThat(first.cars()).extracting(Car::getLicensePlate).containsExactly("BG-00001", "BG-00003");
        assertThat(second.cars()).extracting(Car::getLicensePlate).containsExactly("BG-00004", "BG-00005");
        assertThat(first.hasNext()).isTrue();
    }

    @Test
    void servesAvailabilityOverHttp() {
        book(firstCars.get(0), T10, T10.plus(Duration.ofHours(4)));

        ResponseEntity<String> response = http.getForEntity(
                "/api/v1/cars/availability?from={from}&to={to}&size=3", String.class, T10, T10.plus(Duration.ofHours(2)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> plates = JsonPath.read(response.getBody(), "$.items[*].licensePlate");
        assertThat(plates).containsExactly("BG-00002", "BG-00003", "BG-00004");
    }

    private List<Car> availableAmongFirstFive(Instant from, Instant to) {
        return cars.available(new SearchWindow(from, to), 0, 5).cars();
    }

    private void book(Car car, Instant start, Instant end) {
        reservations.book(car.getId(), UUID.randomUUID(), new BookingPeriod(start, end));
    }
}
