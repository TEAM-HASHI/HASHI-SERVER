package org.sopt.hashi.review.dto;

import java.time.LocalDateTime;
import java.util.List;

public record MyReviewDetailResponse(
        Long reviewId,
        Long restaurantId,
        String restaurantName,
        String restaurantThumbnailUrl,
        LocalDateTime visitedAt,
        int adultCount,
        int childCount,
        String writerNickname,
        int rating,
        String content,
        List<String> keywords,
        List<String> imageUrls,
        LocalDateTime createdAt
) {
}
