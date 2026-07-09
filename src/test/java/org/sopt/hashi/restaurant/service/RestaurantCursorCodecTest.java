package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.test.util.ReflectionTestUtils;

class RestaurantCursorCodecTest {

    @Test
    void encode_and_decode_basic_cursor() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);

        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.BASIC);
        RestaurantCursor decoded = RestaurantCursorCodec.decode(encoded, RestaurantSort.BASIC);

        assertThat(decoded.sort()).isEqualTo(RestaurantSort.BASIC);
        assertThat(decoded.id()).isEqualTo(20L);
        assertThat(decoded.rating()).isNull();
        assertThat(decoded.popularityScore()).isNull();
    }

    @Test
    void encode_and_decode_popular_cursor() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);

        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.POPULAR);
        RestaurantCursor decoded = RestaurantCursorCodec.decode(encoded, RestaurantSort.POPULAR);

        assertThat(decoded.sort()).isEqualTo(RestaurantSort.POPULAR);
        assertThat(decoded.id()).isEqualTo(20L);
        assertThat(decoded.popularityScore()).isEqualTo(100L);
    }

    @Test
    void encode_and_decode_rating_cursor() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);

        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.RATING);
        RestaurantCursor decoded = RestaurantCursorCodec.decode(encoded, RestaurantSort.RATING);

        assertThat(decoded.sort()).isEqualTo(RestaurantSort.RATING);
        assertThat(decoded.id()).isEqualTo(20L);
        assertThat(decoded.rating()).isEqualTo(4.8);
    }

    @Test
    void decode_throws_common_invalid_input_when_cursor_sort_does_not_match_request_sort() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);
        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.RATING);

        assertThatThrownBy(() -> RestaurantCursorCodec.decode(encoded, RestaurantSort.POPULAR))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void decode_throws_common_invalid_input_when_cursor_is_malformed() {
        assertThatThrownBy(() -> RestaurantCursorCodec.decode("not-a-valid-cursor", RestaurantSort.BASIC))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    private Restaurant createRestaurant(Long id, double rating, long popularityScore) {
        Restaurant restaurant = Restaurant.create(
                "Himawari Sushi",
                "Himawari Sushi",
                "Sample restaurant",
                "Tokyo",
                "Tokyo",
                RestaurantGenre.SUSHI,
                "restaurants/%d/thumbnail.jpg".formatted(id),
                4_000L,
                "JPY",
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000)
        );
        ReflectionTestUtils.setField(restaurant, "id", id);
        ReflectionTestUtils.setField(restaurant, "rating", rating);
        ReflectionTestUtils.setField(restaurant, "popularityScore", popularityScore);
        return restaurant;
    }
}
