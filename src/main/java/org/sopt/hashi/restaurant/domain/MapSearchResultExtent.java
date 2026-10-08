package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 검색 조건에 맞는 전체 식당 수, 모든 유효 좌표의 경계와 가장 이른 만료. 0건이면 경계와 만료는 null이다. */
public record MapSearchResultExtent(long totalCount, ResultBounds bounds, Instant earliestValidUntil) {

    public MapSearchResultExtent {
        boolean empty = totalCount == 0;
        if (totalCount < 0 || empty != (bounds == null) || empty != (earliestValidUntil == null)) {
            throw new IllegalArgumentException("검색 결과 수와 경계 및 좌표 만료 시각이 일치해야 합니다");
        }
    }

    public static MapSearchResultExtent empty() {
        return new MapSearchResultExtent(0, null, null);
    }

    /** 단일 결과는 남북 또는 동서 값이 같을 수 있으므로 요청용 MapQueryBounds와 검증 규칙을 분리한다. */
    public record ResultBounds(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east) {

        public ResultBounds {
            if (!inRange(south, 90) || !inRange(north, 90) || !inRange(west, 180) || !inRange(east, 180)
                    || south.compareTo(north) > 0 || west.compareTo(east) > 0) {
                throw new IllegalArgumentException("검색 결과 경계가 올바르지 않습니다");
            }
        }

        private static boolean inRange(BigDecimal value, int maximum) {
            return value != null && value.abs().compareTo(BigDecimal.valueOf(maximum)) <= 0;
        }
    }
}
