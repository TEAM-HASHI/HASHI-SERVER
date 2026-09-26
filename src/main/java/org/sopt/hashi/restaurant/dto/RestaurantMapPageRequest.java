package org.sopt.hashi.restaurant.dto;

import java.util.Set;
import java.math.BigDecimal;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.util.MultiValueMap;

/** 세 모드를 먼저 분리해 중복 값이나 무시되는 필터가 생기지 않도록 한다. */
public record RestaurantMapPageRequest(MapSearchCriteria criteria, RestaurantMapSort sort,
                                       String querySessionId, String cursor) {

    private static final int MAX_COORDINATE_LENGTH = 128;
    private static final int MAX_COORDINATE_SCALE = 128;
    private static final int MAX_COORDINATE_PRECISION = 128;
    private static final Set<String> NEW_QUERY = Set.of("south", "north", "west", "east",
            "mapRegionId", "keyword", "genre", "placeType", "sort");
    private static final Set<String> BOUNDS = Set.of("south", "north", "west", "east");

    public static RestaurantMapPageRequest from(MultiValueMap<String, String> parameters) {
        if (parameters.values().stream().anyMatch(values -> values == null || values.size() != 1
                || values.getFirst() == null)) {
            throw invalid();
        }
        if (parameters.containsKey("cursor")) {
            String cursor = parameters.getFirst("cursor");
            if (parameters.size() != 1 || cursor.isBlank() || cursor.length() > 512) {
                throw invalid();
            }
            return new RestaurantMapPageRequest(null, null, null, cursor);
        }
        if (parameters.containsKey("querySessionId")) {
            if (!parameters.keySet().equals(Set.of("querySessionId", "sort"))) {
                throw invalid();
            }
            return new RestaurantMapPageRequest(null, RestaurantMapSort.parse(parameters.getFirst("sort")),
                    parameters.getFirst("querySessionId"), null);
        }
        if (!NEW_QUERY.containsAll(parameters.keySet()) || !parameters.keySet().containsAll(BOUNDS)) {
            throw invalid();
        }
        Long region = null;
        try {
            if (parameters.containsKey("mapRegionId")) {
                String value = parameters.getFirst("mapRegionId");
                if (!value.matches("[1-9][0-9]{0,18}")) {
                    throw invalid();
                }
                region = Long.valueOf(value);
            }
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        MapQueryBounds bounds = new MapQueryBounds(coordinate(parameters.getFirst("south")),
                coordinate(parameters.getFirst("north")), coordinate(parameters.getFirst("west")),
                coordinate(parameters.getFirst("east")));
        return new RestaurantMapPageRequest(MapSearchCriteria.of(bounds, region, parameters.getFirst("genre"),
                parameters.getFirst("placeType"), parameters.getFirst("keyword")),
                parameters.containsKey("sort") ? RestaurantMapSort.parse(parameters.getFirst("sort"))
                        : RestaurantMapSort.RECOMMEND, null, null);
    }

    @Override
    public String toString() {
        return "RestaurantMapPageRequest[redacted]";
    }

    /** 산술 전에 자원 상한을 검사한다. 허용된 SDK 소수는 반올림하거나 절삭하지 않는다. */
    private static BigDecimal coordinate(String value) {
        try {
            if (value == null || value.length() > MAX_COORDINATE_LENGTH) {
                throw new NumberFormatException();
            }
            BigDecimal decimal = new BigDecimal(value);
            if (Math.abs((long) decimal.scale()) > MAX_COORDINATE_SCALE
                    || decimal.precision() > MAX_COORDINATE_PRECISION) {
                throw new NumberFormatException();
            }
            return decimal;
        } catch (NumberFormatException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_BOUNDS_INVALID);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
}
