package org.sopt.hashi.restaurant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;

@Schema(description = "식당의 현재 사용 가능한 지도 좌표")
public record RestaurantMapLocationResponse(
        @Schema(description = "식당 ID", example = "1001") Long restaurantId,
        @Schema(description = "공개 가능한 현재 좌표와 UTC 유효 기한",
                example = "{\"latitude\":35.681236,\"longitude\":139.767125,"
                        + "\"validUntil\":\"2026-10-10T03:00:00Z\"}") LocationInfo location) {
}
