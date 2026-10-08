package org.sopt.hashi.restaurant.domain;

import java.util.List;
import java.util.Locale;

/** 검색어의 Unicode 앞뒤 공백 제거와 SQL LIKE 리터럴 이스케이프를 한 곳에서 관리한다. */
final class RestaurantSearchKeyword {

    static final char LIKE_ESCAPE = '!';

    private RestaurantSearchKeyword() {
    }

    static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.replaceAll("(?U)^\\s+|\\s+$", "");
        return normalized.isEmpty() ? null : normalized;
    }

    static String containsPattern(String normalizedKeyword) {
        return normalizedKeyword == null ? null : "%" + normalizedKeyword.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    static List<String> terms(String normalizedKeyword) {
        return normalizedKeyword == null ? List.of() : List.of(normalizedKeyword.split("(?U)\\s+"));
    }
}
