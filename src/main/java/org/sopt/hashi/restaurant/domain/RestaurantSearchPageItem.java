package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;
import java.util.List;

public record RestaurantSearchPageItem(Restaurant restaurant, List<BigDecimal> orderValues) {

    public RestaurantSearchPageItem {
        orderValues = List.copyOf(orderValues);
    }
}
