package org.sopt.hashi.user.collection.dto;

import java.util.List;

public record MyRestaurantSavesResponse(List<MyRestaurantSave> restaurants) {
    public record MyRestaurantSave(Long restaurantId, boolean saved) { }
}
