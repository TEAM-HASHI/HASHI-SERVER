package org.sopt.hashi.restaurant;

/** 공개 상세 GET의 식당 조회가 성공한 뒤 요청 스레드에서 전달한다. */
public record RestaurantViewedEvent(Long restaurantId) {}
