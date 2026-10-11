package org.sopt.hashi.restaurant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;

@Schema(description = "선택한 공개 식당의 현재 표시 가능한 지도 위치")
public record RestaurantMapLocationResponse(
        @Schema(description = "식당 ID", example = "1") Long restaurantId,
        @Schema(description = "핀 좌표와 UTC 만료 시각. validUntil부터는 표시할 수 없음") LocationInfo location) {
}
