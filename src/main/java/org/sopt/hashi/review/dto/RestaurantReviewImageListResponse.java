package org.sopt.hashi.review.dto;

import java.util.List;

public record RestaurantReviewImageListResponse(
        List<RestaurantReviewImageResponse> content,
        Long nextCursor,
        boolean hasNext
) {

    public record RestaurantReviewImageResponse(
            Long imageId,
            Long reviewId,
            String imageUrl
    ) {
    }
}
