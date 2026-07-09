package org.sopt.hashi.restaurant.dto;

import java.util.List;

public record RestaurantMainResponse(
        Long restaurantId,
        String name,
        String localName,
        Double rating,
        Long reviewCount,
        String description,
        String address,
        String thumbnailUrl,
        List<String> imageUrls,
        Long savedCount,
        Long reservationFee,
        String availableDate,
        String availableStartTime,
        String availableEndTime
) {
}
