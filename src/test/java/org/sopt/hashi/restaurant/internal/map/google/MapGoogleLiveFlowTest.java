package org.sopt.hashi.restaurant.internal.map.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.media.MediaPort;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@org.junit.jupiter.api.Tag("map-live")
@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.map.google-geocoding.enabled=false", "hashi.map.maintenance.retention-enabled=false",
        "hashi.map.maintenance.refresh-ahead=3d",
        "hashi.map.location-job.max-attempts=1", "hashi.map.location-job.enabled=true", "hashi.map.location-job.retention=30d",
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
@org.springframework.context.annotation.Import(MapLiveProviderConfiguration.class)
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MapGoogleLiveFlowTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("map_flow").withUsername("hashi").withPassword("hashi")
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
    @Autowired MapLiveProviderConfiguration.LiveRequestCounter liveRequests;
    @MockitoBean LocationJobScheduler scheduler;
    @MockitoBean MediaPort mediaPort;
    @MockitoBean FileStorage fileStorage;
    @Test
    void 관리자_저장과_실제_Google_갱신후에도_지도와_컬렉션핀을_유지한다() throws Exception {
        mvc.perform(post("/api/v1/admin/restaurants")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isUnauthorized());
        sessionProperties.setEnabled(true);
        jdbc.update("update restaurant_geocoding_budget set enabled=true,daily_limit=2,max_concurrent=1,reserved_calls=0,budget_day=null,blocked_until=null where id=1");
        String token = jwt.createAccessToken(1L, "ROLE_ADMIN");
        JsonNode saved = body(mvc.perform(post("/api/v1/admin/restaurants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ADMIN-204"))
                .andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.addressRevision").value(1))
                .andReturn().getResponse().getContentAsString());
        long id = saved.path("data").path("restaurantId").asLong();
        assertThat(jdbc.queryForObject("select count(*) from restaurant_location_job where restaurant_id=?",
                Integer.class, id)).isEqualTo(1);
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESTAURANT-018"));

        worker.runOnce();
        String jobState = jdbc.queryForObject("select state from restaurant_location_job where restaurant_id=?", String.class, id);
        String failureCode = jdbc.queryForObject("select failure_code from restaurant_location_job where restaurant_id=?", String.class, id);
        assertThat(jobState).as("Real provider/adoption outcome; failureCode=%s", failureCode).isEqualTo("SUCCEEDED");
        sessionProperties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes()));

        mvc.perform(get("/api/v1/admin/restaurants/{id}/location", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.locationStatus").value("READY"));
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.restaurantId").value(id));
        JsonNode firstPage = body(mvc.perform(get("/api/v1/restaurants/map")
                        .param("south", "35").param("north", "36").param("west", "139").param("east", "140"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.content[0].restaurantId").value(id))
                .andReturn().getResponse().getContentAsString()).path("data");
        User mapUser = users.saveAndFlush(User.onboard(
                "지도실호출회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01011110007", "map-live@hashi.test", null));
        String userToken = jwt.createAccessToken(mapUser.getId(), "ROLE_USER");
        long collectionId = body(mvc.perform(post("/api/v1/collections")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"지도 통합\",\"color\":\"red\",\"visibility\":\"public\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data").path("collectionId").asLong();
        mvc.perform(post("/api/v1/collections/{collectionId}/restaurants", collectionId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":" + id + "}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/collections/{collectionId}/map-markers", collectionId))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.visibleRestaurantCount").value(1))
                .andExpect(jsonPath("$.data.locationUnavailableCount").value(0))
                .andExpect(jsonPath("$.data.content[0].restaurantId").value(id));
        mvc.perform(get("/api/v1/restaurants/save-counts").param("restaurantIds", Long.toString(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restaurants[0].saveCount").value(1));
        mvc.perform(get("/api/v1/users/me/restaurant-saves").param("restaurantIds", Long.toString(id))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restaurants[0].saved").value(true));

        assertThat(jdbc.queryForObject("select reserved_calls from restaurant_geocoding_budget where id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from restaurant_location_job where restaurant_id=? and state='SUCCEEDED'", Integer.class, id)).isEqualTo(1);

        LocationSnapshot initial = location(id);
        assertThat(Duration.between(initial.obtainedAt(), initial.validUntil())).isEqualTo(Duration.ofDays(30));
        jdbc.update("""
                UPDATE restaurant_location l JOIN restaurant r ON r.location_id=l.id
                SET l.obtained_at=UTC_TIMESTAMP(6)-INTERVAL 29 DAY,
                    l.valid_until=UTC_TIMESTAMP(6)+INTERVAL 1 DAY
                WHERE r.id=?
                """, id);
        LocationSnapshot aged = location(id);
        assertThat(Duration.between(aged.obtainedAt(), aged.validUntil())).isEqualTo(Duration.ofDays(30));

        assertThat(retentionOptions.retentionEnabled()).isFalse();
        assertThat(retention.refresh(retentionOptions)).isEqualTo(1);
        LocationSnapshot pending = location(id);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.latitude()).isEqualByComparingTo(aged.latitude());
        assertThat(pending.longitude()).isEqualByComparingTo(aged.longitude());
        assertThat(pending.obtainedAt()).isEqualTo(aged.obtainedAt());
        assertThat(pending.validUntil()).isEqualTo(aged.validUntil());
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restaurantId").value(id));
        mvc.perform(get("/api/v1/collections/{collectionId}/map-markers", collectionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibleRestaurantCount").value(1))
                .andExpect(jsonPath("$.data.content[0].restaurantId").value(id));

        worker.runOnce();
        LocationSnapshot refreshed = location(id);
        assertThat(refreshed.status()).isEqualTo("READY");
        assertThat(refreshed.latitude()).isNotNull();
        assertThat(refreshed.longitude()).isNotNull();
        assertThat(refreshed.obtainedAt()).isAfter(aged.obtainedAt());
        assertThat(refreshed.validUntil()).isAfter(aged.validUntil());
        assertThat(Duration.between(refreshed.obtainedAt(), refreshed.validUntil()))
                .isEqualTo(Duration.ofDays(30));
        mvc.perform(get("/api/v1/restaurants/map")
                        .param("south", "35").param("north", "36").param("west", "139").param("east", "140"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].restaurantId").value(id));
        mvc.perform(get("/api/v1/collections/{collectionId}/map-markers", collectionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibleRestaurantCount").value(1))
                .andExpect(jsonPath("$.data.locationUnavailableCount").value(0))
                .andExpect(jsonPath("$.data.content[0].restaurantId").value(id));
        assertThat(liveRequests.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_calls from restaurant_geocoding_budget where id=1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from restaurant_location_job where restaurant_id=? and state='SUCCEEDED'", Integer.class, id)).isEqualTo(2);
        System.out.println("Map live flow: providerRequests=2 refreshRegistered=true refreshed=true readPins=true");
    }
    private LocationSnapshot location(long restaurantId) {
        return jdbc.queryForObject("""
                SELECT l.status, l.latitude, l.longitude, l.obtained_at, l.valid_until
                FROM restaurant r JOIN restaurant_location l ON l.id=r.location_id
                WHERE r.id=?
                """, (rs, row) -> new LocationSnapshot(rs.getString("status"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"), rs.getObject("obtained_at", LocalDateTime.class),
                rs.getObject("valid_until", LocalDateTime.class)), restaurantId);
    }
    private record LocationSnapshot(String status, BigDecimal latitude, BigDecimal longitude,
                                    LocalDateTime obtainedAt, LocalDateTime validUntil) { }
    private JsonNode body(String value) throws Exception { return json.readTree(value); }
    private String createBody() {
        return """
                {"name":"합성 식당","localName":"試験","summary":"합성 요약","description":"합성 설명",
                 "address":"東京都新宿区西新宿2丁目8番1号","area":"합성 지역","genre":"sushi",
                 "foodCategory":"초밥","placeType":"restaurant","priceCurrency":"JPY",
                 "minPrice":1,"maxPrice":10,"imageKeys":["restaurants/synthetic.jpg"],
                 "hashtags":["합성"],"curationTypes":[],"businessHours":[
                   {"dayOfWeek":"MONDAY","closed":true},{"dayOfWeek":"TUESDAY","closed":true},
                   {"dayOfWeek":"WEDNESDAY","closed":true},{"dayOfWeek":"THURSDAY","closed":true},
                   {"dayOfWeek":"FRIDAY","closed":true},{"dayOfWeek":"SATURDAY","closed":true},
                   {"dayOfWeek":"SUNDAY","closed":true}]}
                """;
    }
}
