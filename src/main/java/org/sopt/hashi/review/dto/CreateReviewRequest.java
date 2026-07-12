package org.sopt.hashi.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 리뷰 작성 요청. 작성자는 인증 컨텍스트에서 확인하므로 요청에 포함하지 않는다. */
public record CreateReviewRequest(
        @Schema(description = "방문 완료된 예약 ID", example = "1001")
        @NotNull(message = "예약 ID는 필수입니다")
        @Positive(message = "예약 ID는 양수여야 합니다")
        Long reservationId,

        @Schema(description = "별점(1~5)", example = "5")
        @NotNull(message = "별점은 필수입니다")
        @Min(value = 1, message = "별점은 1점 이상이어야 합니다")
        @Max(value = 5, message = "별점은 5점 이하여야 합니다")
        Integer rating,

        @Schema(description = "리뷰 키워드 코드(1~3개)", example = "[\"FOOD_IS_DELICIOUS\", \"STAFF_IS_KIND\"]")
        @NotNull(message = "리뷰 키워드는 필수입니다")
        @Size(min = 1, max = 3, message = "리뷰 키워드는 1개 이상 3개 이하로 선택해야 합니다")
        List<@NotBlank(message = "리뷰 키워드 코드는 비어 있을 수 없습니다") String> keywordCodes,

        @Schema(description = "리뷰 내용(10~1000자)", example = "분위기도 좋고 음식도 맛있었어요. 다음에 또 방문하고 싶습니다.")
        @NotBlank(message = "리뷰 내용은 필수입니다")
        @Size(min = 10, max = 1000, message = "리뷰 내용은 10자 이상 1000자 이하여야 합니다")
        String content,

        @Schema(description = "리뷰 이미지 S3 key 목록(선택, 최대 10개)", example = "[\"uploads/reviews/a1b2c3-1.jpg\"]")
        @Size(max = 10, message = "리뷰 이미지는 최대 10개까지 등록할 수 있습니다")
        List<@NotBlank(message = "이미지 파일 key는 비어 있을 수 없습니다")
                @Size(max = 500, message = "이미지 파일 key는 500자 이하여야 합니다") String> imageFileKeys
) {
}
