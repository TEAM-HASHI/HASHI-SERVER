package org.sopt.hashi.restaurant;

/** 식당 상세 요약을 직접 조회했을 때만 발행한다. */
public record RestaurantDetailViewedEvent(Long restaurantId) {
}
