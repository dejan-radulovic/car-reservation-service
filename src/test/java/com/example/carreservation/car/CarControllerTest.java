package com.example.carreservation.car;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.carreservation.common.json.JacksonConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(CarController.class)
@Import(JacksonConfig.class)
class CarControllerTest {

    private static final Instant FROM = Instant.parse("2030-01-15T08:00:00Z");
    private static final Instant TO = Instant.parse("2030-01-15T12:00:00Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CarQueries cars;

    @Test
    void listsCarsWithDefaultPaging() {
        given(cars.list(0, 50)).willReturn(new CarSlice(List.of(car("BG-00001")), 0, 50, true));

        MvcTestResult result = mvc.get().uri("/api/v1/cars").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[0].licensePlate").isEqualTo("BG-00001");
        assertThat(result).bodyJson().extractingPath("$.page").isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.size").isEqualTo(50);
        assertThat(result).bodyJson().extractingPath("$.hasNext").isEqualTo(true);
    }

    @Test
    void findsAvailableCarsForAnIntervalGivenWithAnyOffset() {
        given(cars.available(any(), anyInt(), anyInt())).willReturn(new CarSlice(List.of(), 2, 10, false));

        MvcTestResult result = mvc.get().uri("/api/v1/cars/availability")
                .param("from", "2030-01-15T10:00:00+02:00")
                .param("to", "2030-01-15T12:00:00Z")
                .param("page", "2").param("size", "10")
                .exchange();

        assertThat(result).hasStatusOk();
        verify(cars).available(new SearchWindow(FROM, TO), 2, 10);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"size, 0", "size, 201", "page, -1"})
    void rejectsPagingOutsideTheContract(String parameter, String value) {
        MvcTestResult result = mvc.get().uri("/api/v1/cars").param(parameter, value).exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo(parameter);
        verifyNoInteractions(cars);
    }

    @Test
    void requiresBothEndsOfTheInterval() {
        MvcTestResult result = mvc.get().uri("/api/v1/cars/availability").param("from", FROM.toString()).exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("to");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2030-01-15T08:00:00", "1893484800", "2030-01-15T08:00:00 02:00", "tomorrow"})
    void rejectsTimesWithoutAnOffsetAsMalformed(String from) {
        // "2030-01-15T08:00:00 02:00" is what an unencoded "+02:00" turns into in a query string
        MvcTestResult result = mvc.get().uri("/api/v1/cars/availability")
                .param("from", from).param("to", TO.toString()).exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("from");
        verifyNoInteractions(cars);
    }

    @Test
    void rejectsAnInvertedWindowAsUnprocessable() {
        MvcTestResult result = mvc.get().uri("/api/v1/cars/availability")
                .param("from", TO.toString()).param("to", FROM.toString()).exchange();

        assertProblem(result, HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SEARCH_WINDOW");
    }

    @Test
    void rejectsAWindowLongerThanThirtyOneDaysAsUnprocessable() {
        MvcTestResult result = mvc.get().uri("/api/v1/cars/availability")
                .param("from", "2030-01-01T00:00:00Z").param("to", "2030-03-01T00:00:00Z").exchange();

        assertProblem(result, HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SEARCH_WINDOW");
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    private static Car car(String licensePlate) {
        Car car = new Car(); // cars are only ever loaded from the seeded table, so there is no public constructor
        ReflectionTestUtils.setField(car, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(car, "make", "Toyota");
        ReflectionTestUtils.setField(car, "model", "Corolla");
        ReflectionTestUtils.setField(car, "licensePlate", licensePlate);
        return car;
    }
}
