package org.sopt.hashi.restaurant.dev;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.BusinessHourCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.RestaurantCurationType;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 더미 식당 생성 — local·dev 프로필에서만 빈이 등록된다(운영에는 존재 자체가 없음).
 * 어드민 등록 경로({@link RestaurantService#createByAdmin})를 그대로 재사용해
 * 영업시간 7요일 등 도메인 검증을 통과하는 완전한 식당(메뉴·이미지·해시태그·큐레이션 포함)을 만든다.
 */
@Profile({"local", "dev"})
@Service
public class DevRestaurantDataGenerator {

    private static final String TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 8;
    private static final List<String> AREAS =
            List.of("이케부쿠로", "신주쿠", "시부야", "긴자", "아사쿠사", "우에노");
    private static final List<String> HASHTAGS = List.of("더미", "현지인맛집", "테스트");
    // foodCategory는 자유 텍스트(#145) — 실데이터처럼 요리명 단위 샘플을 쓴다
    private static final List<String> FOOD_CATEGORIES =
            List.of("초밥", "라멘", "돈카츠", "야키니쿠", "스키야키", "텐동", "오마카세");

    private final RestaurantService restaurantService;

    public DevRestaurantDataGenerator(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    /** 더미 식당 1곳을 생성하고 restaurantId를 반환한다. 장르·지역 등은 무작위 배정된다. */
    @Transactional
    public Long createRestaurant() {
        int seed = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        return restaurantService.createByAdmin(newDummyCommand(seed)).restaurantId();
    }

    private AdminRestaurantCommand newDummyCommand(int seed) {
        String token = randomToken();
        RestaurantGenre genre = pick(RestaurantGenre.values(), seed);
        String foodCategory = pick(FOOD_CATEGORIES, seed);
        RestaurantPlaceType placeType = pick(RestaurantPlaceType.values(), seed);
        RestaurantCurationType curationType = pick(RestaurantCurationType.values(), seed);
        BigDecimal minPrice = BigDecimal.valueOf(1000L * (1 + seed % 5));
        BigDecimal maxPrice = minPrice.add(BigDecimal.valueOf(3000));

        return new AdminRestaurantCommand(
                "더미식당-" + token,
                "ダミー食堂-" + token,
                genre.description() + " 더미 식당",
                "테스트용 더미 데이터로 생성된 식당입니다. 실제 가게가 아닙니다.",
                "도쿄도 도시마구 더미 1-1-1",
                pick(AREAS, seed),
                genre.value(),
                foodCategory,
                placeType.value(),
                PriceCurrency.JPY.value(),
                minPrice,
                maxPrice,
                List.of("restaurants/dummy-" + token + "-1.jpg"),
                null,
                null,
                List.of(new MenuCommand(
                        "더미 대표 메뉴", genre.description() + " 대표 구성",
                        "restaurant-menus/dummy-" + token + "-menu.jpg",
                        PriceCurrency.JPY.value(), minPrice.add(BigDecimal.valueOf(500)), true)),
                HASHTAGS,
                List.of(curationType.value()),
                weekdayOpenBusinessHours());
    }

    /** 월~토 11:00-22:00(월요일만 브레이크 포함), 일요일 휴무 — 7요일 전부 포함해야 하는 검증(RESTAURANT-006)을 만족한다. */
    private List<BusinessHourCommand> weekdayOpenBusinessHours() {
        List<BusinessHourCommand> hours = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day == DayOfWeek.SUNDAY) {
                hours.add(new BusinessHourCommand(day, null, null, null, null, true));
                continue;
            }
            LocalTime breakStart = (day == DayOfWeek.MONDAY) ? LocalTime.of(15, 0) : null;
            LocalTime breakEnd = (day == DayOfWeek.MONDAY) ? LocalTime.of(16, 0) : null;
            hours.add(new BusinessHourCommand(
                    day, LocalTime.of(11, 0), LocalTime.of(22, 0), breakStart, breakEnd, false));
        }
        return hours;
    }

    private <T> T pick(T[] values, int seed) {
        return values[seed % values.length];
    }

    private String pick(List<String> values, int seed) {
        return values.get(seed % values.size());
    }

    private String randomToken() {
        StringBuilder token = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            token.append(TOKEN_CHARS.charAt(ThreadLocalRandom.current().nextInt(TOKEN_CHARS.length())));
        }
        return token.toString();
    }
}
