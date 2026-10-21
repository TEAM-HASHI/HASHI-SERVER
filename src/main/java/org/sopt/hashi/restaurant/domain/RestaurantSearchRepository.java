package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;
import java.util.List;

public interface RestaurantSearchRepository {
    List<RestaurantSearchPageItem> search(RestaurantSearchQuery input, RestaurantGenre genre,
            RestaurantCurationType curationType, RestaurantSort sort, List<BigDecimal> after, int limit);
}
