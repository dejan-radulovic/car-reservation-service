package com.example.carreservation.car;

import java.util.List;

/**
 * One page of cars, without a total count: counting all available cars would repeat the
 * availability query over the whole fleet for every page.
 */
public record CarSlice(List<Car> cars, int page, int size, boolean hasNext) {

    /** Builds a slice from a query that fetched one row more than {@code size}. */
    static CarSlice of(List<Car> fetched, int page, int size) {
        boolean hasNext = fetched.size() > size;
        return new CarSlice(hasNext ? fetched.subList(0, size) : fetched, page, size, hasNext);
    }
}
