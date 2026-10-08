package org.sopt.hashi.restaurant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;

@Schema(description = "지도 초기 설정과 활성 관광 지역 목록")
public record RestaurantMapRegionsResponse(
        @Schema(description = "지도 첫 진입 시 카메라 경계") BoundsResponse initialBounds,
        @Schema(description = "클라이언트 지도 조회 제한") QueryLimitsResponse queryLimits,
        @Schema(description = "표시 순서대로 정렬된 활성 관광 지역") List<RegionResponse> regions) {

    @Schema(description = "위도·경도 사각 경계")
    public record BoundsResponse(
            @Schema(description = "남쪽 위도", example = "35.0") BigDecimal south,
            @Schema(description = "북쪽 위도", example = "35.9") BigDecimal north,
            @Schema(description = "서쪽 경도", example = "139.0") BigDecimal west,
            @Schema(description = "동쪽 경도", example = "140.0") BigDecimal east) {
        public static BoundsResponse from(MapQueryBounds bounds) {
            return new BoundsResponse(bounds.south(), bounds.north(), bounds.west(), bounds.east());
        }
    }

    @Schema(description = "지도 조회 가능 영역과 한 번에 요청할 수 있는 최대 범위")
    public record QueryLimitsResponse(
            @Schema(description = "서버가 지원하는 전체 지도 경계") BoundsResponse supportedBounds,
            @Schema(description = "한 요청의 최대 위도 범위(도)", example = "1") int maxLatitudeSpan,
            @Schema(description = "한 요청의 최대 경도 범위(도)", example = "1") int maxLongitudeSpan) {
    }

    @Schema(description = "지도 좌표")
    public record PositionResponse(
            @Schema(description = "위도", example = "35.681236") BigDecimal latitude,
            @Schema(description = "경도", example = "139.767125") BigDecimal longitude) {
    }

    @Schema(description = "활성 관광 지역 클러스터")
    public record RegionResponse(
            @Schema(description = "지도 관광 지역 ID", example = "10") Long mapRegionId,
            @Schema(description = "관광 지역 표시명", example = "시부야") String name,
            @Schema(description = "현재 지도에 노출 가능한 식당 수", example = "24") long restaurantCount,
            @Schema(description = "축소 지도에서 표시할 클러스터 중심 좌표") PositionResponse clusterPosition,
            @Schema(description = "지역 선택 시 맞출 카메라 경계") BoundsResponse cameraBounds,
            @Schema(description = "관광 지역 표시 순서", example = "1") int displayOrder) {
    }
}
