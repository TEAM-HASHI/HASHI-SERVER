package org.sopt.hashi.review.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RestaurantReviewResponse(
        Long restaurantId,
        double averageRating,
        long reviewCount,
        RatingDistributionResponse ratingDistribution,
        List<ReviewSummaryResponse> content,
        Long nextCursor,
        boolean hasNext
) {

    public record RatingDistributionResponse(
            long five,
            long four,
            long three,
            long two,
            long one
    ) {
    }

    public record ReviewSummaryResponse(
            Long reviewId,
            String writerNickname,
            int rating,
            String content,
            List<String> keywords,
            List<String> imageUrls,
            LocalDateTime createdAt
    ) {
    }
}
