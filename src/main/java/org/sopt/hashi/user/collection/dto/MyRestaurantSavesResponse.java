package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "요청 식당별 현재 USER의 저장 여부. 삭제·미존재 식당은 제외")
public record MyRestaurantSavesResponse(
        @Schema(description = "요청 순서의 식당별 저장 여부. 카드와 restaurantId로 결합")
        List<MyRestaurantSave> restaurants) {

    public record MyRestaurantSave(
            @Schema(description = "식당 ID", example = "1001")
            Long restaurantId,
            @Schema(description = "현재 USER의 공개·비공개 컬렉션 중 하나 이상에 저장됐는지 여부",
                    example = "true")
            boolean saved) { }
}
