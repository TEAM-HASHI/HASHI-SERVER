package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.AdminRestaurantInfo.AdminRestaurantMenuInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantFoodCategory;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({RestaurantService.class, RestaurantPortImpl.class, TimeConfig.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:restaurant-service-integration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RestaurantServiceIntegrationTest {

    @Autowired
    private RestaurantPort restaurantPort;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private RestaurantService restaurantService;

    @MockitoBean
    private FileStorage fileStorage;

    @Test
    void 식당_목록은_음식_분류로_필터링하지_않고_응답에는_음식_분류를_유지한다() {
        restaurantRepository.saveAllAndFlush(List.of(
                createRestaurant("음식 분류 초밥 식당", RestaurantFoodCategory.SUSHI),
                createRestaurant("음식 분류 면류 식당", RestaurantFoodCategory.NOODLE)
        ));

        RestaurantListResponse response = restaurantService.getRestaurants(
                null,
                "sushi",
                null,
                null,
                null,
                50
        );

        List<RestaurantListResponse.RestaurantSummaryResponse> testRestaurants = response.content().stream()
                .filter(restaurant -> restaurant.name().startsWith("음식 분류"))
                .toList();

        assertThat(testRestaurants)
                .extracting(RestaurantListResponse.RestaurantSummaryResponse::name)
                .containsExactlyInAnyOrder("음식 분류 초밥 식당", "음식 분류 면류 식당");
        assertThat(testRestaurants)
                .extracting(RestaurantListResponse.RestaurantSummaryResponse::foodCategory)
                .containsExactlyInAnyOrder("초밥", "면류");
    }

    @Test
    void 관리자_메뉴_수정_응답은_유지된_ID와_신규_ID를_DB_반영_후_반환한다() {
        Restaurant restaurant = createRestaurant();
        restaurant.addMenu(createMenu("유지 메뉴"));
        restaurant.addMenu(createMenu("삭제 메뉴"));
        restaurantRepository.saveAndFlush(restaurant);
        Long restaurantId = restaurant.getId();
        Long retainedMenuId = restaurant.getMenus().getFirst().getId();
        Long removedMenuId = restaurant.getMenus().getLast().getId();

        AdminRestaurantInfo response = restaurantPort.updateByAdmin(
                restaurantId,
                updateMenusCommand(List.of(
                        new MenuCommand(retainedMenuId, "수정 메뉴", "수정 설명", null,
                                "JPY", BigDecimal.valueOf(1_500), true),
                        new MenuCommand(null, "신규 메뉴", "신규 설명", null,
                                "JPY", BigDecimal.valueOf(900), false)
                ))
        );

        AdminRestaurantMenuInfo retainedMenu = response.menus().stream()
                .filter(menu -> "수정 메뉴".equals(menu.name()))
                .findFirst()
                .orElseThrow();
        AdminRestaurantMenuInfo newMenu = response.menus().stream()
                .filter(menu -> "신규 메뉴".equals(menu.name()))
                .findFirst()
                .orElseThrow();

        assertThat(retainedMenu.menuId()).isEqualTo(retainedMenuId);
        assertThat(newMenu.menuId()).isNotNull();
        assertThat(response.menus())
                .extracting(AdminRestaurantMenuInfo::menuId)
                .doesNotContain(removedMenuId);

        List<RestaurantMenu> persistedMenus = restaurantRepository.findMenusByRestaurantId(
                restaurantId, null, null, PageRequest.of(0, 10));
        assertThat(persistedMenus)
                .extracting(RestaurantMenu::getId)
                .containsExactlyInAnyOrder(retainedMenuId, newMenu.menuId())
                .doesNotContain(removedMenuId);
        assertThat(restaurantRepository.findMenuByRestaurantIdAndMenuId(restaurantId, newMenu.menuId()))
                .isPresent();
    }

    private AdminRestaurantCommand updateMenusCommand(List<MenuCommand> menus) {
        return new AdminRestaurantCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                menus,
                null,
                null,
                null
        );
    }

    private Restaurant createRestaurant() {
        return createRestaurant("메뉴 수정 식당", RestaurantFoodCategory.SUSHI);
    }

    private Restaurant createRestaurant(String name, RestaurantFoodCategory foodCategory) {
        return Restaurant.create(
                name,
                "Menu Update Restaurant",
                "식당 소개",
                "식당 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                foodCategory,
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000)
        );
    }

    private RestaurantMenu createMenu(String name) {
        return RestaurantMenu.create(
                name,
                "메뉴 설명",
                null,
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                false
        );
    }
}
