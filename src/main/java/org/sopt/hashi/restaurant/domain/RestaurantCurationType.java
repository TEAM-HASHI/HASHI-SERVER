package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

public enum RestaurantCurationType {

    SNS_HOT("sns-hot"),
    POPULAR("popular"),
    // TODAY_RESTAURANT은 랜덤 추천이 전체 식당 대상으로 바뀌며 폐기(#154)
    HASHI_PICK("hashi-pick");

    private final String value;

    RestaurantCurationType(String value) {
        this.value = value;
    }

    public static Optional<RestaurantCurationType> from(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }

    public String value() {
        return value;
    }
}
