package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantLocationReviewPage;

@Schema(description = "식당 ID 오름차순 위치 검토 목록. 다음 요청에는 nextCursor를 그대로 전달")
public record RestaurantLocationReviewListResponse(
        @Schema(description = "위치 검토 대상 식당")
        List<RestaurantLocationReviewResponse> restaurants,
        @Schema(description = "다음 페이지 커서. hasNext가 false면 null", example = "1020", nullable = true)
        Long nextCursor,
        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext) {

    public static RestaurantLocationReviewListResponse from(RestaurantLocationReviewPage page) {
        return new RestaurantLocationReviewListResponse(
                page.restaurants().stream().map(RestaurantLocationReviewResponse::from).toList(),
                page.nextCursor(),
                page.hasNext());
    }
}
