package org.sopt.hashi.restaurant.dto;

import org.sopt.hashi.media.MediaImage;

public record RestaurantMenuDetailResponse(
        Long menuId,
        String name,
        String description,
        String imageUrl,
        MediaImage detailImage,
        String currency,
        Long price,
        boolean main,
        long otherMenuCount
) {

    public RestaurantMenuDetailResponse(
            Long menuId,
            String name,
            String description,
            String imageUrl,
            String currency,
            Long price,
            boolean main,
            long otherMenuCount
    ) {
        this(
                menuId, name, description, imageUrl, null,
                currency, price, main, otherMenuCount);
    }
}
