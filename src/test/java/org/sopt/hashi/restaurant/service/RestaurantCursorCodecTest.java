package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.test.util.ReflectionTestUtils;

class RestaurantCursorCodecTest {

    @Test
    void 기본순_커서를_인코딩하고_디코딩한다() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);

        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.BASIC);
        RestaurantCursor decoded = RestaurantCursorCodec.decode(encoded, RestaurantSort.BASIC);

        assertThat(decoded.sort()).isEqualTo(RestaurantSort.BASIC);
        assertThat(decoded.id()).isEqualTo(20L);
        assertThat(decoded.rating()).isNull();
        assertThat(decoded.reviewCount()).isNull();
    }

    @Test
    void 인기순_커서를_인코딩하고_디코딩한다() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);

        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.POPULAR);
        RestaurantCursor decoded = RestaurantCursorCodec.decode(encoded, RestaurantSort.POPULAR);

        assertThat(decoded.sort()).isEqualTo(RestaurantSort.POPULAR);
        assertThat(decoded.id()).isEqualTo(20L);
        assertThat(decoded.reviewCount()).isEqualTo(100L);
        assertThat(decoded.rating()).isEqualByComparingTo("4.8");
    }

    @Test
    void 별점순_커서를_인코딩하고_디코딩한다() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);

        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.RATING);
        RestaurantCursor decoded = RestaurantCursorCodec.decode(encoded, RestaurantSort.RATING);

        assertThat(decoded.sort()).isEqualTo(RestaurantSort.RATING);
        assertThat(decoded.id()).isEqualTo(20L);
        assertThat(decoded.rating()).isEqualByComparingTo("4.8");
    }

    @Test
    void 요청_정렬과_커서_정렬이_다르면_예외가_발생한다() {
        Restaurant restaurant = createRestaurant(20L, 4.8, 100L);
        String encoded = RestaurantCursorCodec.encode(restaurant, RestaurantSort.RATING);

        assertThatThrownBy(() -> RestaurantCursorCodec.decode(encoded, RestaurantSort.POPULAR))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 커서_형식이_잘못되면_예외가_발생한다() {
        assertThatThrownBy(() -> RestaurantCursorCodec.decode("not-a-valid-cursor", RestaurantSort.BASIC))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    private Restaurant createRestaurant(Long id, double rating, long reviewCount) {
        Restaurant restaurant = Restaurant.create(
                "Himawari Sushi",
                "Himawari Sushi",
                "Sample restaurant",
                "Detailed restaurant description",
                "Tokyo",
                "Tokyo",
                RestaurantGenre.SUSHI,
                "초밥",
                PriceCurrency.JPY,
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000)
        );
        restaurant.replaceImages(List.of(
                RestaurantImage.create("restaurants/%d/thumbnail.jpg".formatted(id), 1)));
        ReflectionTestUtils.setField(restaurant, "id", id);
        ReflectionTestUtils.setField(restaurant, "rating", BigDecimal.valueOf(rating));
        ReflectionTestUtils.setField(restaurant, "reviewCount", reviewCount);
        return restaurant;
    }
}
