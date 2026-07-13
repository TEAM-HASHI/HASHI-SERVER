package org.sopt.hashi.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyReviewDetailResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 내_리뷰_상세는_작성자_닉네임을_리뷰어_필드로_응답한다() throws Exception {
        MyReviewDetailResponse response = new MyReviewDetailResponse(
                10L,
                1L,
                "하시 스시",
                "https://cdn.example.com/restaurants/1/thumbnail.jpg",
                LocalDateTime.of(2026, 7, 1, 12, 0),
                2,
                0,
                "하루",
                5,
                "리뷰 내용입니다.",
                List.of("친절해요"),
                List.of(),
                LocalDateTime.of(2026, 7, 2, 12, 0)
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"reviewerNickname\":\"하루\"")
                .doesNotContain("writerNickname");
    }
}
