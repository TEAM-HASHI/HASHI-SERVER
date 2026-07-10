package org.sopt.hashi.review.web;

import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.dto.MyReviewCountResponse;
import org.sopt.hashi.review.dto.MyReviewDetailResponse;
import org.sopt.hashi.review.dto.MyReviewListResponse;
import org.sopt.hashi.review.service.MyReviewService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
public class MyReviewController {

    private final MyReviewService myReviewService;

    public MyReviewController(MyReviewService myReviewService) {
        this.myReviewService = myReviewService;
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @GetMapping("/me")
    public SuccessResponse<MyReviewListResponse> getMyReviews(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK, myReviewService.getMyReviews(cursor, size));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = ReviewErrorCode.class, codes = {"NOT_FOUND", "RESTAURANT_NOT_FOUND"})
    @GetMapping("/me/{reviewId}")
    public SuccessResponse<MyReviewDetailResponse> getMyReview(@PathVariable Long reviewId) {
        return SuccessResponse.of(CommonSuccessCode.OK, myReviewService.getMyReview(reviewId));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping("/me/count")
    public SuccessResponse<MyReviewCountResponse> getMyReviewCount() {
        return SuccessResponse.of(CommonSuccessCode.OK, myReviewService.getMyReviewCount());
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = ReviewErrorCode.class, codes = {"NOT_FOUND"})
    @DeleteMapping("/{reviewId}")
    public SuccessResponse<Void> deleteMyReview(@PathVariable Long reviewId) {
        myReviewService.deleteMyReview(reviewId);
        return SuccessResponse.of(CommonSuccessCode.OK);
    }
}
