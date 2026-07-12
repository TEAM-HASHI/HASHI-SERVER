package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
