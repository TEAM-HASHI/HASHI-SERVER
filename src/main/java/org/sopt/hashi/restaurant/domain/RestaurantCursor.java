package org.sopt.hashi.restaurant.domain;

public record RestaurantCursor(
        RestaurantSort sort,
        Double rating,
        Long popularityScore,
        Long id
) {
}
