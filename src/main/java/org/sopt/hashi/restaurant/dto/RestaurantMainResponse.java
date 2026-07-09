package org.sopt.hashi.restaurant.dto;

public record RestaurantMainResponse(
        Long restaurantId,
        String name,
        String localName,
        Double rating,
        Long reviewCount,
        String description,
        String address,
        String thumbnailUrl,
        Long savedCount,
        Long reservationFee,
        String availableDate,
        String availableStartTime,
        String availableEndTime
) {
}
