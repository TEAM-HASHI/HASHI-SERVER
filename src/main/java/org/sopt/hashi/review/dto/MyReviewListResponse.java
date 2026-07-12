package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

public record MyReviewListResponse(
        List<MyReviewSummaryResponse> content,
        Long nextCursor,
        boolean hasNext
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyReviewSummaryResponse(
            Long reviewId,
            Long restaurantId,
            String restaurantName,
            String restaurantThumbnailUrl,
            LocalDateTime visitedAt,
            Integer adultCount,
            Integer childCount,
            int rating,
            String content,
            List<String> keywords,
            LocalDateTime createdAt
    ) {
    }
}
