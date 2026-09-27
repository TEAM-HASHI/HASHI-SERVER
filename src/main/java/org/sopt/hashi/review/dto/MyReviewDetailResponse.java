package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MyReviewDetailResponse(
        Long reviewId,
        Long restaurantId,
        String restaurantName,
        String restaurantThumbnailUrl,
        MediaImage restaurantThumbnailImage,
        LocalDateTime visitedAt,
        Integer adultCount,
        Integer childCount,
        String reviewerNickname,
        int rating,
        String content,
        List<String> keywords,
        List<String> imageUrls,
        LocalDateTime createdAt
) {
}
