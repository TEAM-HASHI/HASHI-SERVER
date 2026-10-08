package org.sopt.hashi.restaurant.internal.map.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.LocationJobScheduler;
import org.sopt.hashi.restaurant.internal.map.LocationRetentionProperties;
import org.sopt.hashi.restaurant.internal.map.LocationRetentionService;
import org.sopt.hashi.restaurant.internal.map.MapSessionProperties;
import org.sopt.hashi.restaurant.internal.map.RestaurantLocationWorker;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("map-live")
@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.map.google-geocoding.enabled=false", "hashi.map.google-places.enabled=false",
        "hashi.map.places-selection.enabled=true",
        "hashi.map.places-selection.signing-key=c3ludGhldGljLXBsYWNlcy1zaWduaW5nLWtleS0zMmI=",
        "hashi.map.places-selection.token-ttl=10m",
        "hashi.map.maintenance.retention-enabled=false", "hashi.map.maintenance.refresh-ahead=3d",
        "hashi.map.location-job.max-attempts=1", "hashi.map.location-job.enabled=true",
        "hashi.map.location-job.retention=30d",
        "hashi.map.location-job.south=35", "hashi.map.location-job.north=36",
        "hashi.map.location-job.west=139", "hashi.map.location-job.east=140",
        "hashi.restaurant.map.initial-bounds.south=35", "hashi.restaurant.map.initial-bounds.north=36",
        "hashi.restaurant.map.initial-bounds.west=139", "hashi.restaurant.map.initial-bounds.east=140",
        "hashi.restaurant.map.supported-bounds.south=35", "hashi.restaurant.map.supported-bounds.north=36",
        "hashi.restaurant.map.supported-bounds.west=139", "hashi.restaurant.map.supported-bounds.east=140",
        "hashi.restaurant.map.session.limits.requests-per-caller=600",
        "hashi.restaurant.map.session.limits.new-queries-per-caller=100",
        "hashi.restaurant.map.session.limits.requests-per-minute=3000"
})
@Import(MapPlacesLiveProviderConfiguration.class)
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MapPlacesLiveFlowTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("map_places_flow").withUsername("hashi").withPassword("hashi")
            .withUrlParam("connectionTimeZone", "Asia/Seoul")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--maxmemory", "128mb", "--maxmemory-policy", "noeviction");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RestaurantLocationWorker worker;
    @Autowired LocationRetentionService retention;
    @Autowired LocationRetentionProperties retentionOptions;
    @Autowired MapSessionProperties sessionProperties;
    @Autowired MapPlacesLiveProviderConfiguration.LiveRequestCounter liveRequests;
    @MockitoBean GeocodingProvider geocodingProvider;
    @MockitoBean LocationJobScheduler scheduler;
    @MockitoBean MediaPort mediaPort;
    @MockitoBean FileStorage fileStorage;

    @Test
    void 관리자_Places선택과_상세갱신후에도_지도와_컬렉션핀을_유지한다() throws Exception {
        given(geocodingProvider.geocode(anyString())).willReturn(new GeocodingResult.NoResults());
        enableBudgets();
        sessionProperties.setEnabled(true);
        sessionProperties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes()));
        String adminToken = jwt.createAccessToken(1L, "ROLE_ADMIN");

        JsonNode created = body(mvc.perform(post("/api/v1/admin/restaurants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.addressRevision").value(1))
                .andReturn().getResponse().getContentAsString());
        long restaurantId = created.path("data").path("restaurantId").asLong();

        worker.runOnce();
        verify(geocodingProvider, times(1)).geocode(anyString());
        mvc.perform(get("/api/v1/admin/restaurants/{restaurantId}/location", restaurantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.locationStatus").value("REVIEW_REQUIRED"));

        JsonNode searchData = body(mvc.perform(post(
                        "/api/v1/admin/restaurants/{restaurantId}/location/place-candidates", restaurantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAddressRevision\":1}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(searchData.path("restaurantId").asLong() == restaurantId).isTrue();
        assertThat(searchData.path("addressRevision").asLong() == 1L).isTrue();
        JsonNode candidates = searchData.path("candidates");
        assertThat(candidates.isArray()).isTrue();

        int matchCount = 0;
        JsonNode selected = null;
        for (JsonNode candidate : candidates) {
            String displayName = candidate.path("displayName").asText("");
            boolean ikaruga = displayName.toLowerCase(Locale.ROOT).contains("ikaruga")
                    || displayName.contains("斑鳩");
            if (ikaruga) {
                matchCount++;
                selected = candidate;
            }
        }
        assertThat(matchCount).isEqualTo(1);
        assertThat(selected != null && selected.path("displayName").isTextual()).isTrue();
        assertThat(selected != null && selected.path("address").isTextual()).isTrue();
        assertThat(selected != null && selected.path("latitude").isNumber()).isTrue();
        assertThat(selected != null && selected.path("longitude").isNumber()).isTrue();
        assertThat(selected != null && "JP".equals(selected.path("countryCode").asText())).isTrue();
        assertThat(selected != null && selected.path("administrativeArea").isTextual()).isTrue();
        assertThat(selected != null && selected.path("types").isArray()).isTrue();
        assertThat(selected != null && selected.path("businessStatus").isTextual()).isTrue();
        assertThat(selected != null && selected.path("attributions").isArray()).isTrue();
        assertThat(selected != null && selected.path("googleMapsUri").isTextual()).isTrue();
        assertThat(selected != null && selected.path("selectionToken").isTextual()
                && !selected.path("selectionToken").asText().isBlank()).isTrue();
        assertThat(selected != null && selected.path("selectionExpiresAt").isTextual()).isTrue();

        BigDecimal searchedLatitude = selected.path("latitude").decimalValue();
        BigDecimal searchedLongitude = selected.path("longitude").decimalValue();
        String selectionToken = selected.path("selectionToken").asText();
        String selectionBody = json.createObjectNode()
                .put("expectedAddressRevision", 1)
                .put("selectionToken", selectionToken).toString();
        mvc.perform(post("/api/v1/admin/restaurants/{restaurantId}/location/place-selection", restaurantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(selectionBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.addressRevision").value(1));

        worker.runOnce();
        LocationSnapshot initial = location(restaurantId);
        assertThat(initial.status()).isEqualTo("READY");
        assertThat(initial.source()).isEqualTo("GOOGLE_PLACES");
        assertThat(round6(initial.latitude()).compareTo(round6(searchedLatitude)) == 0).isTrue();
        assertThat(round6(initial.longitude()).compareTo(round6(searchedLongitude)) == 0).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM restaurant r JOIN restaurant_location l ON l.id=r.location_id
                WHERE r.id=? AND l.google_place_id IS NOT NULL AND l.google_place_id<>''
                """, Integer.class, restaurantId)).isEqualTo(1);

        User mapUser = users.saveAndFlush(User.onboard(
                "Places실호출회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01011110008", "places-live@hashi.test", null));
        String userToken = jwt.createAccessToken(mapUser.getId(), "ROLE_USER");
        long collectionId = body(mvc.perform(post("/api/v1/collections")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Places 통합\",\"color\":\"red\",\"visibility\":\"public\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data").path("collectionId").asLong();
        mvc.perform(post("/api/v1/collections/{collectionId}/restaurants", collectionId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":" + restaurantId + "}"))
                .andExpect(status().isCreated());
        assertPins(restaurantId, collectionId);

        assertThat(Duration.between(initial.obtainedAt(), initial.validUntil())).isEqualTo(Duration.ofDays(30));
        jdbc.update("""
                UPDATE restaurant_location l JOIN restaurant r ON r.location_id=l.id
                SET l.obtained_at=UTC_TIMESTAMP(6)-INTERVAL 29 DAY,
                    l.valid_until=UTC_TIMESTAMP(6)+INTERVAL 1 DAY
                WHERE r.id=?
                """, restaurantId);
        LocationSnapshot aged = location(restaurantId);
        assertThat(Duration.between(aged.obtainedAt(), aged.validUntil())).isEqualTo(Duration.ofDays(30));

        assertThat(retentionOptions.retentionEnabled()).isFalse();
        assertThat(retention.refresh(retentionOptions)).isEqualTo(1);
        LocationSnapshot pending = location(restaurantId);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(round6(pending.latitude()).compareTo(round6(aged.latitude())) == 0).isTrue();
        assertThat(round6(pending.longitude()).compareTo(round6(aged.longitude())) == 0).isTrue();
        assertThat(pending.obtainedAt()).isEqualTo(aged.obtainedAt());
        assertThat(pending.validUntil()).isEqualTo(aged.validUntil());
        assertPins(restaurantId, collectionId);

        worker.runOnce();
        LocationSnapshot refreshed = location(restaurantId);
        assertThat(refreshed.status()).isEqualTo("READY");
        assertThat(refreshed.source()).isEqualTo("GOOGLE_PLACES");
        assertThat(refreshed.obtainedAt().isAfter(aged.obtainedAt())).isTrue();
        assertThat(refreshed.validUntil().isAfter(aged.validUntil())).isTrue();
        assertThat(Duration.between(refreshed.obtainedAt(), refreshed.validUntil()))
                .isEqualTo(Duration.ofDays(30));
        assertPins(restaurantId, collectionId);

        assertThat(liveRequests.searchCount()).isEqualTo(1);
        assertThat(liveRequests.detailCount()).isEqualTo(2);
        assertThat(liveRequests.totalCount()).isEqualTo(3);
        assertThat(placeBudgetUsed("SEARCH")).isEqualTo(1);
        assertThat(placeBudgetUsed("DETAILS")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_calls from restaurant_geocoding_budget where id=1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM restaurant_location_job
                WHERE restaurant_id=? AND operation='PLACE_DETAILS' AND state='SUCCEEDED'
                """, Integer.class, restaurantId)).isEqualTo(2);
        System.out.println(
                "Map Places live flow: providerRequests=3 selectionReady=true refreshReady=true readPins=true");
    }

    private void enableBudgets() {
        jdbc.update("""
                UPDATE restaurant_geocoding_budget
                SET enabled=true,daily_limit=1,max_concurrent=1,reserved_calls=0,
                    budget_day=null,blocked_until=null
                WHERE id=1
                """);
        jdbc.update("""
                UPDATE restaurant_places_budget
                SET enabled=true,daily_limit=1,minute_limit=1,daily_used=0,minute_used=0,
                    budget_day=null,minute_window_start=null,blocked_until=null
                WHERE operation='SEARCH'
                """);
        jdbc.update("""
                UPDATE restaurant_places_budget
                SET enabled=true,daily_limit=2,minute_limit=2,daily_used=0,minute_used=0,
                    budget_day=null,minute_window_start=null,blocked_until=null
                WHERE operation='DETAILS'
                """);
    }

    private int placeBudgetUsed(String operation) {
        return jdbc.queryForObject("select daily_used from restaurant_places_budget where operation=?",
                Integer.class, operation);
    }

    private void assertPins(long restaurantId, long collectionId) throws Exception {
        mvc.perform(get("/api/v1/restaurants/map")
                        .param("south", "35").param("north", "36").param("west", "139").param("east", "140"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.content[0].restaurantId").value(restaurantId));
        mvc.perform(get("/api/v1/collections/{collectionId}/map-markers", collectionId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.visibleRestaurantCount").value(1))
                .andExpect(jsonPath("$.data.locationUnavailableCount").value(0))
                .andExpect(jsonPath("$.data.content[0].restaurantId").value(restaurantId));
    }

    private LocationSnapshot location(long restaurantId) {
        return jdbc.queryForObject("""
                SELECT l.status,l.source,l.latitude,l.longitude,l.obtained_at,l.valid_until
                FROM restaurant r JOIN restaurant_location l ON l.id=r.location_id
                WHERE r.id=?
                """, (rs, row) -> new LocationSnapshot(rs.getString("status"), rs.getString("source"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                rs.getObject("obtained_at", LocalDateTime.class),
                rs.getObject("valid_until", LocalDateTime.class)), restaurantId);
    }

    private BigDecimal round6(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private JsonNode body(String value) throws Exception {
        return json.readTree(value);
    }

    private String createBody() {
        return """
                {"name":"Ikaruga","localName":"東京駅 斑鳩","summary":"실호출 검증 요약",
                 "description":"실호출 검증 설명",
                 "address":"東京都千代田区丸の内1-9-1 東京駅一番街 B1F",
                 "geocodingAddress":"東京都千代田区丸の内1-9-1 東京駅一番街 B1F",
                 "area":"도쿄역","genre":"ramen","foodCategory":"라멘",
                 "placeType":"restaurant","priceCurrency":"JPY","minPrice":1,"maxPrice":10,
                 "imageKeys":["restaurants/synthetic-places.jpg"],"hashtags":["실호출"],
                 "curationTypes":[],"businessHours":[
                   {"dayOfWeek":"MONDAY","closed":true},{"dayOfWeek":"TUESDAY","closed":true},
                   {"dayOfWeek":"WEDNESDAY","closed":true},{"dayOfWeek":"THURSDAY","closed":true},
                   {"dayOfWeek":"FRIDAY","closed":true},{"dayOfWeek":"SATURDAY","closed":true},
                   {"dayOfWeek":"SUNDAY","closed":true}]}
                """;
    }

    private record LocationSnapshot(
            String status,
            String source,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDateTime obtainedAt,
            LocalDateTime validUntil
    ) {
    }
}
