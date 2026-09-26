package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Isolated;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.domain.MapBounds;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.MapRegion;
import org.sopt.hashi.restaurant.domain.MapRegionRepository;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.LocationJobScheduler;
import org.sopt.hashi.restaurant.internal.map.MapSessionProperties;
import org.sopt.hashi.restaurant.internal.map.RestaurantLocationWorker;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceProperties;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceProperties.Command;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceProperties.Mode;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceRunner;
import org.sopt.hashi.restaurant.migration.LocationRetentionService;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Outcome;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 관리자 HTTP 저장부터 durable worker, 실제 DB 지도 조회까지 하나의 서버 조립으로 검증한다. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.map.location-job.enabled=true", "hashi.map.location-job.retention=1d",
        "hashi.map.location-job.south=10", "hashi.map.location-job.north=11",
        "hashi.map.location-job.west=20", "hashi.map.location-job.east=21",
        "hashi.restaurant.map.initial-bounds.south=10", "hashi.restaurant.map.initial-bounds.north=11",
        "hashi.restaurant.map.initial-bounds.west=20", "hashi.restaurant.map.initial-bounds.east=21",
        "hashi.restaurant.map.supported-bounds.south=10", "hashi.restaurant.map.supported-bounds.north=11",
        "hashi.restaurant.map.supported-bounds.west=20", "hashi.restaurant.map.supported-bounds.east=21"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Isolated("Verifies JVM UTC with a JDBC Asia/Seoul session")
class RestaurantMapFlowIntegrationTest {
    private static final String ADDRESS = "東京都試験区架空町1丁目2番3号";
    private static final TimeZone ORIGINAL_ZONE = TimeZone.getDefault();

    static {
        // Class initialization runs before SpringExtension can bootstrap the application context.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterAll
    static void restoreJvm() {
        TimeZone.setDefault(ORIGINAL_ZONE);
    }

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
            .withExposedPorts(6379);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired RestaurantLocationWorker worker;
    @Autowired LocationJobTransactions transactions;
    @Autowired RestaurantPort port;
    @Autowired MapRegionRepository regions;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MapSessionProperties sessionProperties;
    @Autowired LocationRetentionService retention;
    @Autowired LocationMaintenanceRunner maintenance;
    @Autowired LocationMaintenanceProperties maintenanceOptions;
    @MockitoBean GeocodingProvider google;
    @MockitoBean LocationJobScheduler scheduler;
    @MockitoBean MediaPort mediaPort;
    @MockitoBean FileStorage fileStorage;

    @BeforeEach
    void resetSyntheticDatabase() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        assertThat(jdbc.queryForObject("select timestampdiff(second, utc_timestamp(), now())", Integer.class))
                .isEqualTo(9 * 60 * 60);
        jdbc.update("update restaurant set deleted=true");
        // The persistent provider budget is closed by default; open only the disposable MySQL fixture.
        jdbc.update("""
                update restaurant_geocoding_budget set enabled=true, daily_limit=100, max_concurrent=4,
                reserved_calls=0, budget_day=null, blocked_until=null where id=1
                """);
    }

    @Test
    void 관리자_저장_후_실제_worker가_확인한_식당만_현재위치와_지역수와_Port에_나타난다() throws Exception {
        mvc.perform(post("/api/v1/admin/restaurants")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isUnauthorized());
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

        MapRegion region = MapRegion.create("SYNTHETIC", "합성 지역",
                MapCoordinates.of(new BigDecimal("10.5"), new BigDecimal("20.5")),
                MapBounds.of(new BigDecimal("10"), new BigDecimal("11"),
                        new BigDecimal("20"), new BigDecimal("21")), 0);
        region.activate();
        long regionId = regions.saveAndFlush(region).getId();
        jdbc.update("update restaurant set map_region_id=? where id=?", regionId, id);
        when(google.geocode(anyString())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            assertThat(invocation.getArgument(0, String.class)).isEqualTo(ADDRESS);
            return new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate()));
        });
        worker.runOnce();
        sessionProperties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes()));

        mvc.perform(get("/api/v1/admin/restaurants/{id}/location", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.locationStatus").value("READY"));
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.restaurantId").value(id))
                .andExpect(jsonPath("$.data.location.latitude").value(10.123457));
        mvc.perform(get("/api/v1/restaurants/map/regions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.regions[0].restaurantCount").value(1));
        assertThat(port.findActiveMapInfos(List.of(id)).getFirst().location()).isNotNull();

        JsonNode firstPage = body(mvc.perform(get("/api/v1/restaurants/map")
                        .param("south", "10").param("north", "11").param("west", "20").param("east", "21"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.content[0].restaurantId").value(id))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(firstPage.path("content").get(0).path("location").path("latitude").decimalValue())
                .isEqualByComparingTo("10.123457");

        mvc.perform(patch("/api/v1/admin/restaurants/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"東京都試験区架空町4丁目5番6号\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ADMIN-205"))
                .andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.addressRevision").value(2));
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESTAURANT-018"));
        mvc.perform(get("/api/v1/restaurants/map/regions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.regions[0].restaurantCount").value(0));
        assertThat(port.findActiveMapInfos(List.of(id)).getFirst().location()).isNull();
        mvc.perform(get("/api/v1/restaurants/map")
                        .param("querySessionId", firstPage.path("querySessionId").asText())
                        .param("sort", "recommend"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void 주소_A와_B의_늦은_완료는_현재_주소와_삭제_상태를_덮어쓰지_못한다() throws Exception {
        String token = jwt.createAccessToken(1L, "ROLE_ADMIN");
        long id = body(mvc.perform(post("/api/v1/admin/restaurants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").path("restaurantId").asLong();
        var targetA = transactions.candidates().stream().filter(value -> value.restaurantId().equals(id))
                .findFirst().orElseThrow();
        var claimA = transactions.claim(targetA).orElseThrow();
        mvc.perform(patch("/api/v1/admin/restaurants/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"東京都試験区架空町4丁目5番6号\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.addressRevision").value(2));
        var position = new LocationAdoptionPolicy(LocationAdoptionPolicyTest.properties())
                .evaluate(ADDRESS, new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate())))
                .coordinates();
        assertThat(transactions.complete(claimA, new Outcome(position, null, null))).isFalse();
        mvc.perform(get("/api/v1/admin/restaurants/{id}/location", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.addressRevision").value(2));
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isConflict());

        var targetB = transactions.candidates().stream().filter(value -> value.restaurantId().equals(id))
                .findFirst().orElseThrow();
        var claimB = transactions.claim(targetB).orElseThrow();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/admin/restaurants/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(transactions.complete(claimB, Outcome.failure(
                GeocodingResult.FailureKind.TRANSIENT_ERROR))).isFalse();
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isNotFound());
        assertThat(port.findActiveMapInfos(List.of(id))).isEmpty();
    }

    @Test
    void Google이_비활성이어도_보존기한_정리후_현재위치_페이지_Port에서_좌표가_사라진다() throws Exception {
        String token = jwt.createAccessToken(1L, "ROLE_ADMIN");
        long id = body(mvc.perform(post("/api/v1/admin/restaurants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").path("restaurantId").asLong();
        when(google.geocode(anyString())).thenReturn(
                new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate())));
        worker.runOnce();
        sessionProperties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes()));
        JsonNode first = body(mvc.perform(get("/api/v1/restaurants/map")
                        .param("south", "10").param("north", "11").param("west", "20").param("east", "21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].restaurantId").value(id))
                .andReturn().getResponse().getContentAsString()).path("data");
        jdbc.update("""
                update restaurant_location l join restaurant r on r.location_id=l.id
                set l.valid_until=UTC_TIMESTAMP(6)+interval 20 minute where r.id=?
                """, id);
        jdbc.update("update restaurant_geocoding_budget set enabled=false where id=1");
        assertThat(retention.purge(maintenanceOptions).purged()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from restaurant_location l join restaurant r on r.location_id=l.id
                where r.id=? and (l.latitude is not null or l.longitude is not null or l.valid_until is not null)
                """, Integer.class, id)).isZero();
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESTAURANT-018"));
        assertThat(port.findActiveMapInfos(List.of(id)).getFirst().location()).isNull();
        mvc.perform(get("/api/v1/restaurants/map")
                        .param("querySessionId", first.path("querySessionId").asText())
                        .param("sort", "recommend"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void 갱신_등록은_오래된_좌표를_현재위치와_기존_페이지에서_즉시_제외한다() throws Exception {
        String token = jwt.createAccessToken(1L, "ROLE_ADMIN");
        long id = body(mvc.perform(post("/api/v1/admin/restaurants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").path("restaurantId").asLong();
        when(google.geocode(anyString())).thenReturn(
                new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate())));
        worker.runOnce();
        sessionProperties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes()));
        JsonNode first = body(mvc.perform(get("/api/v1/restaurants/map")
                        .param("south", "10").param("north", "11").param("west", "20").param("east", "21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].restaurantId").value(id))
                .andReturn().getResponse().getContentAsString()).path("data");
        jdbc.update("""
                update restaurant_location l join restaurant r on r.location_id=l.id
                set l.obtained_at=UTC_TIMESTAMP(6)-interval 21 hour,
                    l.valid_until=UTC_TIMESTAMP(6)+interval 3 hour where r.id=?
                """, id);
        jdbc.update("update restaurant_geocoding_budget set enabled=false where id=1");
        UUID runId = UUID.randomUUID();
        var options = new LocationMaintenanceProperties(Command.START, Mode.REFRESH, true,
                runId, id - 1, id, 10, 1, 10, 80,
                Duration.ofHours(6), Duration.ofHours(1), false, null);
        maintenance.execute(options);
        assertThat(jdbc.queryForObject("""
                select count(*) from restaurant_location_maintenance_job m
                join restaurant_location_job j on j.id=m.job_id
                where m.run_id=? and j.restaurant_id=? and j.state='PENDING'
                """, Integer.class, runId.toString(), id)).isEqualTo(1);
        verify(google, times(1)).geocode(ADDRESS);
        mvc.perform(get("/api/v1/admin/restaurants/{id}/location", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.locationStatus").value("PENDING"));
        mvc.perform(get("/api/v1/restaurants/{id}/map-location", id))
                .andExpect(status().isConflict());
        assertThat(port.findActiveMapInfos(List.of(id)).getFirst().location()).isNull();
        mvc.perform(get("/api/v1/restaurants/map")
                        .param("querySessionId", first.path("querySessionId").asText())
                        .param("sort", "recommend"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void 합성_23개는_10_10_3으로_조회되고_모든_카드_ID에_같은_핀_좌표가_있다() throws Exception {
        Clock clock = Clock.systemUTC();
        List<Long> expected = transactionTemplate.execute(status -> {
            List<Long> ids = new ArrayList<>();
            for (int index = 0; index < 23; index++) {
                Restaurant restaurant = Restaurant.create("합성 식당 " + index, "試験", "요약", "설명",
                        ADDRESS, "합성 지역", RestaurantGenre.SUSHI, "초밥", RestaurantPlaceType.RESTAURANT,
                        PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
                restaurant.requestLocationResolution();
                restaurant.completeLocation(1, restaurant.getLocation().getRequestId(),
                        MapCoordinates.of(new BigDecimal("10.5"), new BigDecimal("20.5")),
                        RestaurantLocationSource.OPERATOR, LocalDateTime.now(clock).minusHours(1),
                        LocalDateTime.now(clock).plusHours(1), clock);
                ids.add(restaurants.save(restaurant).getId());
            }
            return ids;
        });
        sessionProperties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes()));
        JsonNode page = body(mvc.perform(get("/api/v1/restaurants/map")
                        .param("south", "10").param("north", "11").param("west", "20").param("east", "21"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        List<Long> seen = new ArrayList<>();
        String sessionId = page.path("querySessionId").asText();
        for (int size : List.of(10, 10, 3)) {
            assertThat(page.path("querySessionId").asText()).isEqualTo(sessionId);
            assertThat(page.path("content").size()).isEqualTo(size);
            page.path("content").forEach(card -> {
                assertThat(card.path("restaurantId").asLong()).isPositive();
                assertThat(card.path("location").path("latitude").decimalValue())
                        .isEqualByComparingTo("10.5");
                assertThat(card.path("location").path("longitude").decimalValue())
                        .isEqualByComparingTo("20.5");
                seen.add(card.path("restaurantId").asLong());
            });
            if (size == 3) {
                assertThat(page.path("hasNext").asBoolean()).isFalse();
                assertThat(page.has("nextCursor")).isFalse();
            } else {
                assertThat(page.path("hasNext").asBoolean()).isTrue();
                page = body(mvc.perform(get("/api/v1/restaurants/map")
                                .param("cursor", page.path("nextCursor").asText()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            }
        }
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    private JsonNode body(String value) throws Exception {
        return json.readTree(value);
    }

    private String createBody() {
        return """
                {"name":"합성 식당","localName":"試験","summary":"합성 요약","description":"합성 설명",
                 "address":"東京都試験区架空町1丁目2番3号","area":"합성 지역","genre":"sushi",
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
