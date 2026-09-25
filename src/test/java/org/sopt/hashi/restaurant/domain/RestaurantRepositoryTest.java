package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 랜덤_추천은_현재_식당과_삭제된_식당을_제외한_전체_식당에서_뽑는다() {
        Restaurant current = saveRestaurant("현재 식당", RestaurantCurationType.HASHI_PICK);
        // 큐레이션이 전혀 없는 식당도 추천 대상임을 함께 검증한다(#154)
        Restaurant recommendation = saveRestaurant("추천 식당");
        Restaurant deleted = saveRestaurant("삭제된 식당");
        deleted.softDelete();
        entityManager.flush();
        entityManager.clear();

        var result = restaurantRepository.findRandomRestaurantIdExcluding(current.getId());

        assertThat(result).contains(recommendation.getId());
    }

    @Test
    void 최초_랜덤_추천은_제외할_식당_없이_조회한다() {
        Restaurant recommendation = saveRestaurant("추천 식당");

        var result = restaurantRepository.findRandomRestaurantIdExcluding(null);

        assertThat(result).contains(recommendation.getId());
    }

    @Test
    void 현재_식당을_제외한_추천_후보가_없으면_빈_결과를_반환한다() {
        Restaurant current = saveRestaurant("현재 식당");

        var result = restaurantRepository.findRandomRestaurantIdExcluding(current.getId());

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
                restaurant.getId(), selectedMenuId, null, null, null, PageRequest.of(0, 10));
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
    void 대표_메뉴를_가나다순으로_먼저_조회하고_복합_커서로_다음_페이지를_조회한다() {
        Restaurant restaurant = createRestaurant("메뉴 정렬 식당");
        restaurant.addMenu(createMenu("일반 메뉴 먼저", false));
        restaurant.addMenu(createMenu("나 대표 메뉴", true));
        restaurant.addMenu(createMenu("가 대표 메뉴", true));
        restaurant.addMenu(createMenu("나 대표 메뉴", true));
        restaurant.addMenu(createMenu("일반 메뉴 나중", false));
        restaurantRepository.saveAndFlush(restaurant);
        entityManager.clear();

        List<RestaurantMenu> firstPage = restaurantRepository.findMenusByRestaurantId(
                restaurant.getId(), null, null, null, null, PageRequest.of(0, 2));
        RestaurantMenu cursorMenu = firstPage.getLast();
        List<RestaurantMenu> secondPage = restaurantRepository.findMenusByRestaurantId(
                restaurant.getId(), null, cursorMenu.isMain(), cursorMenu.getName(), cursorMenu.getId(),
                PageRequest.of(0, 10));
        RestaurantMenu generalCursorMenu = secondPage.stream()
                .filter(menu -> !menu.isMain())
                .findFirst()
                .orElseThrow();
        List<RestaurantMenu> thirdPage = restaurantRepository.findMenusByRestaurantId(
                restaurant.getId(), null, generalCursorMenu.isMain(), generalCursorMenu.getName(),
                generalCursorMenu.getId(), PageRequest.of(0, 10));

        assertThat(firstPage)
                .extracting(RestaurantMenu::getName)
                .containsExactly("가 대표 메뉴", "나 대표 메뉴");
        assertThat(firstPage.getLast().getId())
                .isGreaterThan(restaurant.getMenus().get(1).getId());
        assertThat(secondPage)
                .extracting(RestaurantMenu::getName)
                .containsExactly("나 대표 메뉴", "일반 메뉴 나중", "일반 메뉴 먼저");
        assertThat(thirdPage)
                .extracting(RestaurantMenu::getName)
                .containsExactly("일반 메뉴 먼저");
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
        UUID retainedAssetId = UUID.randomUUID();
        restaurant.addMenu(RestaurantMenu.createWithAsset(
                "유지 메뉴", "메뉴 설명", retainedAssetId, PriceCurrency.JPY,
                BigDecimal.valueOf(1_000), false));
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
        retainedMenu.update("수정 메뉴", "수정 설명", null, retainedMenu.getImageAssetId(), PriceCurrency.JPY,
                BigDecimal.valueOf(1_500), true);
        managedRestaurant.removeMenusNotIn(Set.of(retainedMenuId));
        managedRestaurant.addMenu(createMenu("신규 메뉴"));
        restaurantRepository.flush();
        entityManager.clear();

        List<RestaurantMenu> menus = restaurantRepository.findMenusByRestaurantId(
                restaurantId, null, null, null, null, PageRequest.of(0, 10));

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
                    assertThat(menu.getImageAssetId()).isEqualTo(retainedAssetId);
                    assertThat(menu.getImageKey()).isNull();
                });
        assertThat(menus)
                .filteredOn(menu -> "신규 메뉴".equals(menu.getName()))
                .hasSize(1);
    }

    @Test
    void 메뉴_이미지를_교체하면_새_asset_ID를_저장하고_기존_key를_제거한다() {
        Restaurant restaurant = createRestaurant("메뉴 이미지 교체 식당");
        RestaurantMenu menu = createMenu("기존 메뉴");
        restaurant.addMenu(menu);
        restaurantRepository.saveAndFlush(restaurant);
        UUID replacementAssetId = UUID.randomUUID();

        menu.update("수정 메뉴", "수정 설명", null, replacementAssetId, PriceCurrency.JPY,
                BigDecimal.valueOf(1_500), true);
        restaurantRepository.flush();
        entityManager.clear();

        RestaurantMenu result = restaurantRepository.findMenuByRestaurantIdAndMenuId(
                restaurant.getId(), menu.getId()).orElseThrow();

        assertThat(result.getImageAssetId()).isEqualTo(replacementAssetId);
        assertThat(result.getImageKey()).isNull();
    }

    @Test
    void 메뉴_이미지_ID를_명시적으로_비우면_연결을_제거한다() {
        Restaurant restaurant = createRestaurant("메뉴 이미지 제거 식당");
        RestaurantMenu menu = RestaurantMenu.createWithAsset(
                "기존 메뉴", "메뉴 설명", UUID.randomUUID(), PriceCurrency.JPY,
                BigDecimal.valueOf(1_000), false);
        restaurant.addMenu(menu);
        restaurantRepository.saveAndFlush(restaurant);

        menu.update("수정 메뉴", "수정 설명", null, null, PriceCurrency.JPY,
                BigDecimal.valueOf(1_500), true);
        restaurantRepository.flush();
        entityManager.clear();

        RestaurantMenu result = restaurantRepository.findMenuByRestaurantIdAndMenuId(
                restaurant.getId(), menu.getId()).orElseThrow();

        assertThat(result.getImageAssetId()).isNull();
        assertThat(result.getImageKey()).isNull();
    }

    @Test
    void 리뷰_별점_변경은_리뷰_수를_유지하고_합계와_평균을_원자적으로_갱신한다() {
        Restaurant restaurant = saveRestaurant("평점 수정 식당");
        jdbcTemplate.update("""
                update restaurant
                set rating_sum = 10, review_count = 2, rating = 5.0
                where id = ?
                """, restaurant.getId());
        entityManager.clear();

        int updatedCount = restaurantRepository.updateReviewRatingStatistics(
                restaurant.getId(), 5, 4);
        entityManager.clear();
        Restaurant updated = restaurantRepository.findById(restaurant.getId()).orElseThrow();

        assertThat(updatedCount).isEqualTo(1);
        assertThat(updated.getRatingSum()).isEqualTo(9L);
        assertThat(updated.getReviewCount()).isEqualTo(2L);
        assertThat(updated.getRating()).isEqualByComparingTo("4.5");
    }

    @Test
    void 리뷰_통계가_없거나_기존_별점보다_합계가_작으면_갱신하지_않는다() {
        Restaurant restaurant = saveRestaurant("잘못된 평점 통계 식당");
        jdbcTemplate.update("""
                update restaurant
                set rating_sum = 0, review_count = 1, rating = 0.0
                where id = ?
                """, restaurant.getId());
        entityManager.clear();

        assertThat(restaurantRepository.updateReviewRatingStatistics(
                restaurant.getId(), 5, 3)).isZero();
        assertThat(restaurantRepository.updateReviewRatingStatistics(
                999_999L, 5, 3)).isZero();
    }

    private Restaurant saveRestaurant(String name) {
        Restaurant restaurant = createRestaurant(name);
        entityManager.persistAndFlush(restaurant);
        return restaurant;
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
                "초밥",
                RestaurantPlaceType.RESTAURANT,
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
        return createMenu(name, false);
    }

    private RestaurantMenu createMenu(String name, boolean main) {
        return RestaurantMenu.create(
                name,
                "메뉴 설명",
                "restaurant-menus/%s.jpg".formatted(name),
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                main
        );
    }
}
