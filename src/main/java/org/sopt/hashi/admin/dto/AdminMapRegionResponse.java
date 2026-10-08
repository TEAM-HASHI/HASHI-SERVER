package org.sopt.hashi.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.AdminMapRegionInfo;

public record AdminMapRegionResponse(
        @Schema(description = "변하지 않는 관광 지역 ID", example = "12") Long mapRegionId,
        @Schema(description = "생성·전체 교체의 멱등 키", example = "TOKYO_SHIBUYA") String code,
        @Schema(description = "클라이언트 표시명", example = "시부야") String name,
        @Schema(description = "지역 선택 시 지도의 대표 중심점") Position clusterPosition,
        @Schema(description = "지역 필터·공개 집계의 좌표 경계") Bounds cameraBounds,
        @Schema(description = "공개 지역 목록 표시 순서", example = "10") int displayOrder,
        @Schema(description = "공개 지역 목록과 지역 지도 조회에 노출되는지 여부", example = "true") boolean active) {
    public static AdminMapRegionResponse from(AdminMapRegionInfo info) {
        return new AdminMapRegionResponse(info.mapRegionId(), info.code(), info.name(),
                new Position(info.latitude(), info.longitude()),
                new Bounds(info.south(), info.north(), info.west(), info.east()), info.displayOrder(), info.active());
    }

    public record Position(
            @Schema(description = "대표 중심점 위도", example = "35.659500") BigDecimal latitude,
            @Schema(description = "대표 중심점 경도", example = "139.700500") BigDecimal longitude) {
    }

    public record Bounds(
            @Schema(description = "남쪽 위도", example = "35.640000") BigDecimal south,
            @Schema(description = "북쪽 위도", example = "35.680000") BigDecimal north,
            @Schema(description = "서쪽 경도", example = "139.680000") BigDecimal west,
            @Schema(description = "동쪽 경도", example = "139.720000") BigDecimal east) {
    }

    public record Page(
            @Schema(description = "현재 페이지의 지역 설정. 비활성 초안도 포함") List<AdminMapRegionResponse> content,
            @Schema(description = "0부터 시작하는 현재 페이지", example = "0") int page,
            @Schema(description = "요청한 페이지 크기", example = "20") int size,
            @Schema(description = "활성·비활성 지역 전체 개수", example = "3") long totalElements,
            @Schema(description = "전체 페이지 수", example = "1") int totalPages) {
        public static Page from(AdminMapRegionInfo.Page info) {
            return new Page(info.content().stream().map(AdminMapRegionResponse::from).toList(),
                    info.page(), info.size(), info.totalElements(), info.totalPages());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Assignment(
            @Schema(description = "처리한 식당 ID", example = "1001") Long restaurantId,
            @Schema(description = "현재 배정된 관광 지역 ID. 소속 해제 후에는 null", example = "12", nullable = true)
            Long mapRegionId) {
    }
}
