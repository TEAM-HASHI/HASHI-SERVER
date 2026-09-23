package org.sopt.hashi.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 리뷰 수정 요청. 수정 가능한 필드의 최종 상태를 모두 전달한다. */
public record UpdateReviewRequest(
        @Schema(description = "별점(1~5)", example = "4")
        @NotNull(message = "별점은 필수입니다")
        @Min(value = 1, message = "별점은 1점 이상이어야 합니다")
        @Max(value = 5, message = "별점은 5점 이하여야 합니다")
        Integer rating,

        @Schema(description = "리뷰 키워드 코드(1~3개, 중복 불가)",
                example = "[\"FOOD_IS_DELICIOUS\", \"GOOD_FOR_SOLO_DINING\"]")
        @NotNull(message = "리뷰 키워드는 필수입니다")
        @Size(min = 1, max = 3, message = "리뷰 키워드는 1개 이상 3개 이하로 선택해야 합니다")
        List<@NotBlank(message = "리뷰 키워드 코드는 비어 있을 수 없습니다") String> keywordCodes,

        @Schema(description = "리뷰 내용(10~1000자)",
                example = "음식이 맛있고 분위기도 좋아서 다시 방문하고 싶습니다.")
        @NotBlank(message = "리뷰 내용은 필수입니다")
        @Size(min = 10, max = 1000, message = "리뷰 내용은 10자 이상 1000자 이하여야 합니다")
        String content,

        @Schema(description = "수정 후 남길 리뷰 이미지 S3 key 전체 목록(최대 10개, 중복 불가, 빈 배열은 전체 삭제)",
                example = "[\"uploads/reviews/a1b2c3-1.jpg\"]")
        @NotNull(message = "리뷰 이미지 목록은 필수입니다")
        @Size(max = 10, message = "리뷰 이미지는 최대 10개까지 등록할 수 있습니다")
        List<@NotBlank(message = "이미지 파일 key는 비어 있을 수 없습니다")
                @Size(max = 500, message = "이미지 파일 key는 500자 이하여야 합니다") String> imageFileKeys
) {
}
