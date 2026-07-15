package org.sopt.hashi.review.domain;

import java.util.Arrays;
import java.util.Optional;

public enum ReviewStatusFilter {

    ALL("all"),
    UNREVIEWED("unreviewed"),
    REVIEWED("reviewed");

    private final String value;

    ReviewStatusFilter(String value) {
        this.value = value;
    }

    public static Optional<ReviewStatusFilter> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(ALL);
        }
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst();
    }
}
