package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** UNRESOLVED(위치 행 없음)의 revision은 0이다. */
public record RetryRestaurantLocationRequest(
        @Schema(description = "위치 상태 조회 응답의 최신 addressRevision. 주소 변경과 재시도의 충돌을 막는 조건값이며 UNRESOLVED는 0. 서버의 최신 값과 다르면 409",
                example = "1")
        @NotNull(message = "주소 버전은 필수입니다")
        @PositiveOrZero(message = "주소 버전은 0 이상입니다") Long expectedAddressRevision) {
}
