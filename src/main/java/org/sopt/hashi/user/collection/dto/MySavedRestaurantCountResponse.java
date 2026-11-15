package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "현재 USER가 컬렉션에 저장한 식당 수. 여러 컬렉션에 저장한 식당은 한 번만 세고 삭제된 식당은 제외")
public record MySavedRestaurantCountResponse(
        @Schema(description = "내 컬렉션 전체에서 중복을 제외한 저장 식당 수", example = "12")
        long savedRestaurantCount) {
}
