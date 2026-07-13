package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000)
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
}
