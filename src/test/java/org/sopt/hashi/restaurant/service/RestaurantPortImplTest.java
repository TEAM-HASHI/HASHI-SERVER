package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RestaurantPortImplTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private FileStorage fileStorage;

    private RestaurantPortImpl restaurantPort;

    @BeforeEach
    void setUp() {
        restaurantPort = new RestaurantPortImpl(restaurantRepository, fileStorage);
    }

    @Test
    void existsById_returns_false_when_restaurant_id_is_null() {
        assertThat(restaurantPort.existsById(null)).isFalse();
    }

    @Test
    void existsById_delegates_to_repository() {
        given(restaurantRepository.existsById(1L)).willReturn(true);

        assertThat(restaurantPort.existsById(1L)).isTrue();

        verify(restaurantRepository).existsById(1L);
    }

    @Test
    void findSummaryById_returns_restaurant_summary() {
        Restaurant restaurant = createRestaurant(1L);
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));
        given(fileStorage.resolveFileUrl("restaurants/1/thumbnail.jpg"))
                .willReturn("https://cdn.example.com/restaurants/1/thumbnail.jpg");

        Optional<RestaurantInfo> result = restaurantPort.findSummaryById(1L);

        assertThat(result).contains(new RestaurantInfo(
                1L,
                "히마와리 스시",
                "도쿄도 신주쿠구",
                "https://cdn.example.com/restaurants/1/thumbnail.jpg"
        ));
    }

    @Test
    void findSummaries_keeps_requested_order_and_skips_missing_restaurants() {
        Restaurant first = createRestaurant(1L);
        Restaurant second = createRestaurant(2L);
        given(restaurantRepository.findAllById(List.of(2L, 1L)))
                .willReturn(List.of(first, second));
        given(fileStorage.resolveFileUrl("restaurants/1/thumbnail.jpg"))
                .willReturn("https://cdn.example.com/restaurants/1/thumbnail.jpg");
        given(fileStorage.resolveFileUrl("restaurants/2/thumbnail.jpg"))
                .willReturn("https://cdn.example.com/restaurants/2/thumbnail.jpg");

        List<RestaurantInfo> result = restaurantPort.findSummaries(Arrays.asList(2L, 1L, 1L, null));

        assertThat(result)
                .extracting(RestaurantInfo::id)
                .containsExactly(2L, 1L);
    }

    @Test
    void findDetailById_returns_restaurant_detail() {
        Restaurant restaurant = createRestaurant(1L);
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));
        given(fileStorage.resolveFileUrl("restaurants/1/thumbnail.jpg"))
                .willReturn("https://cdn.example.com/restaurants/1/thumbnail.jpg");

        Optional<RestaurantDetailInfo> result = restaurantPort.findDetailById(1L);

        assertThat(result).contains(new RestaurantDetailInfo(
                1L,
                "히마와리 스시",
                "Himawari Sushi",
                "도쿄도 신주쿠구",
                "https://cdn.example.com/restaurants/1/thumbnail.jpg"
        ));
    }

    private Restaurant createRestaurant(Long id) {
        Restaurant restaurant = Restaurant.create(
                "히마와리 스시",
                "Himawari Sushi",
                "식당 소개",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                "restaurants/%d/thumbnail.jpg".formatted(id),
                4_000L,
                "JPY",
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000)
        );
        ReflectionTestUtils.setField(restaurant, "id", id);
        return restaurant;
    }
}
