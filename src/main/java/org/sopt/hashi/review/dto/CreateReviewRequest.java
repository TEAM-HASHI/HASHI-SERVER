package org.sopt.hashi.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 리뷰 작성 요청. 작성자는 인증 컨텍스트에서 확인하므로 요청에 포함하지 않는다. */
public record CreateReviewRequest(
        @NotNull(message = "예약 ID는 필수입니다")
        @Positive(message = "예약 ID는 양수여야 합니다")
        Long reservationId,

        @NotNull(message = "별점은 필수입니다")
        @Min(value = 1, message = "별점은 1점 이상이어야 합니다")
        @Max(value = 5, message = "별점은 5점 이하여야 합니다")
        Integer rating,

        @NotNull(message = "리뷰 키워드는 필수입니다")
        @Size(min = 1, max = 3, message = "리뷰 키워드는 1개 이상 3개 이하로 선택해야 합니다")
        List<@NotBlank(message = "리뷰 키워드 코드는 비어 있을 수 없습니다") String> keywordCodes,

        @NotBlank(message = "리뷰 내용은 필수입니다")
        @Size(min = 10, max = 1000, message = "리뷰 내용은 10자 이상 1000자 이하여야 합니다")
        String content,

        @Size(max = 10, message = "리뷰 이미지는 최대 10개까지 등록할 수 있습니다")
        List<@NotBlank(message = "이미지 파일 key는 비어 있을 수 없습니다")
                @Size(max = 500, message = "이미지 파일 key는 500자 이하여야 합니다") String> imageFileKeys
) {
}
