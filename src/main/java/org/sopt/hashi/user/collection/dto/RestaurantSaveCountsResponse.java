package org.sopt.hashi.user.collection.dto;

import java.util.List;

/** 현재 존재하는 식당의 저장 사용자 수. 같은 사용자의 여러 컬렉션은 한 번만 센다. */
public record RestaurantSaveCountsResponse(List<RestaurantSaveCount> restaurants) {
    public record RestaurantSaveCount(Long restaurantId, long saveCount) { }
}
