package com.example.carreservation.common.json;

import java.time.Instant;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /** Registered after the JavaTimeModule, so it replaces the lenient default for {@link Instant}. */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictInstants() {
        return builder -> builder.deserializerByType(Instant.class, new StrictInstantDeserializer());
    }
}
