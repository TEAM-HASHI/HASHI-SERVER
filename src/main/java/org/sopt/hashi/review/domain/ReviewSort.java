package org.sopt.hashi.review.domain;

import java.util.Arrays;
import java.util.Optional;

public enum ReviewSort {

    LATEST("latest"),
    RATING_HIGH("rating-high"),
    RATING_LOW("rating-low");

    private final String value;

    ReviewSort(String value) {
        this.value = value;
    }

    public static Optional<ReviewSort> from(String value) {
        return Arrays.stream(values())
                .filter(sort -> sort.value.equals(value))
                .findFirst();
    }
}
