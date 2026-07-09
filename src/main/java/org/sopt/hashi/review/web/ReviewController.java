package org.sopt.hashi.review.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.dto.RestaurantReviewResponse;
import org.sopt.hashi.review.service.ReviewService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/restaurants/{restaurantId}/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = ReviewErrorCode.class, codes = {"UNSUPPORTED_SORT", "RESTAURANT_NOT_FOUND"})
    @GetMapping
    public SuccessResponse<RestaurantReviewResponse> getRestaurantReviews(
            @Positive @PathVariable Long restaurantId,
            @RequestParam(required = false) String sort,
            @Positive @RequestParam(required = false) Long cursor,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                reviewService.getRestaurantReviews(restaurantId, sort, cursor, size));
    }
}
