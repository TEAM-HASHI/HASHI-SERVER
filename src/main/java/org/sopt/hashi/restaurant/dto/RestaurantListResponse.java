package org.sopt.hashi.restaurant.dto;

import java.math.BigDecimal;
import java.util.List;

public record RestaurantListResponse(
        List<RestaurantSummaryResponse> content,
        String nextCursor,
        boolean hasNext
) {

    public record RestaurantSummaryResponse(
            Long restaurantId,
            String name,
            BigDecimal rating,
            String thumbnailUrl,
            List<String> imageUrls,
            String area,
            String genre,
            String foodCategory,
            String summary,
            List<String> hashtags
    ) {
    }
}
