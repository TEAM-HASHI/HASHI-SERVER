package org.sopt.hashi.restaurant.domain;

import java.util.Locale;

/** 일반 목록에서 선택한 검색 조건을 지도에서도 같은 리터럴 부분 검색으로 적용한다. */
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
}
