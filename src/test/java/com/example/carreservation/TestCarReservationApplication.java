package com.example.carreservation;

import org.springframework.boot.SpringApplication;

/** Runs the application locally against a throwaway PostgreSQL container: {@code ./mvnw spring-boot:test-run}. */
public class TestCarReservationApplication {

    public static void main(String[] args) {
        SpringApplication.from(CarReservationApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
