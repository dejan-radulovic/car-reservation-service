package com.example.carreservation.car;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries over the fleet, one page at a time and always ordered by license plate. */
@Service
@Transactional(readOnly = true)
public class CarQueries {

    private static final Sort BY_LICENSE_PLATE = Sort.by("licensePlate");

    private final CarRepository cars;
    private final CarAvailability availability;

    CarQueries(CarRepository cars, CarAvailability availability) {
        this.cars = cars;
        this.availability = availability;
    }

    public CarSlice list(int page, int size) {
        // a Slice fetches one extra row to know whether there is a next page, without counting
        Slice<Car> slice = cars.findAllBy(PageRequest.of(page, size, BY_LICENSE_PLATE));
        return new CarSlice(slice.getContent(), page, size, slice.hasNext());
    }

    public CarSlice available(SearchWindow window, int page, int size) {
        int offset = Math.multiplyExact(page, size);
        return CarSlice.of(availability.findAvailable(window, offset, size + 1), page, size);
    }
}
