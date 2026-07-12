package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

/** 카드에 표시하는 음식 분류. 장르 필터({@link RestaurantGenre})와 별도 축으로 관리한다. */
public enum RestaurantFoodCategory {

    SUSHI("sushi", "초밥"),
    NOODLE("noodle", "면류"),
    RICE_BOWL("rice-bowl", "덮밥류"),
    NABE("nabe", "나베/냄비류"),
    FRIED("fried", "튀김류"),
    GRILL("grill", "철판/구이류"),
    ETC("etc", "기타");

    private final String value;
    private final String description;

    RestaurantFoodCategory(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static Optional<RestaurantFoodCategory> from(String value) {
        return Arrays.stream(values())
                .filter(category -> category.value.equals(value))
                .findFirst();
    }

    public String value() {
        return value;
    }

    public String description() {
        return description;
    }
}
