package org.sopt.hashi.review.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.dto.MyReviewCountResponse;
import org.sopt.hashi.review.dto.MyReviewDetailResponse;
import org.sopt.hashi.review.dto.MyReviewListResponse;
import org.sopt.hashi.review.service.MyReviewService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 리뷰 조회·삭제 API. */
@Validated
@RestController
@RequestMapping("/api/v1/reviews")
public class MyReviewController {

    private final MyReviewService myReviewService;

    public MyReviewController(MyReviewService myReviewService) {
        this.myReviewService = myReviewService;
    }

    /** 내 리뷰 목록 조회(커서 페이지네이션). */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @GetMapping("/me")
    public SuccessResponse<MyReviewListResponse> getMyReviews(
            @Positive @RequestParam(required = false) Long cursor,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK, myReviewService.getMyReviews(cursor, size));
    }

    /** 내 리뷰 상세 조회 — 본인 소유가 아니면 404. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @ApiException(value = ReviewErrorCode.class, codes = {"NOT_FOUND", "RESTAURANT_NOT_FOUND"})
    @GetMapping("/me/{reviewId}")
    public SuccessResponse<MyReviewDetailResponse> getMyReview(@Positive @PathVariable Long reviewId) {
        return SuccessResponse.of(CommonSuccessCode.OK, myReviewService.getMyReview(reviewId));
    }

    /** 내 리뷰 개수 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping("/me/count")
    public SuccessResponse<MyReviewCountResponse> getMyReviewCount() {
        return SuccessResponse.of(CommonSuccessCode.OK, myReviewService.getMyReviewCount());
    }

    /** 내 리뷰 삭제 — 본인 소유가 아니면 404. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @ApiException(value = ReviewErrorCode.class, codes = {"NOT_FOUND"})
    @DeleteMapping("/{reviewId}")
    public SuccessResponse<Void> deleteMyReview(@Positive @PathVariable Long reviewId) {
        myReviewService.deleteMyReview(reviewId);
        return SuccessResponse.of(CommonSuccessCode.OK);
    }
}
