package com.example.carreservation.common.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Accepts only ISO-8601 date-times with an explicit offset ({@code 2030-01-15T08:00:00Z},
 * {@code 2030-01-15T10:00:00+02:00}).
 *
 * <p>Jackson's default {@code InstantDeserializer} also accepts JSON numbers and digit-only strings
 * as epoch seconds, so a client sending epoch milliseconds would silently book a car in the year
 * 61,000. A time without an offset is ambiguous, so it is rejected as well.
 */
public class StrictInstantDeserializer extends StdScalarDeserializer<Instant> {

    static final String EXPECTED_FORMAT = "an ISO-8601 date-time with an offset, e.g. 2030-01-15T08:00:00Z";

    public StrictInstantDeserializer() {
        super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (Instant) context.handleUnexpectedToken(Instant.class, parser.currentToken(), parser,
                    "Expected " + EXPECTED_FORMAT);
        }
        String text = parser.getText().trim();
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return (Instant) context.handleWeirdStringValue(Instant.class, text, "expected " + EXPECTED_FORMAT);
        }
    }
}
