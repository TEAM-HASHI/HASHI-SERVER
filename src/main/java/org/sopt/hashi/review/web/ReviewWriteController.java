package org.sopt.hashi.review.web;

import jakarta.validation.Valid;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.dto.CreateReviewRequest;
import org.sopt.hashi.review.dto.CreateReviewResponse;
import org.sopt.hashi.review.service.ReviewWriteService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewWriteController {

    private final ReviewWriteService reviewWriteService;

    public ReviewWriteController(ReviewWriteService reviewWriteService) {
        this.reviewWriteService = reviewWriteService;
    }

    @ApiException(value = CommonErrorCode.class,
            codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = ReviewErrorCode.class,
            codes = {"ALREADY_REVIEWED", "NOT_VISITED", "UNSUPPORTED_KEYWORD", "RESTAURANT_NOT_FOUND"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<CreateReviewResponse> create(
            @Valid @RequestBody CreateReviewRequest request
    ) {
        return SuccessResponse.of(CommonSuccessCode.CREATED, reviewWriteService.create(request));
    }
}
