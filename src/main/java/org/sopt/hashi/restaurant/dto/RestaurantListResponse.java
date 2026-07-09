package org.sopt.hashi.restaurant.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record RestaurantListResponse(
        List<RestaurantSummaryResponse> content,
        String nextCursor,
        boolean hasNext
) {

    public record RestaurantSummaryResponse(
            Long restaurantId,
            String name,
            Double rating,
            String thumbnailUrl,
            String area,
            String genre,
            String description,
            List<String> tags,
            LocalDate availableDate,
            LocalTime availableStartTime,
            LocalTime availableEndTime
    ) {
    }
}
