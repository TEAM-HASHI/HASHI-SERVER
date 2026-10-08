package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantLocationReviewPage;

@Schema(description = "식당 ID 오름차순 관리자 위치 검토 목록. page는 0부터 시작")
public record RestaurantLocationReviewListResponse(
        @Schema(description = "위치 검토 대상 식당")
        List<RestaurantLocationReviewResponse> restaurants,
        @Schema(description = "현재 페이지 번호(0부터 시작)", example = "0")
        int page,
        @Schema(description = "요청한 페이지 크기", example = "20")
        int size,
        @Schema(description = "필터에 맞는 전체 식당 수", example = "42")
        long totalCount,
        @Schema(description = "전체 페이지 수", example = "3")
        int totalPages) {

    public static RestaurantLocationReviewListResponse from(RestaurantLocationReviewPage page) {
        return new RestaurantLocationReviewListResponse(
                page.restaurants().stream().map(RestaurantLocationReviewResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalCount(),
                page.totalPages());
    }
}
