package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** 공개 식당의 최소 값 정보. location이 null이면 목록은 유지하고 핀만 생략한다. */
public record RestaurantMapInfo(Long restaurantId, String name, String placeType, String genre,
                                LocationInfo location) {

    /** validUntil은 UTC 절대 시각이며 이 시각부터 좌표를 표시할 수 없다. */
    public record LocationInfo(BigDecimal latitude, BigDecimal longitude, Instant validUntil,
            @Schema(description = "Google이 제공한 제3자 출처. 비어 있지 않으면 좌표와 함께 이름과 링크를 표시하고 좌표 만료 시 같이 제거. Google Maps 자체 로고/출처 표기와 별개")
            List<AttributionInfo> attributions) {
        public LocationInfo {
            attributions = List.copyOf(attributions);
        }

        public LocationInfo(BigDecimal latitude, BigDecimal longitude, Instant validUntil) {
            this(latitude, longitude, validUntil, List.of());
        }
    }

    public record AttributionInfo(String displayName, String uri) { }
}
