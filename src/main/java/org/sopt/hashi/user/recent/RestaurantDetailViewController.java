package org.sopt.hashi.user.recent;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.restaurant.RestaurantMainInfo;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.http.HttpMethod;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class RestaurantDetailViewController {
    private final RestaurantDetailViewService service;

    RestaurantDetailViewController(RestaurantDetailViewService service) {
        this.service = service;
    }

    /** 식당 상세 요약 조회. 로그인한 회원이 조회하면 최근 본 맛집에 기록한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiResponse(responseCode = "404", description = "RESTAURANT-004 식당을 찾을 수 없습니다.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/api/v1/restaurants/{restaurantId}/summary")
    public SuccessResponse<RestaurantMainInfo> getRestaurantSummary(
            @Positive @PathVariable Long restaurantId, HttpMethod method) {
        return SuccessResponse.of(CommonSuccessCode.OK, service.getSummary(restaurantId, HttpMethod.GET.equals(method)));
    }
}
