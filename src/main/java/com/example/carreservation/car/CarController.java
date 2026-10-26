package com.example.carreservation.car;

import com.example.carreservation.api.CarsApi;
import com.example.carreservation.api.model.CarPageDto;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the generated contract. Mappings, defaults and parameter constraints (page ≥ 0,
 * 1 ≤ size ≤ 200) come from {@link CarsApi} and must not be repeated here.
 */
@RestController
class CarController implements CarsApi {

    private final CarQueries cars;

    CarController(CarQueries cars) {
        this.cars = cars;
    }

    @Override
    public ResponseEntity<CarPageDto> listCars(Integer page, Integer size) {
        return ResponseEntity.ok(CarApiMapper.toDto(cars.list(page, size)));
    }

    @Override
    public ResponseEntity<CarPageDto> findAvailableCars(Instant from, Instant to, Integer page, Integer size) {
        return ResponseEntity.ok(CarApiMapper.toDto(cars.available(new SearchWindow(from, to), page, size)));
    }
}
