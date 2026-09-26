package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.time.Instant;

/** 공개 식당의 최소 값 정보. location이 null이면 목록은 유지하고 핀만 생략한다. */
public record RestaurantMapInfo(Long restaurantId, String name, String placeType, String genre,
                                LocationInfo location) {

    /** validUntil은 UTC 절대 시각이며 이 시각부터 좌표를 표시할 수 없다. */
    public record LocationInfo(BigDecimal latitude, BigDecimal longitude, Instant validUntil) {
    }
}
