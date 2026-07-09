package org.sopt.hashi.restaurant.dto;

import java.util.List;

public record RestaurantMenuListResponse(
        List<RestaurantMenuResponse> content,
        Long nextCursor,
        boolean hasNext
) {

    public record RestaurantMenuResponse(
            Long menuId,
            String name,
            String description,
            String imageUrl,
            String currency,
            Long price,
            boolean representative
    ) {
    }
}
