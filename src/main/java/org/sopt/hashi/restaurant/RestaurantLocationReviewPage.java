package org.sopt.hashi.restaurant;

import java.util.List;

/** 식당 ID 오름차순 커서 페이지. hasNext가 false면 nextCursor는 null이다. */
public record RestaurantLocationReviewPage(
        List<RestaurantLocationReviewInfo> restaurants,
        Long nextCursor,
        boolean hasNext) {

    public RestaurantLocationReviewPage {
        restaurants = List.copyOf(restaurants);
    }
}
