package org.sopt.hashi.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestaurantReviewResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 리뷰어_프로필_이미지가_없으면_해당_필드를_응답에서_생략한다() throws Exception {
        RestaurantReviewResponse.ReviewSummaryResponse review =
                new RestaurantReviewResponse.ReviewSummaryResponse(
                        10L,
                        "하루",
                        null,
                        5,
                        "리뷰 내용입니다.",
                        List.of("친절해요"),
                        List.of(),
                        0,
                        LocalDateTime.of(2026, 7, 14, 12, 0)
                );
        RestaurantReviewResponse response = new RestaurantReviewResponse(
                1L,
                5.0,
                1L,
                new RestaurantReviewResponse.RatingDistributionResponse(1L, 0L, 0L, 0L, 0L),
                List.of(review),
                null,
                false
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"reviewerNickname\":\"하루\"")
                .doesNotContain("reviewerProfileImageUrl", "writerNickname");
    }

    @Test
    void 마지막_페이지에서는_nextCursor를_응답에서_생략한다() throws Exception {
        RestaurantReviewResponse response = new RestaurantReviewResponse(
                1L,
                0.0,
                0L,
                new RestaurantReviewResponse.RatingDistributionResponse(0L, 0L, 0L, 0L, 0L),
                List.of(),
                null,
                false
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .doesNotContain("nextCursor")
                .contains("\"content\":[]", "\"hasNext\":false");
    }

    @Test
    void 다음_페이지가_있으면_nextCursor를_응답에_포함한다() throws Exception {
        RestaurantReviewResponse response = new RestaurantReviewResponse(
                1L,
                0.0,
                0L,
                new RestaurantReviewResponse.RatingDistributionResponse(0L, 0L, 0L, 0L, 0L),
                List.of(),
                10L,
                true
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"nextCursor\":10", "\"hasNext\":true");
    }
}
