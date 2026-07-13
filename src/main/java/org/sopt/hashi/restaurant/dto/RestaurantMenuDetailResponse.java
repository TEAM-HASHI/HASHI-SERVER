package org.sopt.hashi.restaurant.dto;

public record RestaurantMenuDetailResponse(
        Long menuId,
        String name,
        String description,
        String imageUrl,
        String currency,
        Long price,
        boolean main,
        long otherMenuCount
) {
}
