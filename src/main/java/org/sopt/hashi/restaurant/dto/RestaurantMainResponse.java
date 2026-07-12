package org.sopt.hashi.restaurant.dto;

import java.math.BigDecimal;
import java.util.List;

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
        List<String> imageUrls,
        Long reservationFee
) {
}
