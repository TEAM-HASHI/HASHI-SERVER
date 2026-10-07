package org.sopt.hashi.admin.dto;

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
        @NotBlank @Size(max = 100) String name,
        @NotNull @Valid Position clusterPosition,
        @NotNull @Valid Bounds cameraBounds,
        @NotNull @PositiveOrZero Integer displayOrder,
        @NotNull Boolean active) {
    public record Position(
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {
    }

    public record Bounds(
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal south,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal north,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal west,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal east) {
    }
}
