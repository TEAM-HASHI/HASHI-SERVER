package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

public enum RestaurantSort {

    BASIC("basic"),
    POPULAR("popular"),
    RATING("rating");

    private final String value;

    RestaurantSort(String value) {
        this.value = value;
    }

    public static Optional<RestaurantSort> from(String value) {
        return Arrays.stream(values())
                .filter(sort -> sort.value.equals(value))
                .findFirst();
    }
}
