package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class MapQuerySessionTest {
    @Test
    void 순위_동점은_각기다른_정렬에서도_최초추천순을_보존한다() {
        var session = session(List.of(candidate(3, "4.0", 2), candidate(1, "5.0", 1),
                candidate(2, "4.0", 2), candidate(4, "5.0", 2)));
        assertThat(session.ordered(RestaurantMapSort.RECOMMEND)).extracting(RestaurantMapCandidate::restaurantId)
                .containsExactly(3L, 1L, 2L, 4L);
        assertThat(session.ordered(RestaurantMapSort.RATING)).extracting(RestaurantMapCandidate::restaurantId)
                .containsExactly(1L, 4L, 3L, 2L);
        assertThat(session.ordered(RestaurantMapSort.REVIEWS)).extracting(RestaurantMapCandidate::restaurantId)
                .containsExactly(3L, 2L, 4L, 1L);
        assertThat(session.candidates()).extracting(RestaurantMapCandidate::restaurantId).containsExactly(3L, 1L, 2L, 4L);
    }

    @Test
    void 손상세션과_중복후보는_복원하지_않고_허용한_정밀도는_그대로_왕복한다() {
        var serializer = new MapSessionSerializer();
        for (String json : List.of("null", "{}", "{\"schemaVersion\":2}", "[\"java.lang.Runtime\",{}]")) {
            assertThatThrownBy(() -> serializer.deserialize(json)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> session(List.of(candidate(1, "4.0", 1), candidate(1, "4.0", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        var criteria = MapSearchCriteria.of(MapQueryBounds.parse("0." + "1".repeat(2000), "1", "0", "1"),
                null, null, null, null);
        var session = new MapQuerySession(1, UUID.randomUUID(), criteria, List.of(), Instant.now(), Instant.now().plusSeconds(900));
        assertThat(serializer.deserialize(serializer.serialize(session))).isEqualTo(session);
    }

    @Test
    void 운영설정은_키를_출력하는_getter없이도_바인딩된다() {
        String key = Base64.getEncoder().encodeToString("synthetic-map-test-key-32-bytes-only".getBytes());
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("hashi.restaurant.map.session.signing-key", key)));
        var properties = binder.bind("hashi.restaurant.map.session", Bindable.of(MapSessionProperties.class)).get();
        assertThat(properties.requireSigningKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(key));
        assertThat(properties.toString()).doesNotContain(key);
    }

    private MapQuerySession session(List<RestaurantMapCandidate> candidates) {
        return new MapQuerySession(1, UUID.randomUUID(), MapSearchCriteria.of(MapQueryBounds.parse("0", "1", "0", "1"),
                null, null, null, null), candidates, Instant.now(), Instant.now().plusSeconds(900));
    }

    private RestaurantMapCandidate candidate(long id, String rating, long reviews) {
        return new RestaurantMapCandidate(id, new BigDecimal(rating), reviews);
    }
}
