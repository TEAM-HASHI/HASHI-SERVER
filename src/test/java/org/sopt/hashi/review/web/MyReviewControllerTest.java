package org.sopt.hashi.review.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.dto.ReviewContextResponse.ReviewKeywordOption;
import org.sopt.hashi.review.dto.ReviewEditContextResponse;
import org.sopt.hashi.review.dto.ReviewEditContextResponse.ReviewEditImageResponse;
import org.sopt.hashi.review.dto.UpdateReviewRequest;
import org.sopt.hashi.review.service.MyReviewService;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = MyReviewController.class,
        excludeFilters = @Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {OnboardingJwtIssuer.class, JwtAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MyReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyReviewService myReviewService;

    @Test
    void 리뷰_수정_화면_초기값을_공통_성공_응답으로_반환한다() throws Exception {
        ReviewEditContextResponse response = new ReviewEditContextResponse(
                20L,
                100L,
                10L,
                "야키토리 무사시",
                "https://cdn.example.com/restaurants/10/thumbnail.jpg",
                null,
                LocalDateTime.of(2026, 6, 22, 17, 0),
                2,
                0,
                0,
                5,
                "직원분들이 친절하고 음식이 맛있었습니다.",
                List.of("FOOD_IS_DELICIOUS"),
                List.of(new ReviewKeywordOption("FOOD_IS_DELICIOUS", "음식이 맛있어요")),
                List.of(new ReviewEditImageResponse(
                        "uploads/reviews/20/1.jpg",
                        "https://cdn.example.com/reviews/20/1.jpg",
                        0))
        );
        given(myReviewService.getMyReviewEditContext(20L)).willReturn(response);

        mockMvc.perform(get("/api/v1/reviews/me/{reviewId}/edit-context", 20L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.reviewId").value(20))
                .andExpect(jsonPath("$.data.selectedKeywordCodes[0]")
                        .value("FOOD_IS_DELICIOUS"))
                .andExpect(jsonPath("$.data.images[0].fileKey")
                        .value("uploads/reviews/20/1.jpg"))
                .andExpect(jsonPath("$.data.images[0].displayOrder").value(0));
    }

    @Test
    void 타인이나_삭제된_리뷰_수정_화면은_기존_404_계약을_반환한다() throws Exception {
        willThrow(new BusinessException(ReviewErrorCode.NOT_FOUND))
                .given(myReviewService)
                .getMyReviewEditContext(20L);

        mockMvc.perform(get("/api/v1/reviews/me/{reviewId}/edit-context", 20L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REVIEW-006"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 리뷰_수정_요청을_서비스에_위임하고_공통_성공_응답을_반환한다() throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/{reviewId}", 20L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rating": 4,
                                  "keywordCodes": ["FOOD_IS_DELICIOUS", "GOOD_VALUE"],
                                  "content": "음식이 맛있고 분위기도 좋아서 다시 방문하고 싶습니다.",
                                  "imageFileKeys": ["uploads/reviews/new-image.jpg"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(myReviewService).updateMyReview(20L, new UpdateReviewRequest(
                4,
                List.of("FOOD_IS_DELICIOUS", "GOOD_VALUE"),
                "음식이 맛있고 분위기도 좋아서 다시 방문하고 싶습니다.",
                List.of("uploads/reviews/new-image.jpg")
        ));
    }

    @Test
    void 이미지_최종_목록을_누락하면_수정_요청을_거부한다() throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/{reviewId}", 20L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rating": 4,
                                  "keywordCodes": ["FOOD_IS_DELICIOUS"],
                                  "content": "음식이 맛있고 분위기도 좋아서 다시 방문하고 싶습니다."
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"));

        verifyNoInteractions(myReviewService);
    }

    @Test
    void 타인이나_삭제된_리뷰_수정은_기존_404_계약을_반환한다() throws Exception {
        willThrow(new BusinessException(ReviewErrorCode.NOT_FOUND))
                .given(myReviewService)
                .updateMyReview(any(), any(UpdateReviewRequest.class));

        mockMvc.perform(patch("/api/v1/reviews/{reviewId}", 20L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rating": 4,
                                  "keywordCodes": ["FOOD_IS_DELICIOUS"],
                                  "content": "음식이 맛있고 분위기도 좋아서 다시 방문하고 싶습니다.",
                                  "imageFileKeys": []
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REVIEW-006"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 리뷰_삭제_외부_계약은_그대로_유지한다() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/{reviewId}", 20L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(myReviewService).deleteMyReview(20L);
    }
}
