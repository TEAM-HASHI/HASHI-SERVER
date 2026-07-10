package org.sopt.hashi.review.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.dto.ReviewContextResponse;
import org.sopt.hashi.review.dto.VisitedReservationListResponse;
import org.sopt.hashi.review.service.ReviewReservationQueryService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewReservationQueryController {

    private final ReviewReservationQueryService reviewReservationQueryService;

    public ReviewReservationQueryController(
            ReviewReservationQueryService reviewReservationQueryService
    ) {
        this.reviewReservationQueryService = reviewReservationQueryService;
    }

    @ApiException(value = CommonErrorCode.class,
            codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = ReviewErrorCode.class, codes = {"RESTAURANT_NOT_FOUND"})
    @GetMapping("/context")
    public SuccessResponse<ReviewContextResponse> getContext(
            @Positive @RequestParam Long reservationId
    ) {
        return SuccessResponse.of(
                CommonSuccessCode.OK,
                reviewReservationQueryService.getContext(reservationId));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = ReviewErrorCode.class,
            codes = {"UNSUPPORTED_STATUS", "UNSUPPORTED_SORT", "RESTAURANT_NOT_FOUND"})
    @GetMapping("/visited-reservations")
    public SuccessResponse<VisitedReservationListResponse> getVisitedReservations(
            @RequestParam(required = false) String reviewStatus,
            @Positive @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) String sort,
            @Positive @RequestParam(required = false) Long cursor,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(
                CommonSuccessCode.OK,
                reviewReservationQueryService.getVisitedReservations(
                        reviewStatus,
                        restaurantId,
                        sort,
                        cursor,
                        size));
    }
}
