package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RestaurantPlaceTypeTest {

    @Test
    void 소문자_값으로_음식점_분류를_찾는다() {
        assertThat(RestaurantPlaceType.from("restaurant")).contains(RestaurantPlaceType.RESTAURANT);
        assertThat(RestaurantPlaceType.from("cafe")).contains(RestaurantPlaceType.CAFE);
        assertThat(RestaurantPlaceType.from("bar")).contains(RestaurantPlaceType.BAR);
    }

    @Test
    void 지원하지_않거나_대소문자가_다른_값은_찾지_못한다() {
        assertThat(RestaurantPlaceType.from("pub")).isEmpty();
        assertThat(RestaurantPlaceType.from("CAFE")).isEmpty();
        assertThat(RestaurantPlaceType.from(null)).isEmpty();
    }
}
