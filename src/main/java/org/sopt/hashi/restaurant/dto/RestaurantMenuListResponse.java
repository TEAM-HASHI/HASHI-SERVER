package org.sopt.hashi.restaurant.dto;

import java.util.List;
import org.sopt.hashi.media.MediaImage;

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
            MediaImage listImage,
            String currency,
            Long price,
            boolean main
    ) {

        public RestaurantMenuResponse(
                Long menuId,
                String name,
                String description,
                String imageUrl,
                String currency,
                Long price,
                boolean main
        ) {
            this(menuId, name, description, imageUrl, null, currency, price, main);
        }
    }
}
