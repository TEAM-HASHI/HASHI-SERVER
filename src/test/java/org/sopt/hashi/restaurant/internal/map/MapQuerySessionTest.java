package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent.ResultBounds;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class MapQuerySessionTest {
    private static final Instant RANKING_AS_OF = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant COORDINATES_VALID_UNTIL = RANKING_AS_OF.plusSeconds(1800);
    private static final Instant SESSION_EXPIRES_AT = RANKING_AS_OF.plusSeconds(900);

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
        for (String json : List.of("null", "{}", "{\"formatVersion\":2}", "{\"schemaVersion\":1}",
                "[\"java.lang.Runtime\",{}]")) {
            assertThatThrownBy(() -> serializer.deserialize(json)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> session(List.of(candidate(1, "4.0", 1), candidate(1, "4.0", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        var criteria = MapSearchCriteria.of(MapQueryBounds.parse("0." + "1".repeat(2000), "1", "0", "1"),
                null, null, null, null);
        var session = new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), criteria, List.of(),
                null, Instant.now(), Instant.now().plusSeconds(900));
        assertThat(serializer.deserialize(serializer.serialize(session))).isEqualTo(session);
    }

    @Test
    void 검색결과요약은_개별좌표없이_왕복하고_빈결과와_단일좌표를_구분한다() throws Exception {
        var serializer = new MapSessionSerializer();
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var point = new ResultBounds(new BigDecimal("0.5"), new BigDecimal("0.5"),
                new BigDecimal("0.5"), new BigDecimal("0.5"));
        var one = searchSession(List.of(candidate(1, "4.0", 2)),
                new MapSearchResultExtent(1, point, COORDINATES_VALID_UNTIL));

        String oneJson = serializer.serialize(one);
        assertThat(serializer.deserialize(oneJson)).isEqualTo(one);
        assertThat(oneJson).contains("\"searchResult\":{\"totalCount\":1")
                .doesNotContain("latitude", "longitude");

        var empty = searchSession(List.of(), MapSearchResultExtent.empty());
        var emptyPayload = mapper.readTree(serializer.serialize(empty));
        assertThat(emptyPayload.at("/searchResult/totalCount").asLong()).isZero();
        assertThat(emptyPayload.at("/searchResult/bounds").isNull()).isTrue();
    }

    @Test
    void 검색결과요약의_잘못된경계와_후보수불일치_구버전은_410으로_거절한다() throws Exception {
        var serializer = new MapSessionSerializer();
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var point = new ResultBounds(new BigDecimal("0.5"), new BigDecimal("0.5"),
                new BigDecimal("0.5"), new BigDecimal("0.5"));
        String json = serializer.serialize(
                searchSession(List.of(candidate(1, "4.0", 2)),
                        new MapSearchResultExtent(1, point, COORDINATES_VALID_UNTIL)));

        ObjectNode countMismatch = (ObjectNode) mapper.readTree(json);
        ((ObjectNode) countMismatch.path("searchResult")).put("totalCount", 2);
        ObjectNode invalidBounds = (ObjectNode) mapper.readTree(json);
        ((ObjectNode) invalidBounds.at("/searchResult/bounds")).put("south", 2);
        ObjectNode invalidExpiry = (ObjectNode) mapper.readTree(json);
        invalidExpiry.put("expiresAt", COORDINATES_VALID_UNTIL.plusSeconds(1).toString());
        ObjectNode legacy = (ObjectNode) mapper.readTree(json);
        legacy.put("formatVersion", 1).put("schemaVersion", 1);

        for (String corrupted : List.of(mapper.writeValueAsString(countMismatch),
                mapper.writeValueAsString(invalidBounds), mapper.writeValueAsString(invalidExpiry),
                mapper.writeValueAsString(legacy))) {
            assertThatThrownBy(() -> serializer.deserialize(corrupted))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_SESSION_EXPIRED));
        }
    }

    @Test
    void 후보_tuple은_세값을_함께_보존하고_620개_payload를_기존객체형식보다_줄인다() throws Exception {
        var candidates = IntStream.rangeClosed(1, 620)
                .mapToObj(id -> candidate(id, "0.0", 0)).toList();
        var session = session(candidates);
        var serializer = new MapSessionSerializer();

        String compact = serializer.serialize(session);
        var legacyMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        int legacyBytes = legacyMapper.writeValueAsString(session).getBytes(StandardCharsets.UTF_8).length;
        int compactBytes = compact.getBytes(StandardCharsets.UTF_8).length;
        System.out.printf("MAP_SESSION_SERIALIZATION candidates=620 legacyBytes=%d compactBytes=%d%n",
                legacyBytes, compactBytes);

        assertThat(serializer.deserialize(compact)).isEqualTo(session);
        assertThat(compact).contains("\"candidates\":[[1,0.0,0],[2,0.0,0]")
                .doesNotContain("restaurantId", "reviewCount");
        assertThat(compactBytes).isLessThan(legacyBytes / 3);
    }

    @Test
    void 후보_tuple의_누락_추가_필드는_손상세션으로_거절한다() {
        var serializer = new MapSessionSerializer();
        String json = serializer.serialize(session(List.of(candidate(1, "4.0", 2))));

        for (String corrupted : List.of(json.replace("[1,4.0,2]", "[1,4.0]"),
                json.replace("[1,4.0,2]", "[1,4.0,2,3]"),
                json.replace("[[1,4.0,2]]", "[null]"))) {
            assertThatThrownBy(() -> serializer.deserialize(corrupted))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_SESSION_EXPIRED));
        }
    }

    @Test
    void 운영설정은_키를_출력하는_getter없이도_바인딩된다() {
        String key = Base64.getEncoder().encodeToString("synthetic-map-test-key-32-bytes-only".getBytes());
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "hashi.restaurant.map.session.enabled", "true", "hashi.restaurant.map.session.signing-key", key)));
        var properties = binder.bind("hashi.restaurant.map.session", Bindable.of(MapSessionProperties.class)).get();
        properties.requireConfigured();
        assertThat(properties.requireSigningKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(key));
        assertThat(properties.toString()).doesNotContain(key);
    }

    private MapQuerySession session(List<RestaurantMapCandidate> candidates) {
        return new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null), candidates, null,
                Instant.now(), Instant.now().plusSeconds(900));
    }

    private MapQuerySession searchSession(List<RestaurantMapCandidate> candidates, MapSearchResultExtent searchResult) {
        return new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, "fixture"), candidates, searchResult,
                RANKING_AS_OF, SESSION_EXPIRES_AT);
    }

    private RestaurantMapCandidate candidate(long id, String rating, long reviews) {
        return new RestaurantMapCandidate(id, new BigDecimal(rating), reviews);
    }
}
