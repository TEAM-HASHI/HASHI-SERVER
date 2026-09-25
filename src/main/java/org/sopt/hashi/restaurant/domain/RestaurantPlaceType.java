package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 음식점 분류(음식점·카페·주점, #211) — 저장 컬렉션 상세의 분류 필터 축(SAVED-008).
 * 장르({@link RestaurantGenre})·음식 카테고리(자유 텍스트)와 별개의 상위 구분이며,
 * 어드민이 식당 등록·수정 시 사용자 API와 같은 소문자 값("restaurant", "cafe", "bar")으로 입력한다.
 */
public enum RestaurantPlaceType {

    RESTAURANT("restaurant", "음식점"),
    CAFE("cafe", "카페"),
    BAR("bar", "주점");

    private final String value;
    private final String description;

    RestaurantPlaceType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static Optional<RestaurantPlaceType> from(String value) {
        return Arrays.stream(values())
                .filter(placeType -> placeType.value.equals(value))
                .findFirst();
    }

    public String value() {
        return value;
    }

    public String description() {
        return description;
    }
}
