package com.example.carreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.carreservation.car.CarNotFoundException;
import com.example.carreservation.common.json.JacksonConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The HTTP contract of the reservation endpoints: status codes, bodies and error model. */
@WebMvcTest(ReservationController.class)
@Import(JacksonConfig.class)
class ReservationControllerTest {

    private static final Instant START = Instant.parse("2030-01-15T08:00:00Z");
    private static final Instant END = START.plus(Duration.ofHours(4));
    private static final UUID CAR = UUID.fromString("0192f7a0-5f3c-7b2e-9c1d-3e4f5a6b7c8d");
    private static final UUID USER = UUID.fromString("0192f7a0-0000-7000-8000-000000000001");
    private static final UUID RESERVATION = UUID.fromString("0192f7a0-0000-7000-8000-0000000000aa");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ReservationService service;

    @Nested
    class Create {

        @Test
        void returns201WithLocationAndTheReservationInUtc() {
            given(service.book(eq(CAR), eq(USER), any())).willReturn(reservation(ReservationStatus.CONFIRMED));

            MvcTestResult result = post(body("2030-01-15T10:00:00+02:00", "2030-01-15T14:00:00+02:00"));

            assertThat(result).hasStatus(HttpStatus.CREATED)
                    .hasHeader("Location", "http://localhost/api/v1/reservations/" + RESERVATION + "?userId=" + USER);
            assertThat(result).bodyJson().extractingPath("$.startTime").isEqualTo("2030-01-15T08:00:00Z");
            assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
            assertThat(result).bodyJson().doesNotHavePath("$.cancelledAt");
            // the +02:00 input reached the service as the same instant
            verify(service).book(CAR, USER, new BookingPeriod(START, END));
        }

        @Test
        void rejectsAMissingFieldWithTheFieldName() {
            MvcTestResult result = post("""
                    {"carId": "%s", "userId": "%s", "endTime": "2030-01-15T12:00:00Z"}""".formatted(CAR, USER));

            assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("startTime");
            verifyNoInteractions(service);
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"2030-01-15T08:00:00\"", "1893484800", "\"1893484800\"", "\"not a time\""})
        void rejectsTimesWithoutAnOffsetAndEpochNumbersAsMalformed(String startTime) {
            MvcTestResult result = post("""
                    {"carId": "%s", "userId": "%s", "startTime": %s, "endTime": "2030-01-15T12:00:00Z"}"""
                    .formatted(CAR, USER, startTime));

            assertProblem(result, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
            assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("startTime", "ISO-8601");
            verifyNoInteractions(service);
        }

        @Test
        void rejectsMalformedJson() {
            assertProblem(post("{\"carId\": "), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }

        @Test
        void rejectsAMalformedUuid() {
            MvcTestResult result = post("""
                    {"carId": "not-a-uuid", "userId": "%s", "startTime": "2030-01-15T08:00:00Z", "endTime": "2030-01-15T12:00:00Z"}"""
                    .formatted(USER));

            assertProblem(result, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
            assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("carId");
        }

        @Test
        void rejectsADurationOutsideTwoToTwentyFourHoursAsUnprocessable() {
            MvcTestResult result = post(body("2030-01-15T08:00:00Z", "2030-01-15T09:00:00Z"));

            assertProblem(result, HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BOOKING_PERIOD");
            verifyNoInteractions(service);
        }

        @Test
        void rejectsAStartInThePastAsUnprocessable() {
            given(service.book(any(), any(), any())).willThrow(new InvalidBookingPeriodException("A reservation must start in the future"));

            assertProblem(post(body("2030-01-15T08:00:00Z", "2030-01-15T12:00:00Z")),
                    HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BOOKING_PERIOD");
        }

        @Test
        void reportsAnUnknownCarAsNotFound() {
            given(service.book(any(), any(), any())).willThrow(new CarNotFoundException(CAR));

            assertProblem(post(body("2030-01-15T08:00:00Z", "2030-01-15T12:00:00Z")), HttpStatus.NOT_FOUND, "CAR_NOT_FOUND");
        }

        @Test
        void reportsAnOverlapAsAConflict() {
            given(service.book(any(), any(), any()))
                    .willThrow(new ReservationConflictException(CAR, new BookingPeriod(START, END), null));

            MvcTestResult result = post(body("2030-01-15T08:00:00Z", "2030-01-15T12:00:00Z"));

            assertProblem(result, HttpStatus.CONFLICT, "RESERVATION_CONFLICT");
            assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("/problems/reservation-conflict");
            assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/v1/reservations");
            assertThat(result).bodyJson().doesNotHavePath("$.errors");
        }

        @Test
        void reportsALockTimeoutAsARetryableConflict() {
            given(service.book(any(), any(), any())).willThrow(new CannotAcquireLockException("lock timeout"));

            assertProblem(post(body("2030-01-15T08:00:00Z", "2030-01-15T12:00:00Z")),
                    HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        }

        @Test
        void reportsAnUnreachableDatabaseAsServiceUnavailable() {
            given(service.book(any(), any(), any())).willThrow(new DataAccessResourceFailureException("connection refused"));

            assertProblem(post(body("2030-01-15T08:00:00Z", "2030-01-15T12:00:00Z")),
                    HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
        }

        @Test
        void hidesInternalsOfUnexpectedErrors() {
            given(service.book(any(), any(), any())).willThrow(new IllegalStateException("secret SQL detail"));

            MvcTestResult result = post(body("2030-01-15T08:00:00Z", "2030-01-15T12:00:00Z"));

            assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
            assertThat(result).bodyText().doesNotContain("secret");
        }

        private MvcTestResult post(String json) {
            return mvc.post().uri("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
        }

        private String body(String startTime, String endTime) {
            return """
                    {"carId": "%s", "userId": "%s", "startTime": "%s", "endTime": "%s"}"""
                    .formatted(CAR, USER, startTime, endTime);
        }
    }

    @Nested
    class Get {

        @Test
        void returnsTheOwnersReservation() {
            given(service.get(RESERVATION, USER)).willReturn(reservation(ReservationStatus.CONFIRMED));

            MvcTestResult result = mvc.get().uri("/api/v1/reservations/{id}?userId={user}", RESERVATION, USER).exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(RESERVATION.toString());
        }

        @Test
        void requiresTheUserId() {
            MvcTestResult result = mvc.get().uri("/api/v1/reservations/{id}", RESERVATION).exchange();

            assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("userId");
        }

        @Test
        void rejectsAMalformedId() {
            MvcTestResult result = mvc.get().uri("/api/v1/reservations/not-a-uuid?userId={user}", USER).exchange();

            assertProblem(result, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
            assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("reservationId");
        }

        @Test
        void reportsAMissingOrForeignReservationAsNotFound() {
            given(service.get(RESERVATION, USER)).willThrow(new ReservationNotFoundException(RESERVATION));

            assertProblem(mvc.get().uri("/api/v1/reservations/{id}?userId={user}", RESERVATION, USER).exchange(),
                    HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND");
        }
    }

    @Nested
    class Cancel {

        @Test
        void returnsTheCancelledReservation() {
            given(service.cancel(RESERVATION, USER)).willReturn(reservation(ReservationStatus.CANCELLED));

            MvcTestResult result = cancel();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
        }

        @Test
        void reportsAnAlreadyCancelledReservationAsAConflict() {
            given(service.cancel(RESERVATION, USER)).willThrow(new ReservationAlreadyCancelledException(RESERVATION));

            assertProblem(cancel(), HttpStatus.CONFLICT, "RESERVATION_ALREADY_CANCELLED");
        }

        @Test
        void reportsAClosedCancellationWindowAsAConflict() {
            given(service.cancel(RESERVATION, USER)).willThrow(new CancellationWindowClosedException(RESERVATION));

            assertProblem(cancel(), HttpStatus.CONFLICT, "CANCELLATION_WINDOW_CLOSED");
        }

        @Test
        void reportsALostOptimisticLockAsARetryableConflict() {
            given(service.cancel(RESERVATION, USER))
                    .willThrow(new ObjectOptimisticLockingFailureException(Reservation.class, RESERVATION));

            assertProblem(cancel(), HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        }

        @Test
        void requiresTheUserId() {
            MvcTestResult result = mvc.post().uri("/api/v1/reservations/{id}/cancel", RESERVATION)
                    .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

            assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            verifyNoInteractions(service);
        }

        private MvcTestResult cancel() {
            return mvc.post().uri("/api/v1/reservations/{id}/cancel", RESERVATION)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"userId\": \"%s\"}".formatted(USER)).exchange();
        }
    }

    @Test
    void addsTheErrorCodeToSpringMvcsOwnErrors() {
        MvcTestResult result = mvc.put().uri("/api/v1/reservations").exchange();

        assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, "MALFORMED_REQUEST");
    }

    @Test
    void addsTheErrorCodeToAnUnknownPath() {
        MvcTestResult result = mvc.get().uri("/api/v1/no-such-resource").exchange();

        assertProblem(result, HttpStatus.NOT_FOUND, "MALFORMED_REQUEST");
    }

    @Test
    void addsTheErrorCodeToAnUnsupportedContentType() {
        MvcTestResult result = mvc.post().uri("/api/v1/reservations")
                .contentType(MediaType.TEXT_PLAIN).content("carId=" + CAR).exchange();

        assertProblem(result, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MALFORMED_REQUEST");
        verifyNoInteractions(service);
    }

    @Test
    void sendsNoBodyToAClientThatAcceptsNeitherJsonNorProblemJson() {
        MvcTestResult result = mvc.get().uri("/api/v1/reservations/{id}?userId={userId}", RESERVATION, USER)
                .accept(MediaType.TEXT_PLAIN).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_ACCEPTABLE);
        assertThat(result).bodyText().isEmpty();
        verifyNoInteractions(service);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    private static Reservation reservation(ReservationStatus status) {
        Reservation reservation = Reservation.book(CAR, USER, new BookingPeriod(START, END), START.minus(Duration.ofDays(1)));
        if (status == ReservationStatus.CANCELLED) {
            reservation.cancel(USER, START.minus(Duration.ofHours(1)));
        }
        org.springframework.test.util.ReflectionTestUtils.setField(reservation, "id", RESERVATION);
        return reservation;
    }
}
