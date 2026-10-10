package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationRetentionPolicyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);

    @Test
    void 동일revision이어도_새요청결과는_오래된정리작업으로_제거하지않는다() {
        var location = ready(RestaurantLocationSource.GOOGLE_GEOCODING);
        var request = location.getRequestId();
        var obtained = location.getObtainedAt();
        var until = location.getValidUntil();
        location.beginRefresh();
        complete(location, RestaurantLocationSource.GOOGLE_GEOCODING);
        assertThat(location.purgeGoogle(1, request, obtained, until, NOW.plusHours(2))).isFalse();
        assertThat(location.isUsable(CLOCK)).isTrue();
    }

    @Test
    void 수명snapshot과_기한전경계를_함께확인하고_운영자좌표는_유지한다() {
        var location = ready(RestaurantLocationSource.GOOGLE_GEOCODING);
        var request = location.getRequestId();
        assertThat(location.purgeGoogle(1, request, NOW.minusHours(2), NOW.plusHours(1), NOW.plusHours(2))).isFalse();
        assertThat(location.purgeGoogle(1, request, NOW.minusHours(1), NOW.plusHours(1), NOW)).isFalse();
        assertThat(location.purgeGoogle(1, request, NOW.minusHours(1), NOW.plusHours(1), NOW.plusHours(1))).isTrue();
        assertThat(location.getStatus()).isEqualTo(RestaurantLocationStatus.REVIEW_REQUIRED);
        assertThat(location.getCoordinates()).isNull();
        assertThat(location.getSource()).isNull();
        assertThat(location.getObtainedAt()).isNull();
        assertThat(location.getValidUntil()).isNull();
        assertThat(location.getRequestId()).isEqualTo(request);
        var admin = ready(RestaurantLocationSource.ADMIN);
        assertThat(admin.purgeGoogle(1, admin.getRequestId(), NOW.minusHours(1), NOW.plusHours(1),
                NOW.plusHours(2))).isFalse();
    }

    @Test
    void 갱신중_만료좌표만_지우고_진행중인_요청과_재시도시각은_보존한다() {
        var location = ready(RestaurantLocationSource.GOOGLE_GEOCODING);
        location.beginRefresh();
        var request = location.getRequestId();
        var next = NOW.plusHours(2);
        assertThat(location.defer(1, request, next, CLOCK)).isTrue();
        assertThat(location.purgeGoogle(1, request, NOW.minusHours(1), NOW.plusHours(1), NOW.plusHours(1))).isTrue();
        assertThat(location.getCoordinates()).isNull();
        assertThat(location.getStatus()).isEqualTo(RestaurantLocationStatus.RETRY_WAIT);
        assertThat(location.getRequestId()).isEqualTo(request);
        assertThat(location.getNextAttemptAt()).isEqualTo(next);
    }

    @Test
    void Places_위치정리는_좌표와_Place_ID와_attribution을_함께지운다() {
        var location = RestaurantLocation.pending();
        var attribution = new RestaurantLocationAttribution("Google Maps", "https://maps.google.com/");
        assertThat(location.complete(1, location.getRequestId(),
                MapCoordinates.of(BigDecimal.TEN, BigDecimal.TEN), RestaurantLocationSource.GOOGLE_PLACES,
                "place-retention", List.of(attribution), NOW.minusHours(1), NOW.plusHours(1), CLOCK)).isTrue();
        var request = location.getRequestId();

        assertThat(location.purgeGoogle(1, request, NOW.minusHours(1), NOW.plusHours(1),
                NOW.plusHours(1))).isTrue();

        assertThat(location.getStatus()).isEqualTo(RestaurantLocationStatus.REVIEW_REQUIRED);
        assertThat(location.getCoordinates()).isNull();
        assertThat(location.getSource()).isNull();
        assertThat(location.getGooglePlaceId()).isNull();
        assertThat(location.getPlacesAttributions()).isNull();
        assertThat(location.getRequestId()).isEqualTo(request);
    }

    private RestaurantLocation ready(RestaurantLocationSource source) {
        var location = RestaurantLocation.pending();
        complete(location, source);
        return location;
    }

    private void complete(RestaurantLocation location, RestaurantLocationSource source) {
        assertThat(location.complete(1, location.getRequestId(), MapCoordinates.of(BigDecimal.TEN, BigDecimal.TEN),
                source, NOW.minusHours(1), NOW.plusHours(1), CLOCK)).isTrue();
    }
}
