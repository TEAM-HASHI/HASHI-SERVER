package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 저장 식당 이동 요청(SAVED-004) — 선택한 식당들을 다른 컬렉션으로 옮긴다.
 * 대상 컬렉션에 이미 있는 식당이 하나라도 있으면 전체 실패한다.
 */
public record MoveSavedRestaurantsRequest(
        @Schema(description = "이동 대상 컬렉션 ID", example = "2")
        @NotNull(message = "대상 컬렉션 ID는 필수입니다")
        @Positive(message = "대상 컬렉션 ID는 1 이상이어야 합니다") Long targetCollectionId,
        @Schema(description = "이동할 식당 ID 목록", example = "[1001, 1002]")
        @NotEmpty(message = "이동할 식당은 최소 1개 이상 필요합니다")
        List<@NotNull(message = "식당 ID는 null일 수 없습니다")
        @Positive(message = "식당 ID는 1 이상이어야 합니다") Long> restaurantIds) {
}
