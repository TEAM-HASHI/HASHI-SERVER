package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;

/** 일반 목록의 basic/popular/rating과 분리된 지도 정렬 계약. */
public enum RestaurantMapSort {
    RECOMMEND("recommend"), RATING("rating"), REVIEWS("reviews");

    private final String value;

    RestaurantMapSort(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RestaurantMapSort parse(String value) {
        return Arrays.stream(values()).filter(sort -> sort.value.equals(value)).findFirst()
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_SORT));
    }
}
