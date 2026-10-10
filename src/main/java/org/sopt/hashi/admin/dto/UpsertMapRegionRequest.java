package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** PUT 전체 교체 요청. 대표 좌표 포함 여부와 저장 정밀도는 restaurant 모델에서 검사한다. */
public record UpsertMapRegionRequest(
        @Schema(description = "클라이언트에 표시할 관광 지역명", example = "시부야")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "지역 선택 시 지도의 대표 중심점. cameraBounds 안에 있어야 하며 식당 배정에는 사용하지 않음. "
                + "좌표는 소수 6자리로 정확히 표현되는 값이어야 하고 서버가 반올림하지 않음")
        @NotNull @Valid Position clusterPosition,
        @Schema(description = "지역 필터·공개 집계에서 식당의 유효 좌표를 재검사하는 경계. 식당 소속을 자동 결정하지 않음. "
                + "모든 경계는 소수 6자리로 정확히 표현되어야 하며 서버가 반올림하지 않음. "
                + "active=true이면 서버 지원 범위 안에 있고 위도·경도 폭이 각각 1도 이하여야 함")
        @NotNull @Valid Bounds cameraBounds,
        @Schema(description = "공개 지역 목록의 표시 순서. 같은 값이면 mapRegionId 순", example = "10")
        @NotNull @PositiveOrZero Integer displayOrder,
        @Schema(description = "공개 여부. false이면 공개 지역 목록에서 제외되고 해당 지역 ID로 지도 조회 시 RESTAURANT-012",
                example = "true")
        @NotNull Boolean active) {
    public record Position(
            @Schema(description = "대표 중심점 위도", example = "35.659500")
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @Schema(description = "대표 중심점 경도", example = "139.700500")
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {
    }

    public record Bounds(
            @Schema(description = "남쪽 위도", example = "35.640000")
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal south,
            @Schema(description = "북쪽 위도. south보다 커야 함", example = "35.680000")
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal north,
            @Schema(description = "서쪽 경도", example = "139.680000")
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal west,
            @Schema(description = "동쪽 경도. west보다 커야 함", example = "139.720000")
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal east) {
    }
}
