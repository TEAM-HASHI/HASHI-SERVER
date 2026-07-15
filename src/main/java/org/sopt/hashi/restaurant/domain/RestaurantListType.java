package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

public enum RestaurantListType {

    ALL("all", null),
    SNS_HOT("sns-hot", RestaurantCurationType.SNS_HOT),
    POPULAR("popular", RestaurantCurationType.POPULAR),
    // today-restaurant 목록 유형은 큐레이션 폐기(#154)와 함께 제거 — 사용 시 UNSUPPORTED_LIST_TYPE(400)
    HASHI_PICK("hashi-pick", RestaurantCurationType.HASHI_PICK);

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
