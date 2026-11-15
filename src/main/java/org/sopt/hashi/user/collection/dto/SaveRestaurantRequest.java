package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 컬렉션에 식당 저장 요청(SAVED-006) — 한 번에 한 컬렉션·한 식당. */
public record SaveRestaurantRequest(
        @Schema(description = "저장할 식당 ID", example = "1001")
        @NotNull(message = "식당 ID는 필수입니다")
        @Positive(message = "식당 ID는 1 이상이어야 합니다") Long restaurantId) {
}
