package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

public enum RestaurantListType {

    ALL("all", null),
    SNS_HOT("sns-hot", RestaurantCurationType.SNS_HOT),
    POPULAR("popular", RestaurantCurationType.POPULAR),
    HASHI_PICK("hashi-pick", RestaurantCurationType.HASHI_PICK),
    TODAY_RESTAURANT("today-restaurant", RestaurantCurationType.TODAY_RESTAURANT);

    private final String value;
    private final RestaurantCurationType curationType;

    RestaurantListType(String value, RestaurantCurationType curationType) {
        this.value = value;
        this.curationType = curationType;
    }

    public static Optional<RestaurantListType> from(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }

    public boolean isAll() {
        return this == ALL;
    }

    public RestaurantCurationType curationType() {
        return curationType;
    }
}
