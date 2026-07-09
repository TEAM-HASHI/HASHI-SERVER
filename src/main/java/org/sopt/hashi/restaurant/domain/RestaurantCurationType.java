package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

public enum RestaurantCurationType {

    SNS_HOT("sns-hot"),
    POPULAR("popular"),
    HASHI_PICK("hashi-pick"),
    TODAY_RESTAURANT("today-restaurant");

    private final String value;

    RestaurantCurationType(String value) {
        this.value = value;
    }

    public static Optional<RestaurantCurationType> from(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }
}
