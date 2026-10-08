package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;

/** 검색 조건에 맞는 전체 식당 수와 모든 유효 좌표를 포함하는 경계. 검색 결과가 없으면 bounds는 null이다. */
public record MapSearchResultExtent(long totalCount, ResultBounds bounds) {

    public MapSearchResultExtent {
        if (totalCount < 0 || (totalCount == 0) != (bounds == null)) {
            throw new IllegalArgumentException("검색 결과 수와 경계가 일치해야 합니다");
        }
    }

    public static MapSearchResultExtent empty() {
        return new MapSearchResultExtent(0, null);
    }

    /** 단일 결과는 남북 또는 동서 값이 같을 수 있으므로 요청용 MapQueryBounds와 검증 규칙을 분리한다. */
    public record ResultBounds(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east) {

        public ResultBounds {
            if (south == null || north == null || west == null || east == null
                    || south.compareTo(north) > 0 || west.compareTo(east) > 0) {
                throw new IllegalArgumentException("검색 결과 경계가 올바르지 않습니다");
            }
        }
    }
}
