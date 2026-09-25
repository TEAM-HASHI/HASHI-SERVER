package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.review.dto.ReviewContextResponse.ReviewKeywordOption;

/** 리뷰 수정 화면 초기값. 저장에 필요한 keyword code와 이미지 fileKey를 함께 반환한다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewEditContextResponse(
        Long reviewId,
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        String restaurantThumbnailUrl,
        MediaImage restaurantThumbnailImage,
        LocalDateTime visitedAt,
        int adultCount,
        int teenCount,
        int childCount,
        int rating,
        String content,
        List<String> selectedKeywordCodes,
        List<ReviewKeywordOption> reviewKeywordOptions,
        List<ReviewEditImageResponse> images
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReviewEditImageResponse(
            String fileKey,
            String imageUrl,
            int displayOrder
    ) {
    }
}
