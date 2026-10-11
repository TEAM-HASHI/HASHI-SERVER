package org.sopt.hashi.restaurant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;

@Schema(description = "지도 최초 진입과 관광 지역 안내에 필요한 설정")
public record RestaurantMapRegionsResponse(
        @Schema(description = "최초 진입 시 카메라를 맞출 권장 범위") BoundsResponse initialBounds,
        @Schema(description = "서버가 허용하는 새 조회 범위 제한") QueryLimitsResponse queryLimits,
        @Schema(description = "표시 순서대로 정렬된 활성 관광 지역. 식당 수 0인 지역도 포함")
        List<RegionResponse> regions) {

    @Schema(description = "WGS84 위도·경도 경계")
    public record BoundsResponse(
            @Schema(description = "남쪽 위도", example = "35.0") BigDecimal south,
            @Schema(description = "북쪽 위도", example = "35.1") BigDecimal north,
            @Schema(description = "서쪽 경도", example = "139.0") BigDecimal west,
            @Schema(description = "동쪽 경도", example = "139.1") BigDecimal east) {
        public static BoundsResponse from(MapQueryBounds bounds) {
            return new BoundsResponse(bounds.south(), bounds.north(), bounds.west(), bounds.east());
        }
    }

    @Schema(description = "새 조회 BBOX의 허용 범위와 최대 크기")
    public record QueryLimitsResponse(
            @Schema(description = "BBOX가 완전히 포함되어야 하는 지원 범위") BoundsResponse supportedBounds,
            @Schema(description = "최대 위도 폭", example = "1") int maxLatitudeSpan,
            @Schema(description = "최대 경도 폭", example = "1") int maxLongitudeSpan) {
    }

    @Schema(description = "WGS84 지도 위치")
    public record PositionResponse(
            @Schema(description = "위도", example = "35.05") BigDecimal latitude,
            @Schema(description = "경도", example = "139.05") BigDecimal longitude) {
    }

    @Schema(description = "활성 관광 지역 클러스터")
    public record RegionResponse(
            @Schema(description = "관광 지역 ID", example = "1") Long mapRegionId,
            @Schema(description = "관광 지역 이름", example = "신주쿠") String name,
            @Schema(description = "지역 ID와 cameraBounds를 모두 만족하는 현재 표시 가능 식당 수", example = "24")
            long restaurantCount,
            @Schema(description = "관광 지역 클러스터 표시 위치") PositionResponse clusterPosition,
            @Schema(description = "관광 지역 선택 뒤 카메라를 맞출 범위") BoundsResponse cameraBounds,
            @Schema(description = "클러스터 표시 순서", example = "1") int displayOrder) {
    }
}
