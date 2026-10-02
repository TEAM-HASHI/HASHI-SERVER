package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** UNRESOLVED(위치 행 없음)의 revision은 0이다. */
public record RetryRestaurantLocationRequest(
        @NotNull(message = "주소 버전은 필수입니다")
        @PositiveOrZero(message = "주소 버전은 0 이상입니다") Long expectedAddressRevision) {
}
