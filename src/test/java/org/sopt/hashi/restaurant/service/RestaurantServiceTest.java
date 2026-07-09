package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantBusinessHour;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse;
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

    @Test
    void getRestaurantSummary_returns_main_information() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        ReflectionTestUtils.setField(restaurant, "reviewCount", 256L);
        ReflectionTestUtils.setField(restaurant, "savedCount", 35L);
        ReflectionTestUtils.setField(restaurant, "availableDate", LocalDate.of(2026, 7, 19));
        ReflectionTestUtils.setField(restaurant, "availableStartTime", LocalTime.of(10, 0));
        ReflectionTestUtils.setField(restaurant, "availableEndTime", LocalTime.of(22, 0));
        given(restaurantRepository.findByIdAndActiveTrue(1L)).willReturn(Optional.of(restaurant));
        given(fileStorage.resolveFileUrl("restaurants/1/thumbnail.jpg"))
                .willReturn("https://cdn.example.com/restaurants/1/thumbnail.jpg");

        var response = restaurantService.getRestaurantSummary(1L);

        assertThat(response.restaurantId()).isEqualTo(1L);
        assertThat(response.rating()).isEqualTo(4.8);
        assertThat(response.reviewCount()).isEqualTo(256L);
        assertThat(response.thumbnailUrl()).isEqualTo("https://cdn.example.com/restaurants/1/thumbnail.jpg");
        assertThat(response.availableDate()).isEqualTo("2026-07-19");
        assertThat(response.availableStartTime()).isEqualTo("10:00");
        assertThat(response.availableEndTime()).isEqualTo("22:00");
    }

    @Test
    void getStoreInformation_returns_business_hours_and_price_range() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceBusinessHours(List.of(
                RestaurantBusinessHour.create(DayOfWeek.TUESDAY, LocalTime.of(11, 0),
                        LocalTime.of(21, 0), LocalTime.of(20, 30), false),
                RestaurantBusinessHour.create(DayOfWeek.MONDAY, LocalTime.of(10, 0),
                        LocalTime.of(22, 0), LocalTime.of(21, 30), false)
        ));
        given(restaurantRepository.findActiveByIdWithBusinessHours(1L)).willReturn(Optional.of(restaurant));

        RestaurantStoreInformationResponse response = restaurantService.getStoreInformation(1L);

        assertThat(response.restaurantId()).isEqualTo(1L);
        assertThat(response.businessHours()).hasSize(2);
        assertThat(response.businessHours().getFirst().dayOfWeek()).isEqualTo("MONDAY");
        assertThat(response.businessHours().getFirst().openTime()).isEqualTo("10:00");
        assertThat(response.businessHours().getFirst().lastOrderTime()).isEqualTo("21:30");
        assertThat(response.priceRange().currency()).isEqualTo("JPY");
        assertThat(response.priceRange().minPrice()).isEqualTo(1000L);
        assertThat(response.priceRange().maxPrice()).isEqualTo(3000L);
    }

    @Test
    void getRestaurantMenus_returns_cursor_page() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);
        List<RestaurantMenu> menus = List.of(
                createMenu(30L, "Omakase Sushi", true),
                createMenu(20L, "Salmon Nigiri", false),
                createMenu(10L, "Tuna Roll", false)
        );
        given(restaurantRepository.existsByIdAndActiveTrue(1L)).willReturn(true);
        given(restaurantRepository.findMenusByRestaurantId(
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.<Long>isNull(),
                any(Pageable.class)
        )).willReturn(menus);
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantMenuListResponse response = restaurantService.getRestaurantMenus(1L, null, 2);

        assertThat(response.content()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(20L);
        assertThat(response.content().getFirst().menuId()).isEqualTo(30L);
        assertThat(response.content().getFirst().imageUrl())
                .isEqualTo("https://cdn.example.com/restaurant-menus/30.jpg");
    }

    @Test
    void getRestaurantDetail_throws_not_found_when_restaurant_is_inactive_or_missing() {
        RestaurantService restaurantService = new RestaurantService(restaurantRepository, fileStorage);
        given(restaurantRepository.findByIdAndActiveTrue(404L)).willReturn(Optional.empty());
        given(restaurantRepository.existsByIdAndActiveTrue(404L)).willReturn(false);
        given(restaurantRepository.findActiveByIdWithBusinessHours(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.getRestaurantSummary(404L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> restaurantService.getStoreInformation(404L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> restaurantService.getRestaurantMenus(404L, null, 10))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.NOT_FOUND));
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

    private RestaurantMenu createMenu(Long id, String name, boolean representative) {
        RestaurantMenu menu = RestaurantMenu.create(
                name,
                "menu description",
                "restaurant-menus/%d.jpg".formatted(id),
                "JPY",
                BigDecimal.valueOf(1200),
                representative
        );
        ReflectionTestUtils.setField(menu, "id", id);
        return menu;
    }
}
