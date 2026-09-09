package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;

@JsonInclude(JsonInclude.Include.NON_NULL)
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReviewSummaryResponse(
            Long reviewId,
            String reviewerNickname,
            String reviewerProfileImageUrl,
            MediaImage reviewerProfileImage,
            int rating,
            String content,
            List<String> keywords,
            List<String> previewImageUrls,
            int imageCount,
            LocalDateTime createdAt
    ) {

        public ReviewSummaryResponse(
                Long reviewId,
                String reviewerNickname,
                String reviewerProfileImageUrl,
                int rating,
                String content,
                List<String> keywords,
                List<String> previewImageUrls,
                int imageCount,
                LocalDateTime createdAt
        ) {
            this(
                    reviewId, reviewerNickname, reviewerProfileImageUrl, null,
                    rating, content, keywords, previewImageUrls, imageCount, createdAt);
        }
    }
}
