package com.example.carreservation.car;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SearchWindowTest {

    private static final Instant FROM = Instant.parse("2030-01-15T08:00:00Z");

    @Test
    void acceptsAnyPositiveLengthUpToThirtyOneDays() {
        assertThatCode(() -> new SearchWindow(FROM, FROM.plusNanos(1_000))).doesNotThrowAnyException();
        assertThatCode(() -> new SearchWindow(FROM, FROM.plus(Duration.ofDays(31)))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEmptyOrInvertedWindow() {
        assertThatThrownBy(() -> new SearchWindow(FROM, FROM)).isInstanceOf(InvalidSearchWindowException.class);
        assertThatThrownBy(() -> new SearchWindow(FROM, FROM.minusSeconds(1))).isInstanceOf(InvalidSearchWindowException.class);
    }

    @Test
    void rejectsAWindowLongerThanThirtyOneDays() {
        assertThatThrownBy(() -> new SearchWindow(FROM, FROM.plus(Duration.ofDays(31)).plusNanos(1_000)))
                .isInstanceOf(InvalidSearchWindowException.class)
                .hasMessageContaining("31 days");
    }
}
