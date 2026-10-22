package com.example.carreservation.car;

import java.util.UUID;

public class CarNotFoundException extends RuntimeException {

    public CarNotFoundException(UUID carId) {
        super("No car with id " + carId);
    }
}
