package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
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

    private Restaurant saveRestaurant(String name, RestaurantCurationType curationType) {
        Restaurant restaurant = Restaurant.create(
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
        restaurant.replaceCurationTypes(List.of(curationType));
        entityManager.persistAndFlush(restaurant);
        return restaurant;
    }
}
