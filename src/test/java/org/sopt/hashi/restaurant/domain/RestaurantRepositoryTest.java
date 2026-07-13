package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:restaurant-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
class RestaurantRepositoryTest {

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 랜덤_추천은_현재_식당과_삭제된_식당과_다른_큐레이션을_제외한다() {
        Restaurant current = saveRestaurant("현재 식당", RestaurantCurationType.TODAY_RESTAURANT);
        Restaurant recommendation = saveRestaurant("추천 식당", RestaurantCurationType.TODAY_RESTAURANT);
        saveRestaurant("하시픽 식당", RestaurantCurationType.HASHI_PICK);
        Restaurant deleted = saveRestaurant("삭제된 식당", RestaurantCurationType.TODAY_RESTAURANT);
        deleted.softDelete();
        entityManager.flush();
        entityManager.clear();

        var result = restaurantRepository.findRandomRestaurantIdByCurationTypeExcluding(
                RestaurantCurationType.TODAY_RESTAURANT.name(),
                current.getId()
        );

        assertThat(result).contains(recommendation.getId());
    }

    @Test
    void 최초_랜덤_추천은_제외할_식당_없이_조회한다() {
        Restaurant recommendation = saveRestaurant("추천 식당", RestaurantCurationType.TODAY_RESTAURANT);

        var result = restaurantRepository.findRandomRestaurantIdByCurationTypeExcluding(
                RestaurantCurationType.TODAY_RESTAURANT.name(),
                null
        );

        assertThat(result).contains(recommendation.getId());
    }

    @Test
    void 현재_식당을_제외한_추천_후보가_없으면_빈_결과를_반환한다() {
        Restaurant current = saveRestaurant("현재 식당", RestaurantCurationType.TODAY_RESTAURANT);

        var result = restaurantRepository.findRandomRestaurantIdByCurationTypeExcluding(
                RestaurantCurationType.TODAY_RESTAURANT.name(),
                current.getId()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 요청한_식당의_해당_요일_영업시간만_조회한다() {
        Restaurant first = createRestaurant("첫 번째 식당");
        first.replaceBusinessHours(List.of(
                createOpenBusinessHour(DayOfWeek.MONDAY),
                createOpenBusinessHour(DayOfWeek.TUESDAY)
        ));
        Restaurant second = createRestaurant("두 번째 식당");
        second.replaceBusinessHours(List.of(createOpenBusinessHour(DayOfWeek.MONDAY)));
        Restaurant deleted = createRestaurant("삭제된 식당");
        deleted.replaceBusinessHours(List.of(createOpenBusinessHour(DayOfWeek.MONDAY)));
        deleted.softDelete();
        restaurantRepository.saveAllAndFlush(List.of(first, second, deleted));

        List<RestaurantBusinessHour> businessHours =
                restaurantRepository.findBusinessHoursByRestaurantIdsAndDayOfWeek(
                        List.of(first.getId(), second.getId(), deleted.getId()),
                        DayOfWeek.MONDAY
                );

        assertThat(businessHours)
                .extracting(businessHour -> businessHour.getRestaurant().getId())
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(businessHours)
                .extracting(RestaurantBusinessHour::getDayOfWeek)
                .containsOnly(DayOfWeek.MONDAY);
    }

    @Test
    void 메뉴_상세와_현재_메뉴를_제외한_목록과_개수를_조회한다() {
        Restaurant restaurant = createRestaurant("메뉴 식당");
        restaurant.addMenu(createMenu("시오라멘"));
        restaurant.addMenu(createMenu("쇼유라멘"));
        restaurant.addMenu(createMenu("미소라멘"));
        restaurantRepository.saveAndFlush(restaurant);
        Long selectedMenuId = restaurant.getMenus().getFirst().getId();
        entityManager.clear();

        RestaurantMenu detail = restaurantRepository.findMenuByRestaurantIdAndMenuId(
                restaurant.getId(), selectedMenuId).orElseThrow();
        List<RestaurantMenu> otherMenus = restaurantRepository.findMenusByRestaurantId(
                restaurant.getId(), selectedMenuId, null, PageRequest.of(0, 10));
        long otherMenuCount = restaurantRepository.countOtherMenusByRestaurantId(
                restaurant.getId(), selectedMenuId);

        assertThat(detail.getName()).isEqualTo("시오라멘");
        assertThat(otherMenus)
                .extracting(RestaurantMenu::getId)
                .doesNotContain(selectedMenuId)
                .hasSize(2);
        assertThat(otherMenuCount).isEqualTo(2L);
    }

    @Test
    void 메뉴_상세는_다른_식당의_메뉴와_삭제된_식당의_메뉴를_노출하지_않는다() {
        Restaurant first = createRestaurant("첫 번째 식당");
        RestaurantMenu firstMenu = createMenu("첫 메뉴");
        first.addMenu(firstMenu);
        Restaurant deleted = createRestaurant("삭제된 식당");
        RestaurantMenu deletedMenu = createMenu("삭제된 메뉴");
        deleted.addMenu(deletedMenu);
        restaurantRepository.saveAllAndFlush(List.of(first, deleted));
        deleted.softDelete();
        restaurantRepository.flush();
        entityManager.clear();

        assertThat(restaurantRepository.findMenuByRestaurantIdAndMenuId(999L, firstMenu.getId())).isEmpty();
        assertThat(restaurantRepository.findMenuByRestaurantIdAndMenuId(deleted.getId(), deletedMenu.getId()))
                .isEmpty();
    }

    @Test
    void 메뉴_동기화는_기존_ID를_유지하고_누락된_메뉴를_삭제한다() {
        Restaurant restaurant = createRestaurant("메뉴 수정 식당");
        restaurant.addMenu(createMenu("유지 메뉴"));
        restaurant.addMenu(createMenu("삭제 메뉴"));
        restaurantRepository.saveAndFlush(restaurant);
        Long restaurantId = restaurant.getId();
        Long retainedMenuId = restaurant.getMenus().getFirst().getId();
        Long removedMenuId = restaurant.getMenus().getLast().getId();
        entityManager.clear();

        Restaurant managedRestaurant = restaurantRepository.findById(restaurantId).orElseThrow();
        RestaurantMenu retainedMenu = managedRestaurant.getMenus().stream()
                .filter(menu -> menu.getId().equals(retainedMenuId))
                .findFirst()
                .orElseThrow();
        retainedMenu.update("수정 메뉴", "수정 설명", null, PriceCurrency.JPY,
                BigDecimal.valueOf(1_500), true);
        managedRestaurant.removeMenusNotIn(Set.of(retainedMenuId));
        managedRestaurant.addMenu(createMenu("신규 메뉴"));
        restaurantRepository.flush();
        entityManager.clear();

        List<RestaurantMenu> menus = restaurantRepository.findMenusByRestaurantId(
                restaurantId, null, null, PageRequest.of(0, 10));

        assertThat(menus)
                .extracting(RestaurantMenu::getId)
                .contains(retainedMenuId)
                .doesNotContain(removedMenuId);
        assertThat(menus)
                .filteredOn(menu -> menu.getId().equals(retainedMenuId))
                .singleElement()
                .satisfies(menu -> {
                    assertThat(menu.getName()).isEqualTo("수정 메뉴");
                    assertThat(menu.getPriceAmount()).isEqualByComparingTo("1500");
                });
        assertThat(menus)
                .filteredOn(menu -> "신규 메뉴".equals(menu.getName()))
                .hasSize(1);
    }

    private Restaurant saveRestaurant(String name, RestaurantCurationType curationType) {
        Restaurant restaurant = createRestaurant(name);
        restaurant.replaceCurationTypes(List.of(curationType));
        entityManager.persistAndFlush(restaurant);
        return restaurant;
    }

    private Restaurant createRestaurant(String name) {
        return Restaurant.create(
                name,
                name,
                "식당 소개",
                "매장 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                RestaurantFoodCategory.SUSHI,
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000)
        );
    }

    private RestaurantBusinessHour createOpenBusinessHour(DayOfWeek dayOfWeek) {
        return RestaurantBusinessHour.create(
                dayOfWeek,
                LocalTime.of(10, 0),
                LocalTime.of(22, 0),
                null,
                null,
                false
        );
    }

    private RestaurantMenu createMenu(String name) {
        return RestaurantMenu.create(
                name,
                "메뉴 설명",
                "restaurant-menus/%s.jpg".formatted(name),
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                false
        );
    }
}
