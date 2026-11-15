package org.sopt.hashi.restaurant;

import java.util.List;

/** 식당 ID 오름차순 관리자 offset 페이지. */
public record RestaurantLocationReviewPage(
        List<RestaurantLocationReviewInfo> restaurants,
        int page,
        int size,
        long totalCount,
        int totalPages) {

    public RestaurantLocationReviewPage {
        restaurants = List.copyOf(restaurants);
    }
}
