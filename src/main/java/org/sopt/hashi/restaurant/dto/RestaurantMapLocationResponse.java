package org.sopt.hashi.restaurant.dto;

import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;

public record RestaurantMapLocationResponse(Long restaurantId, LocationInfo location) {
}
