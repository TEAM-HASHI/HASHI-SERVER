package org.sopt.hashi.user.collection.domain;

import java.util.Arrays;
import java.util.Optional;

/** 컬렉션 저장 식당 정렬(SAVED-008) — 최신순(저장 순, 기본)·별점순·리뷰순. */
public enum SavedRestaurantSort {

    LATEST("latest"),
    RATING("rating"),
    REVIEW("review");

    private final String value;

    SavedRestaurantSort(String value) {
        this.value = value;
    }

    public static Optional<SavedRestaurantSort> from(String value) {
        return Arrays.stream(values())
                .filter(sort -> sort.value.equals(value))
                .findFirst();
    }

    public String value() {
        return value;
    }
}
