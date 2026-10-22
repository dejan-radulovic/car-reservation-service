package com.example.carreservation.common.time;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The single source of "now", so that time-dependent rules are testable with a fixed clock. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /**
     * Ticks in microseconds, PostgreSQL's timestamp precision, so that timestamps returned right after
     * a write (e.g. {@code createdAt}) are identical to the ones read back later.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
