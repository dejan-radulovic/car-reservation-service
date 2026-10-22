package com.example.carreservation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/**
 * Full application context on a random port, against a real PostgreSQL container, with a
 * controllable {@link MutableClock}.
 *
 * <p>Every integration test uses exactly this configuration, so Spring's context cache shares one
 * context, and therefore one container, across all of them. Service-level and HTTP-level tests alike.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
public @interface IntegrationTest {
}
