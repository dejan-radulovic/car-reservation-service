package com.example.carreservation.car;

import com.example.carreservation.api.model.CarDto;
import com.example.carreservation.api.model.CarPageDto;

/** Maps cars to the generated API model; generated types never leave the web layer. */
final class CarApiMapper {

    private CarApiMapper() {
    }

    static CarPageDto toDto(CarSlice slice) {
        return new CarPageDto(
                slice.cars().stream().map(CarApiMapper::toDto).toList(),
                slice.page(),
                slice.size(),
                slice.hasNext());
    }

    static CarDto toDto(Car car) {
        return new CarDto(car.getId(), car.getMake(), car.getModel(), car.getLicensePlate());
    }
}
