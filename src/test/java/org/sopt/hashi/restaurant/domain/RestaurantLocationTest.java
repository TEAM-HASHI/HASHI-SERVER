package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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

    @Test
    void 위치확인주소_변경과_삭제는_revision을_올리고_이전_결과를_막는다() {
        Restaurant restaurant = restaurantWithGeocodingAddress("\u00A0東京都試験区架空町1丁目2番3号\u202F");
        restaurant.requestLocationResolution();
        UUID firstRequest = request(restaurant);
        assertThat(restaurant.geocodingAddressForResolution()).isEqualTo("東京都試験区架空町1丁目2番3号");

        updateGeocodingAddress(restaurant, "\u2007東京都試験区架空町1丁目2番4号\u00A0");

        assertThat(restaurant.getGeocodingAddress()).isEqualTo("東京都試験区架空町1丁目2番4号");
        assertThat(restaurant.getLocation().getAddressRevision()).isEqualTo(2);
        assertThat(complete(restaurant, 1, firstRequest)).isFalse();
        UUID secondRequest = request(restaurant);

        updateGeocodingAddress(restaurant, "\u00A0\u2007\u202F");

        assertThat(restaurant.getGeocodingAddress()).isNull();
        assertThat(restaurant.geocodingAddressForResolution()).isEqualTo(restaurant.getAddress());
        assertThat(restaurant.getLocation().getAddressRevision()).isEqualTo(3);
        assertThat(complete(restaurant, 2, secondRequest)).isFalse();
    }

    @Test
    void 표시주소를_바꾸며_위치확인주소를_생략하면_과거_override를_지운다() {
        Restaurant restaurant = restaurantWithGeocodingAddress("東京都試験区架空町1丁目2番3号");
        restaurant.requestLocationResolution();

        updateAddress(restaurant, "東京都試験区別町4丁目5番6号 새 건물 2F");

        assertThat(restaurant.getGeocodingAddress()).isNull();
        assertThat(restaurant.geocodingAddressForResolution()).isEqualTo("東京都試験区別町4丁目5番6号 새 건물 2F");
        assertThat(restaurant.getLocation().getAddressRevision()).isEqualTo(2);
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
    void READY_재시도는_거절하고_같은_주소의_갱신은_기존_좌표를_만료까지만_유지한다() {
        Restaurant restaurant = pending();
        complete(restaurant, 1, request(restaurant));
        UUID previous = request(restaurant);
        assertThatThrownBy(restaurant::requestLocationResolution).isInstanceOf(IllegalStateException.class);
        restaurant.refreshLocation();
        assertThat(restaurant.getLocation().getStatus()).isEqualTo(RestaurantLocationStatus.PENDING);
        assertThat(restaurant.getLocation().getAddressRevision()).isEqualTo(1);
        assertAcceptedLocationUnchanged(restaurant);
        assertThat(restaurant.getLocation().getNextAttemptAt()).isNull();
        assertThat(restaurant.hasUsableMapLocation(CLOCK)).isTrue();
        assertThat(restaurant.hasUsableMapLocation(clockAt(NOW.plusDays(1).minusNanos(1000)))).isTrue();
        assertThat(restaurant.hasUsableMapLocation(clockAt(NOW.plusDays(1)))).isFalse();
        assertThat(request(restaurant)).isNotEqualTo(previous);
        assertThat(complete(restaurant, 1, previous)).isFalse();
        assertThatThrownBy(restaurant::refreshLocation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 반복_재시도는_기존_좌표와_만료를_유지하고_지난_요청의_결과를_거절한다() {
        Restaurant restaurant = readyForRefresh();
        for (int attempt = 1; attempt <= 3; attempt++) {
            UUID previousRequest = request(restaurant);
            Clock attemptedAt = clockAt(NOW.plusMinutes(attempt - 1));
            LocalDateTime nextAttemptAt = NOW.plusMinutes(attempt);
            assertThat(restaurant.deferLocation(1, previousRequest, nextAttemptAt, attemptedAt)).isTrue();
            assertThat(restaurant.getLocation().getStatus()).isEqualTo(RestaurantLocationStatus.RETRY_WAIT);
            assertAcceptedLocationUnchanged(restaurant);
            assertThat(restaurant.hasUsableMapLocation(attemptedAt)).isTrue();
            assertThat(restaurant.retryLocationWhenDue(attemptedAt)).isFalse();
            assertThat(restaurant.retryLocationWhenDue(clockAt(nextAttemptAt))).isTrue();
            assertThat(request(restaurant)).isNotEqualTo(previousRequest);
            assertThat(restaurant.getLocation().getNextAttemptAt()).isNull();
            assertThat(complete(restaurant, 1, previousRequest)).isFalse();
            assertThat(restaurant.deferLocation(1, previousRequest, NOW.plusHours(1), CLOCK)).isFalse();
            assertThat(restaurant.rejectLocation(1, previousRequest, RestaurantLocationStatus.FAILED)).isFalse();
            assertAcceptedLocationUnchanged(restaurant);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RestaurantLocationStatus.class, names = {"REVIEW_REQUIRED", "FAILED"})
    void 갱신_실패와_명시적_재요청은_기존_좌표의_수명을_연장하지_않는다(RestaurantLocationStatus outcome) {
        Restaurant restaurant = readyForRefresh();
        UUID failedRequest = request(restaurant);
        assertThat(restaurant.rejectLocation(1, failedRequest, outcome)).isTrue();
        assertThat(restaurant.getLocation().getStatus()).isEqualTo(outcome);
        assertThat(restaurant.retryLocationWhenDue(CLOCK)).isFalse();
        assertThat(restaurant.hasUsableMapLocation(CLOCK)).isTrue();
        assertThat(restaurant.hasUsableMapLocation(clockAt(NOW.plusDays(1)))).isFalse();
        assertAcceptedLocationUnchanged(restaurant);

        restaurant.requestLocationResolution();
        assertThat(request(restaurant)).isNotEqualTo(failedRequest);
        assertThat(complete(restaurant, 1, failedRequest)).isFalse();
        assertThat(restaurant.getLocation().getStatus()).isEqualTo(RestaurantLocationStatus.PENDING);
        assertAcceptedLocationUnchanged(restaurant);
        assertThat(restaurant.hasUsableMapLocation(clockAt(NOW.plusDays(1)))).isFalse();
    }

    @Test
    void 검증을_통과한_갱신_결과만_좌표와_출처와_수명을_함께_교체한다() {
        Restaurant restaurant = readyForRefresh();
        MapCoordinates updated = MapCoordinates.of(BigDecimal.ONE, BigDecimal.TEN);
        Clock completedAt = Clock.offset(CLOCK, Duration.ofHours(1));

        assertThat(restaurant.completeLocation(1, request(restaurant), updated, RestaurantLocationSource.ADMIN,
                NOW.plusHours(1), NOW.plusDays(2), completedAt)).isTrue();

        assertThat(restaurant.getLocation().getStatus()).isEqualTo(RestaurantLocationStatus.READY);
        assertThat(restaurant.getLocation().getCoordinates()).isEqualTo(updated);
        assertThat(restaurant.getLocation().getSource()).isEqualTo(RestaurantLocationSource.ADMIN);
        assertThat(restaurant.getLocation().getObtainedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(restaurant.getLocation().getValidUntil()).isEqualTo(NOW.plusDays(2));
        assertThat(restaurant.hasUsableMapLocation(clockAt(NOW.plusDays(1)))).isTrue();
    }

    @Test
    void 잘못된_갱신_결과는_기존_좌표나_작업_상태를_부분_변경하지_않는다() {
        Restaurant restaurant = readyForRefresh();
        UUID request = request(restaurant);
        MapCoordinates updated = MapCoordinates.of(BigDecimal.ONE, BigDecimal.TEN);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, updated,
                RestaurantLocationSource.ADMIN, NOW.plusSeconds(1), NOW.plusDays(2), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, updated,
                RestaurantLocationSource.ADMIN, NOW.minusDays(1), NOW, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, updated,
                null, NOW, NOW.plusDays(2), CLOCK)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> restaurant.completeLocation(1, request, null,
                RestaurantLocationSource.ADMIN, NOW, NOW.plusDays(2), CLOCK)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> restaurant.deferLocation(1, request, NOW, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertAcceptedLocationUnchanged(restaurant);
        assertThat(restaurant.getLocation().getStatus()).isEqualTo(RestaurantLocationStatus.PENDING);
        assertThat(request(restaurant)).isEqualTo(request);
        assertThat(restaurant.getLocation().getNextAttemptAt()).isNull();
    }

    @Test
    void 갱신_중_주소가_바뀌면_이전_좌표를_지우고_늦은_갱신_결과를_거절한다() {
        Restaurant restaurant = readyForRefresh();
        UUID refreshRequest = request(restaurant);
        restaurant.assignMapRegion(7L);

        updateAddress(restaurant, "갱신 중 바뀐 합성 주소");

        assertPendingWithoutCoordinates(restaurant);
        assertThat(restaurant.getLocation().getAddressRevision()).isEqualTo(2);
        assertThat(restaurant.getMapRegionId()).isEqualTo(7L);
        assertThat(complete(restaurant, 1, refreshRequest)).isFalse();
        assertThat(complete(restaurant, 2, refreshRequest)).isFalse();
        assertThat(restaurant.deferLocation(1, refreshRequest, NOW.plusHours(1), CLOCK)).isFalse();
        assertThat(complete(restaurant, 2, request(restaurant))).isTrue();
    }

    @Test
    void 갱신_중_삭제된_식당은_유효한_이전_좌표도_노출하지_않고_작업을_중단한다() {
        Restaurant restaurant = readyForRefresh();
        UUID request = request(restaurant);
        restaurant.softDelete();
        assertThat(restaurant.hasUsableMapLocation(CLOCK)).isFalse();
        assertThat(complete(restaurant, 1, request)).isFalse();
        assertThat(restaurant.deferLocation(1, request, NOW.plusHours(1), CLOCK)).isFalse();
        assertThat(restaurant.rejectLocation(1, request, RestaurantLocationStatus.FAILED)).isFalse();
        assertThat(restaurant.retryLocationWhenDue(CLOCK)).isFalse();
        assertThatThrownBy(restaurant::refreshLocation).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(restaurant::requestLocationResolution).isInstanceOf(IllegalStateException.class);
        assertAcceptedLocationUnchanged(restaurant);
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
                RestaurantLocationSource.ADMIN, NOW, NOW.plusDays(1), CLOCK))
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
                RestaurantLocationSource.ADMIN, NOW, NOW.plusNanos(999), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        restaurant.completeLocation(1, request(restaurant), POINT, RestaurantLocationSource.ADMIN,
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

    private Restaurant restaurantWithGeocodingAddress(String geocodingAddress) {
        return Restaurant.create("합성 식당", "fixture", "요약", "설명", "표시용 전체 주소 架空ビル1F",
                geocodingAddress, "표시 지역", RestaurantGenre.SUSHI, "초밥",
                RestaurantPlaceType.RESTAURANT, PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
    }

    private Restaurant pending() {
        Restaurant restaurant = restaurant();
        restaurant.requestLocationResolution();
        return restaurant;
    }

    private UUID request(Restaurant restaurant) {
        return restaurant.getLocation().getRequestId();
    }

    private Restaurant readyForRefresh() {
        Restaurant restaurant = pending();
        complete(restaurant, 1, request(restaurant));
        restaurant.refreshLocation();
        return restaurant;
    }

    private Clock clockAt(LocalDateTime time) {
        return Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private void assertAcceptedLocationUnchanged(Restaurant restaurant) {
        RestaurantLocation location = restaurant.getLocation();
        assertThat(location.getCoordinates()).isEqualTo(POINT);
        assertThat(location.getSource()).isEqualTo(RestaurantLocationSource.GOOGLE_GEOCODING);
        assertThat(location.getObtainedAt()).isEqualTo(NOW);
        assertThat(location.getValidUntil()).isEqualTo(NOW.plusDays(1));
    }

    private boolean complete(Restaurant restaurant, long revision, UUID requestId) {
        return restaurant.completeLocation(revision, requestId, POINT, RestaurantLocationSource.GOOGLE_GEOCODING,
                NOW, NOW.plusDays(1), CLOCK);
    }

    private void updateAddress(Restaurant restaurant, String address) {
        restaurant.updateBasicInfo(null, null, null, null, address, null, null, null, null, null, null, null);
    }

    private void updateGeocodingAddress(Restaurant restaurant, String geocodingAddress) {
        restaurant.updateBasicInfo(null, null, null, null, null, geocodingAddress,
                null, null, null, null, null, null, null);
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
