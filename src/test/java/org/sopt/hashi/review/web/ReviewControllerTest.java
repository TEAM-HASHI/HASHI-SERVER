package org.sopt.hashi.review.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.dto.RestaurantReviewResponse;
import org.sopt.hashi.review.dto.RestaurantReviewResponse.RatingDistributionResponse;
import org.sopt.hashi.review.dto.RestaurantReviewResponse.ReviewSummaryResponse;
import org.sopt.hashi.review.service.ReviewService;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = ReviewController.class,
        excludeFilters = @Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {OnboardingJwtIssuer.class, JwtAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    @Test
    void 식당_리뷰_목록_조회_요청이면_공통_성공_응답을_반환한다() throws Exception {
        RestaurantReviewResponse response = new RestaurantReviewResponse(
                1L,
                4.5,
                1L,
                new RatingDistributionResponse(1L, 0L, 0L, 0L, 0L),
                List.of(new ReviewSummaryResponse(
                        10L,
                        "하루",
                        "https://cdn.example.com/users/1/profile.jpg",
                        5,
                        "리뷰 내용입니다.",
                        List.of("친절해요"),
                        List.of("https://cdn.example.com/reviews/10/1.jpg"),
                        1,
                        LocalDateTime.of(2026, 7, 9, 12, 0)
                )),
                null,
                false
        );
        given(reviewService.getRestaurantReviews(1L, "latest", null, 5))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/reviews", 1L)
                        .param("sort", "latest")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.restaurantId").value(1))
                .andExpect(jsonPath("$.data.content[0].reviewId").value(10))
                .andExpect(jsonPath("$.data.content[0].reviewerNickname").value("하루"))
                .andExpect(jsonPath("$.data.content[0].reviewerProfileImageUrl")
                        .value("https://cdn.example.com/users/1/profile.jpg"))
                .andExpect(jsonPath("$.data.content[0].writerNickname").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 식당_ID가_양수가_아니면_공통_검증_에러를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/reviews", 0L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(reviewService);
    }

    @Test
    void 페이지_크기가_최댓값을_넘으면_공통_검증_에러를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/reviews", 1L)
                        .param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(reviewService);
    }

    @Test
    void 리뷰_서비스_예외가_발생하면_공통_에러_응답을_반환한다() throws Exception {
        given(reviewService.getRestaurantReviews(1L, "invalid", null, 5))
                .willThrow(new BusinessException(ReviewErrorCode.UNSUPPORTED_SORT));

        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/reviews", 1L)
                        .param("sort", "invalid")
                        .param("size", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REVIEW-004"))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
