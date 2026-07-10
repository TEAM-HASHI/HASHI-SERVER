package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

public enum RestaurantGenre {

    SUSHI("sushi", "스시/사시미류"),
    NOODLE("noodle", "면류"),
    RICE_BOWL("rice-bowl", "덮밥류"),
    NABE("nabe", "나베/냄비류"),
    FRIED("fried", "튀김류"),
    GRILL("grill", "철판/구이류"),
    ETC("etc", "기타");

    private final String value;
    private final String description;

    RestaurantGenre(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static Optional<RestaurantGenre> from(String value) {
        return Arrays.stream(values())
                .filter(genre -> genre.value.equals(value))
                .findFirst();
    }

    public String value() {
        return value;
    }

    public String description() {
        return description;
    }
}
