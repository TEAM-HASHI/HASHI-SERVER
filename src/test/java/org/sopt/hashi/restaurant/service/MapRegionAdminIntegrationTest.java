package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.MapQueryProperties;
import org.sopt.hashi.restaurant.internal.map.MapSessionProperties;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.LocationJobScheduler;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m", "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test", "springdoc.api-docs.enabled=false",
        "hashi.restaurant.map.initial-bounds.south=0", "hashi.restaurant.map.initial-bounds.north=1",
        "hashi.restaurant.map.initial-bounds.west=0", "hashi.restaurant.map.initial-bounds.east=1",
        "hashi.restaurant.map.supported-bounds.south=-1", "hashi.restaurant.map.supported-bounds.north=2",
        "hashi.restaurant.map.supported-bounds.west=-1", "hashi.restaurant.map.supported-bounds.east=2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MapRegionAdminIntegrationTest {
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String REGIONS = "/api/v1/admin/map-regions";
    private static final String ASSIGN = "/api/v1/admin/restaurants/{id}/map-region";
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("admin_regions").withUsername("hashi").withPassword("hashi");
    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired OnboardingTokenStore onboarding;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;
    @Autowired RestaurantPort port;
    @Autowired TransactionTemplate transactions;
    @Autowired MapSessionProperties sessions;
    @Autowired MapQueryProperties queryProperties;
    @Autowired StringRedisTemplate redis;
    @MockitoBean MediaPort media;
    @MockitoBean FileStorage files;
    @MockitoBean GeocodingProvider google;
    @MockitoBean LocationJobScheduler scheduler;

    @BeforeEach
    void 합성_데이터를_초기화한다() {
        jdbc.update("update restaurant set deleted=true");
        jdbc.update("delete from map_region");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        sessions.setEnabled(true);
        sessions.setSigningKey(Base64.getEncoder().encodeToString("synthetic-map-test-key-32-bytes-only".getBytes()));
        given(media.findImages(any())).willReturn(Map.of());
    }

    @Test
    void 동일_code_PUT은_ID와_소속을_유지하고_관리자_목록에는_비활성도_정렬해_포함한다() throws Exception {
        JsonNode first = upsert("AREA", body("첫 지역", 2, false));
        long id = first.path("mapRegionId").asLong();
        long restaurant = fixture();
        assign(restaurant, id);
        assertThat(upsert("AREA", body("첫 지역", 2, false)).path("mapRegionId").asLong()).isEqualTo(id);
        JsonNode updated = upsert("AREA", body("수정 지역", 3, true));
        assertThat(updated.path("mapRegionId").asLong()).isEqualTo(id);
        assertThat(jdbc.queryForObject("select count(*) from map_region where code='AREA'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select map_region_id from restaurant where id=?", Long.class, restaurant))
                .isEqualTo(id);
        upsert("DRAFT", body("초안", 1, false));
        JsonNode list = success(admin(get(REGIONS).param("size", "1")));
        assertThat(list.path("totalElements").asLong()).isEqualTo(2);
        assertThat(list.path("content").get(0).path("code").asText()).isEqualTo("DRAFT");
        assertThat(list.path("content").get(0).path("active").asBoolean()).isFalse();
        assertThat(success(admin(get(REGIONS).param("page", "1").param("size", "1")))
                .path("content").get(0).path("code").asText()).isEqualTo("AREA");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 100})
    void 목록_offset이_JPA_정수_범위를_넘으면_400을_반환한다(int size) throws Exception {
        int firstOverflowPage = Integer.MAX_VALUE / size + 1;
        mvc.perform(admin(get(REGIONS).param("page", Integer.toString(firstOverflowPage))
                .param("size", Integer.toString(size))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void 목록_offset의_JPA_정수_경계_안에서는_빈_페이지를_반환한다(int size) throws Exception {
        upsert("AREA", body("지역", 0, false));
        int lastValidPage = Integer.MAX_VALUE / size;
        JsonNode result = success(admin(get(REGIONS).param("page", Integer.toString(lastValidPage))
                .param("size", Integer.toString(size))));
        assertThat(result.path("content").size()).isZero();
        assertThat(result.path("page").asInt()).isEqualTo(lastValidPage);
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void 관리자_소속_입력은_공개_지역_집계에_반영되고_해제해도_좌표와_주소는_유지한다() throws Exception {
        long region = upsert("AREA", body("지역", 0, true)).path("mapRegionId").asLong();
        long restaurant = fixture();
        assign(restaurant, region);
        assertThat(success(get("/api/v1/restaurants/map/regions")).path("regions").get(0)
                .path("restaurantCount").asLong()).isEqualTo(1);
        assertThat(success(query(region)).path("content").size()).isEqualTo(1);
        assign(restaurant, null);
        assertThat(success(get("/api/v1/restaurants/map/regions")).path("regions").get(0)
                .path("restaurantCount").asLong()).isZero();
        assertThat(success(query(null)).path("content").size()).isEqualTo(1);
        transactions.executeWithoutResult(status -> {
            Restaurant reloaded = restaurants.findById(restaurant).orElseThrow();
            assertThat(reloaded.getMapRegionId()).isNull();
            assertThat(reloaded.getLocation().getAddressRevision()).isEqualTo(1);
            assertThat(reloaded.hasUsableMapLocation(CLOCK)).isTrue();
            assertThat(reloaded.getAddress()).isEqualTo("합성 주소");
        });
    }

    @Test
    void 지역_할당은_카메라_경계로_제한하지_않되_공개_집계는_현재_카메라_안에서만_센다() throws Exception {
        String bounds = body("좁은 지역", 0, true).replace("\"north\":1", "\"north\":0.4")
                .replace("\"latitude\":0.5", "\"latitude\":0.2");
        long region = upsert("NARROW", bounds).path("mapRegionId").asLong();
        assign(fixture(), region);
        assertThat(success(get("/api/v1/restaurants/map/regions")).path("regions").get(0)
                .path("restaurantCount").asLong()).isZero();
        assertThat(success(query(null)).path("content").size()).isEqualTo(1);
        assertThat(success(get("/api/v1/restaurants/map").param("mapRegionId", Long.toString(region))
                .param("south", "0").param("north", "0.4").param("west", "0").param("east", "1"))
                .path("content").size()).isZero();
    }

    @Test
    void 비활성화와_소속_해제는_기존_조회_세션의_다음_페이지에도_반영된다() throws Exception {
        long region = upsert("AREA", body("지역", 0, true)).path("mapRegionId").asLong();
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            long id = fixture();
            assign(id, region);
            ids.add(id);
        }
        JsonNode first = success(query(region));
        String cursor = first.path("nextCursor").asText();
        for (Long id : ids) {
            assign(id, null);
        }
        assertThat(success(get("/api/v1/restaurants/map").param("cursor", cursor)).path("content").size()).isZero();
        upsert("AREA", body("지역", 0, false));
        mvc.perform(get("/api/v1/restaurants/map").param("cursor", cursor)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT-012"));
        assertThat(success(query(null)).path("content").size()).isEqualTo(10);
    }

    @Test
    void 없는_지역과_삭제된_식당은_404이며_필드_누락과_음수_ID는_해제하지_않는다() throws Exception {
        long id = fixture();
        long region = upsert("AREA", body("지역", 0, false)).path("mapRegionId").asLong();
        assign(id, region);
        mvc.perform(admin(put(ASSIGN, id).contentType(MediaType.APPLICATION_JSON).content("{\"mapRegionId\":999999}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESTAURANT-023"));
        for (String invalid : List.of("{}", "{\"mapRegionId\":0}", "{\"mapRegionId\":-1}")) {
            mvc.perform(admin(put(ASSIGN, id).contentType(MediaType.APPLICATION_JSON).content(invalid)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select map_region_id from restaurant where id=?", Long.class, id)).isEqualTo(region);
        jdbc.update("update restaurant set deleted=true where id=?", id);
        mvc.perform(admin(put(ASSIGN, id).contentType(MediaType.APPLICATION_JSON).content("{\"mapRegionId\":null}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESTAURANT-004"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANONYMOUS", "USER", "ONBOARDING"})
    void 실제_필터는_관리자가_아닌_모든_주체의_세_API를_차단한다(String role) throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(get(REGIONS),
                put(REGIONS + "/FORBIDDEN").contentType(MediaType.APPLICATION_JSON).content(body("금지", 0, false)),
                put(ASSIGN, 1).contentType(MediaType.APPLICATION_JSON).content("{\"mapRegionId\":null}"));
        for (var request : requests) {
            if (!role.equals("ANONYMOUS")) {
                String token = role.equals("ONBOARDING") ? jwt.createOnboardingToken(1L)
                        : jwt.createAccessToken(1L, "ROLE_USER");
                if (role.equals("ONBOARDING")) {
                    onboarding.save(1L, token);
                }
                request.header("Authorization", "Bearer " + token);
            }
            mvc.perform(request).andExpect(role.equals("ANONYMOUS") ? status().isUnauthorized() : status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select count(*) from map_region", Long.class)).isZero();
    }

    @Test
    void 잘못된_전체설정은_기존_행을_부분_수정하지_않는다() throws Exception {
        upsert("AREA", body("유지", 0, false));
        List<String> invalid = List.of(body(" ", 0, false), body("\u3000", 0, false), body("음수", -1, false),
                body("역전", 0, false).replace("\"north\":1", "\"north\":0"),
                body("범위밖", 0, false).replace("\"latitude\":0.5", "\"latitude\":2"),
                body("정밀도", 0, false).replace("\"latitude\":0.5", "\"latitude\":0.1234567"),
                body("누락", 0, false).replace(",\"active\":false", ""));
        for (String body : invalid) {
            mvc.perform(admin(put(REGIONS + "/AREA").contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }
        for (String code : List.of("lower", "1AREA", "AREA-1", "A".repeat(41))) {
            mvc.perform(admin(put(REGIONS + "/{code}", code).contentType(MediaType.APPLICATION_JSON)
                    .content(body("지역", 0, false)))).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select name from map_region where code='AREA'", String.class)).isEqualTo("유지");
        mvc.perform(admin(get(REGIONS).param("size", "101"))).andExpect(status().isBadRequest());
        mvc.perform(admin(get(REGIONS).param("page", "-1"))).andExpect(status().isBadRequest());
    }

    @Test
    void 미설정_환경에서는_초안만_저장하고_활성화가_실패해도_기존_초안을_보존한다() throws Exception {
        var original = queryProperties.getSupportedBounds();
        try {
            queryProperties.setSupportedBounds(new MapQueryProperties.Bounds());
            upsert("DRAFT", body("초안", 0, false));
            mvc.perform(admin(put(REGIONS + "/DRAFT").contentType(MediaType.APPLICATION_JSON)
                    .content(body("공개", 0, true)))).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RESTAURANT-017"));
            assertThat(jdbc.queryForObject("select name from map_region where code='DRAFT'", String.class)).isEqualTo("초안");
        } finally {
            queryProperties.setSupportedBounds(original);
        }
        String tooWide = body("너무 넓음", 0, true).replace("\"north\":1", "\"north\":2");
        mvc.perform(admin(put(REGIONS + "/WIDE").contentType(MediaType.APPLICATION_JSON).content(tooWide)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESTAURANT-011"));
    }

    @Test
    void 동시_생성도_한_ID이고_동시_수정은_요청의_전체_필드를_함께_저장한다() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<JsonNode>>();
            for (int index = 0; index < 4; index++) {
                final int number = index;
                results.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return upsert("RACE", body("요청" + number, number, number % 2 == 0));
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (var result : results) {
                ids.add(result.get(20, TimeUnit.SECONDS).path("mapRegionId").asLong());
            }
            assertThat(ids.stream().distinct()).hasSize(1);
        }
        Map<String, Object> stored = jdbc.queryForMap("select name, display_order, active from map_region where code='RACE'");
        int order = ((Number) stored.get("display_order")).intValue();
        assertThat(stored.get("name")).isEqualTo("요청" + order);
        assertThat(stored.get("active")).isEqualTo(order % 2 == 0);
        assertThat(jdbc.queryForObject("select count(*) from map_region where code='RACE'", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 대기하던_소속_지정은_앞선_변경을_이어서_반영하되_삭제된_식당은_거절한다(boolean deleted) throws Exception {
        long id = fixture();
        long region = upsert("AREA", body("지역", 0, false)).path("mapRegionId").asLong();
        long previous = upsert("PREVIOUS", body("이전 지역", 1, false)).path("mapRegionId").asLong();
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Long> attempt = transactions.execute(status -> {
                Restaurant locked = restaurants.findByIdForUpdate(id).orElseThrow();
                var future = executor.submit(() -> port.assignMapRegionByAdmin(id, region));
                awaitRestaurantLock();
                if (deleted) {
                    locked.softDelete();
                } else {
                    locked.assignMapRegion(previous);
                }
                return future;
            });
            if (deleted) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(org.sopt.hashi.shared.error.BusinessException.class);
                assertThat(jdbc.queryForObject("select map_region_id from restaurant where id=?", Long.class, id)).isNull();
            } else {
                assertThat(attempt.get(10, TimeUnit.SECONDS)).isEqualTo(region);
                assertThat(jdbc.queryForObject("select map_region_id from restaurant where id=?", Long.class, id))
                        .isEqualTo(region);
            }
        }
    }

    private void awaitRestaurantLock() {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                        JOIN performance_schema.data_locks requested
                          ON requested.engine = waits.engine
                         AND requested.engine_lock_id = waits.requesting_engine_lock_id
                        WHERE requested.object_schema = DATABASE() AND requested.object_name = 'restaurant'
                        """)) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(25);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        throw new AssertionError("식당 row lock 대기를 제한 시간 안에 관찰하지 못했습니다.");
    }

    private long fixture() {
        return transactions.execute(status -> {
            Restaurant row = Restaurant.create("합성 식당", "fixture", "요약", "설명", "합성 주소", "지역",
                    RestaurantGenre.SUSHI, "초밥", RestaurantPlaceType.RESTAURANT,
                    PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
            row.requestLocationResolution();
            LocalDateTime now = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
            row.completeLocation(1, row.getLocation().getRequestId(), MapCoordinates.of(new BigDecimal(".5"),
                    new BigDecimal(".5")), RestaurantLocationSource.ADMIN, now.minusHours(1), now.plusDays(1), CLOCK);
            return restaurants.saveAndFlush(row).getId();
        });
    }

    private String body(String name, int order, boolean active) {
        return """
                {"name":"%s","clusterPosition":{"latitude":0.5,"longitude":0.5},
                "cameraBounds":{"south":0,"north":1,"west":0,"east":1},"displayOrder":%d,"active":%s}
                """.formatted(name, order, active);
    }

    private JsonNode upsert(String code, String body) throws Exception {
        return success(admin(put(REGIONS + "/{code}", code).contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    private void assign(long id, Long region) throws Exception {
        JsonNode result = success(admin(put(ASSIGN, id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mapRegionId\":" + region + "}")));
        assertThat(result.path("restaurantId").asLong()).isEqualTo(id);
        if (region == null) {
            assertThat(result.get("mapRegionId").isNull()).isTrue();
        } else {
            assertThat(result.path("mapRegionId").asLong()).isEqualTo(region);
        }
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(1L, "ROLE_ADMIN"));
    }

    private MockHttpServletRequestBuilder query(Long region) {
        var request = get("/api/v1/restaurants/map").param("south", "0").param("north", "1")
                .param("west", "0").param("east", "1");
        return region == null ? request : request.param("mapRegionId", region.toString());
    }

    private JsonNode success(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andReturn().getResponse().getContentAsString()).path("data");
    }

}
