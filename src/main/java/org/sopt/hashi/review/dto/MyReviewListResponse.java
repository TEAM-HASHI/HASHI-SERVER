package org.sopt.hashi.review.dto;

import java.time.LocalDateTime;
import java.util.List;

public record MyReviewListResponse(
        List<MyReviewSummaryResponse> content,
        Long nextCursor,
        boolean hasNext
) {

    public record MyReviewSummaryResponse(
            Long reviewId,
            Long restaurantId,
            String restaurantName,
            String restaurantThumbnailUrl,
            LocalDateTime visitedAt,
            int adultCount,
            int childCount,
            int rating,
            String content,
            List<String> keywords,
            List<String> imageUrls,
            LocalDateTime createdAt
    ) {
    }
}
