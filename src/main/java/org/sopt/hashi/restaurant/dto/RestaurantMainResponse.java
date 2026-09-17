package org.sopt.hashi.restaurant.dto;

import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantImageInfo;

public record RestaurantMainResponse(
        Long restaurantId,
        String name,
        String localName,
        BigDecimal rating,
        Long reviewCount,
        String summary,
        String foodCategory,
        String address,
        String thumbnailUrl,
        RestaurantImageInfo thumbnailImage,
        List<String> imageUrls,
        List<RestaurantImageInfo> heroImages,
        Long reservationFee
) {

    public RestaurantMainResponse(
            Long restaurantId,
            String name,
            String localName,
            BigDecimal rating,
            Long reviewCount,
            String summary,
            String foodCategory,
            String address,
            String thumbnailUrl,
            List<String> imageUrls,
            Long reservationFee
    ) {
        this(
                restaurantId, name, localName, rating, reviewCount, summary, foodCategory,
                address, thumbnailUrl, null, imageUrls, List.of(), reservationFee);
    }
}
