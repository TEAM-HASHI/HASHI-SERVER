package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private FileStorage fileStorage;

    @Test
    void 기본값으로_식당_목록을_조회하고_다음_커서를_반환한다() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);
        List<Restaurant> restaurants = LongStream.rangeClosed(1, 11)
                .mapToObj(id -> createRestaurant(id, 4.0, id * 10))
                .toList();
        givenRestaurants(restaurants);
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantListResponse response = restaurantService.getRestaurants(
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(response.content()).hasSize(10);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        assertThat(response.content().getFirst().thumbnailUrl())
                .isEqualTo("https://cdn.example.com/restaurants/1/thumbnail.jpg");

        RestaurantCursor decodedCursor = RestaurantCursorCodec.decode(response.nextCursor(), RestaurantSort.BASIC);
        assertThat(decodedCursor.id()).isEqualTo(response.content().getLast().restaurantId());

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(11);
        assertSortOrder(pageable, "id");
    }

    @Test
    void 인기순_커서로_식당_목록을_조회한다() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);
        Restaurant cursorBase = createRestaurant(20L, 4.8, 100L);
        String cursor = RestaurantCursorCodec.encode(cursorBase, RestaurantSort.POPULAR);
        givenRestaurants(List.of(createRestaurant(19L, 4.7, 90L)));
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantListResponse response = restaurantService.getRestaurants(
                "스시",
                "sushi",
                "popular",
                "sns-hot",
                cursor,
                20
        );

        assertThat(response.content()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageSize()).isEqualTo(21);
        assertSortOrder(pageable, "popularityScore");
        assertSortOrder(pageable, "id");
    }

    @Test
    void 별점순_조회에서_페이지_크기를_최대값으로_제한한다() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);
        Restaurant cursorBase = createRestaurant(20L, 4.8, 100L);
        String cursor = RestaurantCursorCodec.encode(cursorBase, RestaurantSort.RATING);
        givenRestaurants(List.of());

        RestaurantListResponse response = restaurantService.getRestaurants(
                null,
                "all",
                "rating",
                "all",
                cursor,
                100
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageSize()).isEqualTo(51);
        assertSortOrder(pageable, "rating");
        assertSortOrder(pageable, "id");
    }

    @Test
    void 지원하지_않는_조회_조건이면_예외가_발생한다() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                "invalid-genre",
                null,
                null,
                null,
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_GENRE));

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                null,
                "invalid-sort",
                null,
                null,
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_SORT));

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                null,
                null,
                "invalid-type",
                null,
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_LIST_TYPE));

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                null,
                "popular",
                null,
                "not-a-valid-cursor",
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verifyNoInteractions(restaurantRepository);
    }

    private void givenRestaurants(List<Restaurant> restaurants) {
        given(restaurantRepository.findAll(
                ArgumentMatchers.<Specification<Restaurant>>any(),
                any(Pageable.class)
        )).willReturn(new PageImpl<>(restaurants));
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(restaurantRepository).findAll(
                ArgumentMatchers.<Specification<Restaurant>>any(),
                pageableCaptor.capture()
        );
        return pageableCaptor.getValue();
    }

    private void assertSortOrder(Pageable pageable, String property) {
        Sort.Order order = pageable.getSort().getOrderFor(property);
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    private Restaurant createRestaurant(Long id, double rating, long popularityScore) {
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
        restaurant.replaceTags(List.of("예약 가능", "스시"));
        ReflectionTestUtils.setField(restaurant, "id", id);
        ReflectionTestUtils.setField(restaurant, "rating", rating);
        ReflectionTestUtils.setField(restaurant, "popularityScore", popularityScore);
        return restaurant;
    }
}
