package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 현재 존재하는 식당의 저장 사용자 수. 같은 사용자의 여러 컬렉션은 한 번만 센다. */
@Schema(description = "요청 식당별 저장 회원 수. 삭제·미존재 식당은 제외")
public record RestaurantSaveCountsResponse(
        @Schema(description = "요청 순서의 식당별 저장 수. 카드와 restaurantId로 결합")
        List<RestaurantSaveCount> restaurants) {

    public record RestaurantSaveCount(
            @Schema(description = "식당 ID", example = "1001")
            Long restaurantId,
            @Schema(description = "식당을 하나 이상의 컬렉션에 저장한 고유 회원 수. 한 회원의 여러 저장은 1명으로 계산",
                    example = "27")
            long saveCount) { }
}
