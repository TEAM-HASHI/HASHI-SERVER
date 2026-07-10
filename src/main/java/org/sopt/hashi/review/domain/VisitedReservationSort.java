package org.sopt.hashi.review.domain;

import java.util.Arrays;
import java.util.Optional;

public enum VisitedReservationSort {

    LATEST("latest"),
    OLDEST("oldest");

    private final String value;

    VisitedReservationSort(String value) {
        this.value = value;
    }

    public static Optional<VisitedReservationSort> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(LATEST);
        }
        return Arrays.stream(values())
                .filter(sort -> sort.value.equals(value))
                .findFirst();
    }
}
