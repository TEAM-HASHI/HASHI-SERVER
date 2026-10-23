package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.review.dto.ReviewContextResponse.ReviewKeywordOption;

/** 리뷰 수정 초기값. asset 사진은 reviewImageId로 유지하고 fileKey는 legacy 사진에만 반환한다. */
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
            int displayOrder,
            Long reviewImageId,
            UUID imageAssetId,
            MediaImage image
    ) {
    }
}
