package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RestaurantLocationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
    private static final MapCoordinates POINT = MapCoordinates.of(new BigDecimal("10.123456"),
            new BigDecimal("20.654321"));

    @Test
    void 기존_식당은_위치와_관광_지역이_없어도_유효하다() {
        Restaurant restaurant = restaurant();
        assertThat(restaurant.getLocation()).isNull();
        assertThat(restaurant.getMapRegionId()).isNull();
        assertThat(restaurant.hasUsableMapLocation(CLOCK)).isFalse();
        assertThat(restaurant.getArea()).isEqualTo("표시 지역");
    }

    @Test
    void 미분류_READY는_사용_가능하고_만료_순간부터_제외한다() {
        Restaurant restaurant = pending();
        assertThat(complete(restaurant, 1, request(restaurant))).isTrue();
        assertThat(restaurant.hasUsableMapLocation(CLOCK.withZone(ZoneId.of("Asia/Tokyo")))).isTrue();
        assertThat(restaurant.getMapRegionId()).isNull();
        assertThat(restaurant.hasUsableMapLocation(Clock.offset(CLOCK, java.time.Duration.ofDays(1)))).isFalse();
        assertThat(complete(restaurant, 1, request(restaurant))).isFalse();
    }

    @Test
    void 주소_변경은_좌표를_제거하고_지역을_보존하며_이전_결과를_막는다() {
        Restaurant restaurant = pending();
        restaurant.assignMapRegion(7L);
        UUID previousRequest = request(restaurant);
        complete(restaurant, 1, previousRequest);

        updateAddress(restaurant, "새 합성 주소");

        assertThat(restaurant.getLocation().getAddressRevision()).isEqualTo(2);
        assertThat(request(restaurant)).isNotEqualTo(previousRequest);
        assertPendingWithoutCoordinates(restaurant);
        assertThat(restaurant.getMapRegionId()).isEqualTo(7L);
        assertThat(complete(restaurant, 1, previousRequest)).isFalse();
        assertThat(complete(restaurant, 1, request(restaurant))).isFalse();
        assertThat(complete(restaurant, 2, request(restaurant))).isTrue();
    }

    @Test
    void 동일_주소와_null_PATCH는_revision과_READY와_통계를_보존한다() {
        Restaurant restaurant = pending();
        complete(restaurant, 1, request(restaurant));
        UUID request = request(restaurant);
        updateAddress(restaurant, restaurant.getAddress());
        updateAddress(restaurant, null);
        assertThat(restaurant.getLocation().getAddressRevision()).isEqualTo(1);
        assertThat(request(restaurant)).isEqualTo(request);
        assertThat(restaurant.hasUsableMapLocation(CLOCK)).isTrue();
        assertThat(restaurant.getReviewCount()).isZero();
        assertThat(restaurant.getRating()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @EnumSource(value = RestaurantLocationStatus.class, names = {"REVIEW_REQUIRED", "FAILED"})
    void 실패_후_같은_주소의_재요청도_이전_요청을_구별한다(RestaurantLocationStatus outcome) {
        Restaurant restaurant = pending();
        UUID oldRequest = request(restaurant);
        restaurant.requestLocationResolution();
        assertThat(request(restaurant)).isEqualTo(oldRequest);
        assertThat(restaurant.rejectLocation(1, oldRequest, outcome)).isTrue();
        assertThat(restaurant.retryLocationWhenDue(CLOCK)).isFalse();
        restaurant.requestLocationResolution();
        assertThat(request(restaurant)).isNotEqualTo(oldRequest);
        assertThat(complete(restaurant, 1, oldRequest)).isFalse();
        assertThat(restaurant.rejectLocation(1, oldRequest, outcome)).isFalse();
        assertThat(complete(restaurant, 1, request(restaurant))).isTrue();
    }

    @Test
    void 재시도는_예정시각부터_새_요청으로_시작하고_주소_변경은_대기를_없앤다() {
        Restaurant restaurant = pending();
        UUID oldRequest = request(restaurant);
        assertThat(restaurant.deferLocation(1, oldRequest, NOW.plusMinutes(1), CLOCK)).isTrue();
        assertThat(restaurant.retryLocationWhenDue(CLOCK)).isFalse();
        assertThat(complete(restaurant, 1, oldRequest)).isFalse();
        Clock due = Clock.offset(CLOCK, java.time.Duration.ofMinutes(1));
        assertThat(restaurant.retryLocationWhenDue(due)).isTrue();
        assertPendingWithoutCoordinates(restaurant);
        assertThat(restaurant.deferLocation(1, oldRequest, NOW.plusMinutes(2), due)).isFalse();
        restaurant.deferLocation(1, request(restaurant), NOW.plusMinutes(2), due);
        updateAddress(restaurant, "다른 합성 주소");
        assertPendingWithoutCoordinates(restaurant);
    }

    @Test
    void READY_재시도는_거절하고_갱신은_기존_좌표를_제거한다() {
        Restaurant restaurant = pending();
        complete(restaurant, 1, request(restaurant));
        UUID previous = request(restaurant);
        assertThatThrownBy(restaurant::requestLocationResolution).isInstanceOf(IllegalStateException.class);
        restaurant.refreshLocation();
        assertPendingWithoutCoordinates(restaurant);
        assertThat(request(restaurant)).isNotEqualTo(previous);
        assertThat(complete(restaurant, 1, previous)).isFalse();
        assertThatThrownBy(restaurant::refreshLocation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 삭제_식당은_좌표를_노출하거나_늦은_결과를_반영하지_않는다() {
        Restaurant pending = pending();
        UUID request = request(pending);
        pending.softDelete();
        assertThat(complete(pending, 1, request)).isFalse();
        assertThat(pending.deferLocation(1, request, NOW.plusHours(1), CLOCK)).isFalse();
        assertThat(pending.rejectLocation(1, request, RestaurantLocationStatus.FAILED)).isFalse();
        assertThatThrownBy(pending::requestLocationResolution).isInstanceOf(IllegalStateException.class);
        Restaurant ready = pending();
        complete(ready, 1, request(ready));
        ready.softDelete();
        assertThat(ready.hasUsableMapLocation(CLOCK)).isFalse();
        assertThat(ready.getLocation().getCoordinates()).isEqualTo(POINT);
    }

    @Test
    void 유효하지_않은_결과는_상태를_부분_변경하지_않는다() {
        Restaurant restaurant = pending();
        UUID request = request(restaurant);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, POINT,
                RestaurantLocationSource.GOOGLE_GEOCODING, NOW.plusSeconds(1), NOW.plusDays(1), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, POINT,
                RestaurantLocationSource.GOOGLE_GEOCODING, NOW.minusDays(1), NOW, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, POINT, null, NOW, NOW.plusDays(1), CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, null,
                RestaurantLocationSource.OPERATOR, NOW, NOW.plusDays(1), CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> restaurant.deferLocation(1, request, NOW, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restaurant.rejectLocation(1, request, RestaurantLocationStatus.READY))
                .isInstanceOf(IllegalArgumentException.class);
        assertPendingWithoutCoordinates(restaurant);
    }

    @Test
    void DB_시간_정밀도로_내린_후에도_유효기간을_검증한다() {
        Restaurant restaurant = pending();
        assertThatThrownBy(() -> restaurant.completeLocation(1, request(restaurant), POINT,
                RestaurantLocationSource.OPERATOR, NOW, NOW.plusNanos(999), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        restaurant.completeLocation(1, request(restaurant), POINT, RestaurantLocationSource.OPERATOR,
                NOW.minusNanos(1), NOW.plusSeconds(1).plusNanos(999), CLOCK);
        assertThat(restaurant.getLocation().getObtainedAt()).isEqualTo(NOW.minusNanos(1000));
        assertThat(restaurant.getLocation().getValidUntil()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void 지역_수정과_해제는_위치_상태를_변경하지_않는다() {
        Restaurant restaurant = pending();
        UUID request = request(restaurant);
        restaurant.assignMapRegion(1L);
        restaurant.assignMapRegion(null);
        assertThatThrownBy(() -> restaurant.assignMapRegion(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(request(restaurant)).isEqualTo(request);
        assertThat(restaurant.getMapRegionId()).isNull();
    }

    private Restaurant restaurant() {
        return Restaurant.create("합성 식당", "fixture", "요약", "설명", "합성 주소", "표시 지역",
                RestaurantGenre.SUSHI, "초밥", RestaurantPlaceType.RESTAURANT,
                PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
    }

    private Restaurant pending() {
        Restaurant restaurant = restaurant();
        restaurant.requestLocationResolution();
        return restaurant;
    }

    private UUID request(Restaurant restaurant) {
        return restaurant.getLocation().getRequestId();
    }

    private boolean complete(Restaurant restaurant, long revision, UUID requestId) {
        return restaurant.completeLocation(revision, requestId, POINT, RestaurantLocationSource.GOOGLE_GEOCODING,
                NOW, NOW.plusDays(1), CLOCK);
    }

    private void updateAddress(Restaurant restaurant, String address) {
        restaurant.updateBasicInfo(null, null, null, null, address, null, null, null, null, null, null, null);
    }

    private void assertPendingWithoutCoordinates(Restaurant restaurant) {
        RestaurantLocation location = restaurant.getLocation();
        assertThat(location.getStatus()).isEqualTo(RestaurantLocationStatus.PENDING);
        assertThat(location.getCoordinates()).isNull();
        assertThat(location.getSource()).isNull();
        assertThat(location.getObtainedAt()).isNull();
        assertThat(location.getValidUntil()).isNull();
        assertThat(location.getNextAttemptAt()).isNull();
        assertThat(restaurant.hasUsableMapLocation(CLOCK)).isFalse();
    }
}
