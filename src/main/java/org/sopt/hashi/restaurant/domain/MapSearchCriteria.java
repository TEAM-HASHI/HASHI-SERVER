package org.sopt.hashi.restaurant.domain;

import java.util.List;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

/** HTTP 페이지 API와 Redis 세션이 공통으로 사용할 정규화된 조회 조건. */
public record MapSearchCriteria(MapQueryBounds bounds, Long mapRegionId, RestaurantGenre genre,
                                RestaurantPlaceType placeType, String keyword) {

    public MapSearchCriteria {
        if (bounds == null || (mapRegionId != null && mapRegionId <= 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        keyword = normalizeKeyword(keyword);
    }

    public static MapSearchCriteria of(MapQueryBounds bounds, Long regionId, String genre,
                                       String placeType, String keyword) {
        RestaurantGenre parsedGenre = genre == null ? null : RestaurantGenre.from(genre)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_GENRE));
        RestaurantPlaceType parsedType = placeType == null ? null : RestaurantPlaceType.from(placeType)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_PLACE_TYPE));
        return new MapSearchCriteria(bounds, regionId, parsedGenre, parsedType, keyword);
    }

    public boolean hashtagOnly() {
        return keyword != null && keyword.startsWith("#");
    }

    /** !를 명시적 LIKE escape 문자로 사용한다. 사용자 입력의 !, %, _ 모두 리터럴이다. */
    public List<String> keywordPatterns() {
        if (keyword == null) {
            return List.of();
        }
        String searchText = hashtagOnly() ? keyword.substring(1) : keyword;
        return RestaurantSearchKeyword.terms(searchText).stream()
                .map(RestaurantSearchKeyword::containsPattern)
                .toList();
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        boolean forbidden = keyword.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
        if (forbidden) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        String normalized = RestaurantSearchKeyword.normalize(keyword);
        if (normalized == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        int length = normalized.codePointCount(0, normalized.length());
        if (length > 30) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (normalized.startsWith("#")
                && (normalized.length() == 1 || RestaurantSearchKeyword.terms(normalized).size() != 1)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return normalized;
    }
}
