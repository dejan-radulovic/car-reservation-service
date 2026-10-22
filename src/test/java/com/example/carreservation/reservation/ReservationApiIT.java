package com.example.carreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.carreservation.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The reservation API end to end: real HTTP, real JSON, real PostgreSQL. */
@IntegrationTest
class ReservationApiIT {

    private static final String START = "2030-01-15T08:00:00Z";
    private static final String END = "2030-01-15T12:00:00Z";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcClient jdbc;

    private final UUID user = UUID.randomUUID();
    private UUID carId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation").update();
        carId = jdbc.sql("SELECT id FROM car ORDER BY license_plate LIMIT 1").query(UUID.class).single();
    }

    @Test
    void reservationLifecycle() {
        ResponseEntity<String> created = book(user, START, END);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        URI location = created.getHeaders().getLocation();
        String id = JsonPath.read(created.getBody(), "$.id");

        ResponseEntity<String> fetched = http.getForEntity(location, String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());

        ResponseEntity<String> cancelled = http.postForEntity("/api/v1/reservations/{id}/cancel",
                Map.of("userId", user), String.class, id);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) JsonPath.read(cancelled.getBody(), "$.status")).isEqualTo("CANCELLED");
        assertThat(http.getForEntity(location, String.class).getBody()).isEqualTo(cancelled.getBody());

        // the cancelled slot is free again
        assertThat(book(UUID.randomUUID(), START, END).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anotherUserCannotSeeOrCancelTheReservation() {
        String id = JsonPath.read(book(user, START, END).getBody(), "$.id");
        UUID stranger = UUID.randomUUID();

        assertThat(http.getForEntity("/api/v1/reservations/{id}?userId={user}", String.class, id, stranger)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/api/v1/reservations/{id}/cancel", Map.of("userId", stranger), String.class, id)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentRequestsForTheSameSlotYieldOneCreatedAndOnlyConflicts() throws Exception {
        int requests = 16;
        var go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    go.await();
                    return book(UUID.randomUUID(), START, END);
                }));
            }
            go.countDown();
        }

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .hasSize(requests - 1)
                .allSatisfy(r -> {
                    assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
                    assertThat((String) JsonPath.read(r.getBody(), "$.code")).isEqualTo("RESERVATION_CONFLICT");
                });
    }

    @Test
    void anUnknownCarIsNotFound() {
        ResponseEntity<String> response = http.postForEntity("/api/v1/reservations", Map.of(
                "carId", UUID.randomUUID(), "userId", user, "startTime", START, "endTime", END), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo("CAR_NOT_FOUND");
    }

    private ResponseEntity<String> book(UUID userId, String startTime, String endTime) {
        return http.postForEntity("/api/v1/reservations",
                Map.of("carId", carId, "userId", userId, "startTime", startTime, "endTime", endTime), String.class);
    }
}
