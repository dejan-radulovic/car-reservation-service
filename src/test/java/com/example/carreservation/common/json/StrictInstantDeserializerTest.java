package com.example.carreservation.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Uses an ObjectMapper built the way Spring Boot builds it, including the JavaTimeModule. */
class StrictInstantDeserializerTest {

    private final ObjectMapper mapper = mapperWith(new JacksonConfig());

    record Payload(Instant at) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"2030-01-15T08:00:00Z", "2030-01-15T10:00:00+02:00", "2030-01-15T03:00:00-05:00"})
    void acceptsIsoDateTimesWithAnOffset(String value) throws Exception {
        Payload payload = mapper.readValue("{\"at\": \"" + value + "\"}", Payload.class);

        assertThat(payload.at()).isEqualTo(Instant.parse("2030-01-15T08:00:00Z"));
    }

    @Test
    void keepsFractionalSeconds() throws Exception {
        Payload payload = mapper.readValue("{\"at\": \"2030-01-15T08:00:00.123456Z\"}", Payload.class);

        assertThat(payload.at()).isEqualTo(Instant.parse("2030-01-15T08:00:00.123456Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2030-01-15T08:00:00", "2030-01-15", "1893456000", "tomorrow", ""})
    void rejectsStringsWithoutAnOffsetOrThatAreNotDateTimes(String value) {
        assertThatThrownBy(() -> mapper.readValue("{\"at\": \"" + value + "\"}", Payload.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("ISO-8601");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1893456000", "1893456000000", "1893456000.5", "true", "[]", "{}"})
    void rejectsNonStringTokensSuchAsEpochNumbers(String json) {
        assertThatThrownBy(() -> mapper.readValue("{\"at\": " + json + "}", Payload.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void leavesNullToBeanValidation() throws Exception {
        assertThat(mapper.readValue("{\"at\": null}", Payload.class).at()).isNull();
    }

    private static ObjectMapper mapperWith(JacksonConfig config) {
        var builder = new Jackson2ObjectMapperBuilder();
        config.strictInstants().customize(builder);
        return builder.build();
    }
}
