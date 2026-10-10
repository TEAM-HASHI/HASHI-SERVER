package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SearchRestaurantPlacesRequest(
        @Schema(description = "위치 상태 조회 응답의 최신 addressRevision. 서버의 최신 값과 다르면 409",
                example = "2")
        @NotNull(message = "주소 버전은 필수입니다")
        @Positive(message = "주소 버전은 양수입니다") Long expectedAddressRevision) {
}
