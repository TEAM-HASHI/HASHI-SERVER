package org.sopt.hashi.restaurant.domain;

import java.util.List;
import java.util.Locale;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

/** 일반 검색 전용 입력. 지도 검색의 정책과 독립적으로 유지한다. */
public record RestaurantSearchQuery(List<String> terms, boolean hashtagOnly) {

    public RestaurantSearchQuery {
        terms = List.copyOf(terms);
    }

    public static RestaurantSearchQuery parse(String keyword) {
        String value = RestaurantSearchKeyword.normalize(keyword);
        if (value == null) {
            return new RestaurantSearchQuery(List.of(), false);
        }
        if (value.codePointCount(0, value.length()) > 30) {
            throw invalid();
        }
        boolean hashtag = value.startsWith("#");
        if (hashtag) {
            value = value.substring(1);
            if (value.isEmpty() || value.matches("(?s).*(?U)\\s.*")) {
                throw invalid();
            }
        }
        return new RestaurantSearchQuery(RestaurantSearchKeyword.terms(value.toLowerCase(Locale.ROOT))
                .stream().distinct().sorted().toList(), hashtag);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
}
