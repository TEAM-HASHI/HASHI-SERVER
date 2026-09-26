package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.security.JwtAccessDeniedHandler;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationEntryPoint;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.auth.internal.security.SecurityConfig;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.config.RedisConfig;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.MapBounds;
import org.sopt.hashi.restaurant.domain.MapRegion;
import org.sopt.hashi.restaurant.domain.MapRegionRepository;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantBusinessHour;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.MapCursorCodec;
import org.sopt.hashi.restaurant.internal.map.MapQuerySession;
import org.sopt.hashi.restaurant.internal.map.MapSessionId;
import org.sopt.hashi.restaurant.internal.map.MapSessionProperties;
import org.sopt.hashi.restaurant.internal.map.RedisMapSessionStore;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ApplicationModuleTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, extraIncludes = "shared")
@AutoConfigureMockMvc
@Import({RestaurantMapPageIntegrationTest.Infrastructure.class, SecurityConfig.class,
        JwtAuthenticationFilter.class, JwtProvider.class, CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m", "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.cors.allowed-origins=https://app.hashi.test", "springdoc.api-docs.enabled=false",
        "hashi.restaurant.map.initial-bounds.south=0", "hashi.restaurant.map.initial-bounds.north=1",
        "hashi.restaurant.map.initial-bounds.west=0", "hashi.restaurant.map.initial-bounds.east=1",
        "hashi.restaurant.map.supported-bounds.south=-1", "hashi.restaurant.map.supported-bounds.north=2",
        "hashi.restaurant.map.supported-bounds.west=-1", "hashi.restaurant.map.supported-bounds.east=2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantMapPageIntegrationTest {
    private static final Clock CLOCK = Clock.system(ZoneId.of("Asia/Tokyo"));
    private static final String PATH = "/api/v1/restaurants/map";
    private static final String PREFIX = "hashi:restaurant:map:{sessions-v1}:slot:";
    private static final MapSearchCriteria CRITERIA = MapSearchCriteria.of(
            MapQueryBounds.parse("0", "1", "0", "1"), null, "sushi", "restaurant", "fixture");

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("map_pages").withUsername("hashi").withPassword("hashi")
            .withUrlParam("serverTimezone", "Asia/Seoul");
    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RestaurantRepository restaurants;
    @Autowired MapRegionRepository regions;
    @Autowired RestaurantMapPageService pages;
    @Autowired RestaurantMapService mapService;
    @Autowired RestaurantPort port;
    @Autowired RedisMapSessionStore store;
    @Autowired MapCursorCodec cursors;
    @Autowired MapSessionProperties properties;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean MediaPort media;
    @MockitoBean FileStorage files;
    @MockitoBean OnboardingTokenStore onboardingTokens;

    @BeforeEach
    void resetSyntheticData() {
        jdbc.update("update restaurant set deleted=true");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        properties.setSigningKey(Base64.getEncoder().encodeToString("synthetic-map-test-key-32-bytes-only".getBytes()));
        given(files.resolveFileUrl(any())).willAnswer(invocation -> "https://cdn.hashi.test/" + invocation.getArgument(0));
        given(media.findImages(any())).willReturn(Map.of());
    }

    @Test
    void 기본OSIV에서_지도목록만_연결을_분리하고_일반API는_기존OSIV를_유지한다() throws Exception {
        fixtures(11, false);
        mvc.perform(newQuery()).andExpect(status().isOk())
                .andExpect(request().attribute("test.osiv.bound", false));
        mvc.perform(get("/api/v1/restaurants")).andExpect(status().isOk())
                .andExpect(request().attribute("test.osiv.bound", true));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10, 11, 21})
    void 실제_HTTP에서_10개씩_빠짐없이_반환하고_마지막_cursor를_생략한다(int count) throws Exception {
        List<Long> expected = fixtures(count, false);
        JsonNode data = page(newQuery());
        List<Long> result = new ArrayList<>();
        String session = data.get("querySessionId").asText();
        do {
            assertThat(data.get("content").size()).isLessThanOrEqualTo(10);
            result.addAll(ids(data));
            assertThat(data.get("querySessionId").asText()).isEqualTo(session);
            if (!data.get("hasNext").asBoolean()) {
                assertThat(data.has("nextCursor")).isFalse();
                break;
            }
            assertThat(data.get("content").size()).isEqualTo(10);
            data = page(get(PATH).param("cursor", data.get("nextCursor").asText()));
        } while (true);
        assertThat(result).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(Instant.parse(data.get("expiresAt").asText()))
                .isAfter(Instant.parse(data.get("rankingAsOf").asText()));
        assertThat(data.get("query").get("sort").asText()).isEqualTo("recommend");
    }

    @Test
    void 동점과_최신통계변경에도_최초추천_별점_리뷰순위가_고정된다() throws Exception {
        fixtures(21, false);
        JsonNode first = page(newQuery());
        String session = first.get("querySessionId").asText();
        var snapshot = store.find(MapSessionId.parse(session));
        jdbc.update("update restaurant set rating=5, review_count=100 where deleted=false");
        for (String sort : List.of("rating", "reviews", "recommend", "rating")) {
            JsonNode sorted = page(get(PATH).param("querySessionId", session).param("sort", sort));
            assertThat(ids(sorted)).isEqualTo(ids(first));
            assertThat(sorted.get("expiresAt")).isEqualTo(first.get("expiresAt"));
            sorted.get("content").forEach(card -> {
                assertThat(card.get("rating").decimalValue()).isEqualByComparingTo("0.0");
                assertThat(card.get("reviewCount").asLong()).isZero();
            });
        }
        assertThat(store.find(MapSessionId.parse(session))).isEqualTo(snapshot);
    }

    @Test
    void 다른_별점과_리뷰수는_내림차순이며_동점은_원래추천순이다() throws Exception {
        List<Long> ids = fixtures(21, false);
        for (int index = 0; index < ids.size(); index++) {
            jdbc.update("update restaurant set rating=?, review_count=? where id=?", index % 5, index % 3, ids.get(index));
        }
        var first = page(newQuery());
        String sessionId = first.get("querySessionId").asText();
        var session = store.find(MapSessionId.parse(sessionId));
        for (var sort : List.of(RestaurantMapSort.RATING, RestaurantMapSort.REVIEWS)) {
            var sorted = page(get(PATH).param("querySessionId", sessionId).param("sort", sort.value()));
            assertThat(ids(sorted)).containsExactlyElementsOf(session.ordered(sort).subList(0, 10).stream()
                    .map(RestaurantMapCandidate::restaurantId).toList());
        }
    }

    @Test
    void 앞뒤의_삭제_만료_주소_분류_검색변경을_건너뛰고_lookahead를_보존한다() throws Exception {
        List<Long> ids = fixtures(30, false);
        var session = orderedSession(Duration.ofMinutes(15));
        String token = cursors.encode(session, RestaurantMapSort.RECOMMEND, 5);
        jdbc.update("update restaurant set deleted=true where id in (?,?)", ids.get(0), ids.get(5));
        jdbc.update("update restaurant_location set valid_until=UTC_TIMESTAMP(6) where id=(select location_id from restaurant where id=?)", ids.get(6));
        new TransactionTemplate(transactions).executeWithoutResult(status -> restaurants.findById(ids.get(7)).orElseThrow()
                .updateBasicInfo(null, null, null, null, "changed fixture address", null, null, null, null, null, null, null));
        jdbc.update("update restaurant set place_type='CAFE' where id=?", ids.get(8));
        jdbc.update("update restaurant set genre='NOODLE' where id=?", ids.get(9));
        jdbc.update("update restaurant set name='changed' where id=?", ids.get(10));
        var page = page(get(PATH).param("cursor", token));
        assertThat(ids(page)).containsExactlyElementsOf(ids.subList(11, 21));
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        String next = page.get("nextCursor").asText();
        assertThat(cursors.decode(next).position()).isEqualTo(21);
        jdbc.update("update restaurant set deleted=false where id=?", ids.get(0));
        fixtures(1, false);
        var last = page(get(PATH).param("cursor", next));
        assertThat(ids(last)).containsExactlyElementsOf(ids.subList(21, 30));
        assertThat(last.has("nextCursor")).isFalse();
        jdbc.update("update restaurant set deleted=true where id>=?", ids.get(21));
        var nowEmpty = page(get(PATH).param("cursor", next));
        assertThat(ids(nowEmpty)).isEmpty();
        assertThat(nowEmpty.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void 같은_cursor의_반복과_동시_HTTP는_전역위치를_움직이지_않는다() throws Exception {
        fixtures(21, false);
        JsonNode first = page(newQuery());
        String token = first.get("nextCursor").asText();
        JsonNode expected = page(get(PATH).param("cursor", token));
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<JsonNode>> calls = IntStream.range(0, 12)
                    .mapToObj(index -> (Callable<JsonNode>) () -> page(get(PATH).param("cursor", token))).toList();
            for (var result : executor.invokeAll(calls)) {
                assertThat(result.get()).isEqualTo(expected);
            }
        }
        assertThat(page(get(PATH).param("cursor", token))).isEqualTo(expected);
    }

    @Test
    void 실제TTL은_정렬로_연장되지_않으며_유실과_만료는_410이다() throws Exception {
        fixtures(1, false);
        var session = orderedSession(Duration.ofSeconds(2));
        long ttl = redis.getExpire(PREFIX + session.slot(), TimeUnit.MILLISECONDS);
        Thread.sleep(150);
        page(get(PATH).param("querySessionId", session.value()).param("sort", "rating"));
        assertThat(redis.getExpire(PREFIX + session.slot(), TimeUnit.MILLISECONDS)).isLessThan(ttl);
        Thread.sleep(2000);
        mvc.perform(get(PATH).param("querySessionId", session.value()).param("sort", "reviews"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("RESTAURANT-013"));
        var lost = orderedSession(Duration.ofMinutes(15));
        redis.delete(PREFIX + lost.slot());
        mvc.perform(get(PATH).param("querySessionId", lost.value()).param("sort", "recommend"))
                .andExpect(status().isGone());
    }

    @Test
    void 현재좌표_가격_이미지_영업시간을_일괄조회하며_식당수가_늘어도_쿼리수가_늘지_않는다() throws Exception {
        var ids = fixtures(10, true);
        // 같은 JDBC 연결의 Asia/Seoul과 무관하게 UTC 원문을 조회한다.
        jdbc.update("update restaurant_location set valid_until=UTC_TIMESTAMP(6) + interval 1 hour where status='READY'");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        clearInvocations(media);
        var ten = page(newQuery());
        long tenQueries = statistics.getPrepareStatementCount();
        assertThat(ten.get("content")).hasSize(10);
        var card = ten.get("content").get(0);
        assertThat(card.get("priceRange").get("currency").asText()).isEqualTo("JPY");
        assertThat(card.get("priceRange").get("minPrice").asLong()).isEqualTo(100);
        assertThat(card.get("priceRange").get("maxPrice").asLong()).isEqualTo(999);
        assertThat(card.get("placeType").asText()).isEqualTo("restaurant");
        assertThat(card.get("imageUrls")).hasSize(1);
        assertThat(card.get("cardImages")).hasSize(2);
        assertThat(card.get("todayBusinessHour").get("openTime").asText()).isEqualTo("10:00");
        assertThat(Instant.parse(card.get("location").get("validUntil").asText())).isAfter(Instant.now());
        assertThat(card.has("isSaved")).isFalse();
        assertThat(card.has("savedCount")).isFalse();
        verify(media, times(1)).findImages(any());
        jdbc.update("update restaurant set deleted=true where id<>?", ids.getFirst());
        statistics.clear();
        var one = page(newQuery());
        long oneQueries = statistics.getPrepareStatementCount();
        assertThat(one.get("content")).hasSize(1);
        assertThat(tenQueries).isEqualTo(oneQueries).isLessThanOrEqualTo(7);
        System.out.println("MAP_CARD_QUERIES one=" + oneQueries + " ten=" + tenQueries + " mediaBulk=1");
        jdbc.update("update restaurant_location set valid_until=UTC_TIMESTAMP(6) where status='READY'");
        assertThat(page(newQuery()).get("content")).isEmpty();
        assertThat(port.findActiveMapInfos(List.of(ids.getFirst()))).hasSize(1)
                .allSatisfy(info -> assertThat(info.location()).isNull());
    }

    @Test
    void 후보500개_초과는_잘라내지_않고_저장전_503이다() throws Exception {
        fixtures(501, false);
        mvc.perform(newQuery()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESTAURANT-016"));
        assertThat(redis.keys(PREFIX + "*")).isEmpty();
    }

    @Test
    void 키_누락은_지도세션만_503이며_기존목록과_보안경로는_그대로이다() throws Exception {
        activeRegion();
        properties.setSigningKey(null);
        mvc.perform(newQuery()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESTAURANT-014"));
        mvc.perform(get("/api/v1/restaurants")).andExpect(status().isOk());
        mvc.perform(get(PATH + "/regions")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/restaurants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 지역매핑과_BBOX_변경을_재검사하고_현재비활성지역은_거절한다() throws Exception {
        List<Long> ids = fixtures(12, false);
        Long region = activeRegion();
        jdbc.update("update restaurant set map_region_id=? where deleted=false", region);
        var query = MapSearchCriteria.of(CRITERIA.bounds(), region, "sushi", "restaurant", "fixture");
        var snapshot = mapService.findCandidates(query, 500);
        var id = store.save(new MapQuerySession(1, UUID.randomUUID(), query, snapshot.candidates(),
                snapshot.rankingAsOf(), Instant.now().plusSeconds(900)));
        jdbc.update("update restaurant set map_region_id=null where id=?", ids.get(0));
        jdbc.update("update restaurant_location set latitude=1.5 where id=(select location_id from restaurant where id=?)", ids.get(1));
        var result = page(get(PATH).param("querySessionId", id.value()).param("sort", "recommend"));
        assertThat(ids(result)).containsExactlyElementsOf(ids.subList(2, 12));
        assertThat(result.get("hasNext").asBoolean()).isFalse();
        jdbc.update("update map_region set active=false where id=?", region);
        mvc.perform(get(PATH).param("querySessionId", id.value()).param("sort", "recommend"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESTAURANT-012"));
    }

    @Test
    void 실제Redis_읽기쓰기실패와_DB실패를_HTTP503으로_구분한다() throws Exception {
        fixtures(1, false);
        var id = orderedSession(Duration.ofMinutes(15));
        try {
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "1");
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory-policy", "noeviction");
            mvc.perform(newQuery()).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RESTAURANT-014"));
        } finally {
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0");
        }
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            mvc.perform(get(PATH).param("querySessionId", id.value()).param("sort", "rating"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RESTAURANT-014"));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        jdbc.execute("rename table restaurant to restaurant_map_test_unavailable");
        try {
            mvc.perform(newQuery()).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RESTAURANT-015"));
        } finally {
            jdbc.execute("rename table restaurant_map_test_unavailable to restaurant");
        }
    }

    @Test
    void 상위DB_transaction이_열려있으면_Redis_호출_전에_거절한다() {
        fixtures(1, false);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                pages.getPage(new org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest(
                        CRITERIA, RestaurantMapSort.RECOMMEND, null, null))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(redis.keys(PREFIX + "*")).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 잘못된_HTTP_파라미터와_변조cursor는_400이고_민감값을_노출하지_않는다(CapturedOutput output) throws Exception {
        for (String key : List.of("south", "unknown", "size", "cursor", "querySessionId")) {
            mvc.perform(newQuery().param(key, "private-fixture"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
        mvc.perform(get(PATH).param("cursor", "x".repeat(513))).andExpect(status().isBadRequest());
        String token = cursors.encode(new MapSessionId(0, UUID.randomUUID()), RestaurantMapSort.RECOMMEND, 10);
        String tampered = (token.startsWith("A") ? "B" : "A") + token.substring(1);
        String body = mvc.perform(get(PATH).param("cursor", tampered)).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(tampered, "signingKey", "private-fixture");
        assertThat(output.getAll()).doesNotContain(tampered, "private-fixture", "synthetic-map-test-key-32-bytes-only");
    }

    private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("data");
    }

    private MockHttpServletRequestBuilder newQuery() {
        return get(PATH).param("south", "0").param("north", "1").param("west", "0").param("east", "1");
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(card -> ids.add(card.get("restaurantId").asLong()));
        return ids;
    }

    private MapSessionId orderedSession(Duration ttl) {
        var snapshot = mapService.findCandidates(CRITERIA, 500);
        return store.save(new MapQuerySession(1, UUID.randomUUID(), CRITERIA, snapshot.candidates(),
                snapshot.rankingAsOf(), Instant.now().plus(ttl)));
    }

    private Long activeRegion() {
        return new TransactionTemplate(transactions).execute(status -> {
            var region = MapRegion.create("FIXTURE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                    "합성 지역", MapCoordinates.of(new BigDecimal(".5"), new BigDecimal(".5")),
                    MapBounds.of(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE), 0);
            region.activate();
            return regions.save(region).getId();
        });
    }

    private List<Long> fixtures(int count, boolean cards) {
        return new TransactionTemplate(transactions).execute(status -> IntStream.range(0, count).mapToObj(index -> {
            Restaurant restaurant = Restaurant.create("fixture " + index, "fixture", "fixture", "fixture",
                    "synthetic address", "fixture", RestaurantGenre.SUSHI, "fixture", RestaurantPlaceType.RESTAURANT,
                    PriceCurrency.JPY, new BigDecimal("100.75"), new BigDecimal("999.99"));
            restaurant.requestLocationResolution();
            LocalDateTime now = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
            restaurant.completeLocation(1, restaurant.getLocation().getRequestId(),
                    MapCoordinates.of(new BigDecimal(".5"), new BigDecimal(".5")), RestaurantLocationSource.OPERATOR,
                    now.minusHours(1), now.plusHours(1), CLOCK);
            if (cards) {
                restaurant.addImage(RestaurantImage.createLegacy("fixture/image" + index, 1));
                restaurant.addImage(RestaurantImage.createAsset(UUID.randomUUID(), 2));
                restaurant.addMenu(RestaurantMenu.create("menu", "fixture", null, PriceCurrency.JPY, BigDecimal.ONE, true));
                restaurant.replaceHashtags(List.of("fixture"));
                restaurant.addBusinessHour(RestaurantBusinessHour.create(LocalDate.now(CLOCK).getDayOfWeek(),
                        LocalTime.of(10, 0), LocalTime.of(21, 0), null, null, false));
            }
            return restaurants.save(restaurant).getId();
        }).toList());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class Infrastructure {
        // ModuleTest가 root config의 BeanDefinition을 제거하므로 같은 factory 메서드로 연결한다.
        @Bean
        RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connections) {
            return new RedisConfig().redisTemplate(connections);
        }

        @Bean
        LettuceClientOptionsBuilderCustomizer socketOptions(RedisProperties properties) {
            return new RedisConfig().lettuceSocketOptionsCustomizer(properties);
        }

        /** 실제 adapter 경계의 resource와 최초 저장 시 물리 pool 연결 반환을 관측한다. */
        @Bean
        static BeanPostProcessor observeRedisTransactionBoundary(ConfigurableListableBeanFactory factory) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof RedisMapSessionStore)) {
                        return bean;
                    }
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        if (invocation.getMethod().getName().equals("save")) {
                            var pool = factory.getBean(DataSource.class).unwrap(HikariDataSource.class);
                            assertThat(pool.getHikariPoolMXBean().getActiveConnections())
                                    .as("Redis save must not retain a physical DB connection").isZero();
                        }
                        assertThat(TransactionSynchronizationManager.getResourceMap().values())
                                .noneMatch(EntityManagerHolder.class::isInstance);
                        return invocation.proceed();
                    });
                    return proxy.getProxy();
                }
            };
        }

        @Bean
        WebMvcConfigurer observeRequestPersistenceContext(EntityManagerFactory factory) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                                 Object handler) {
                            request.setAttribute("test.osiv.bound", TransactionSynchronizationManager.hasResource(factory));
                            return true;
                        }
                    }).order(Ordered.LOWEST_PRECEDENCE);
                }
            };
        }

        @Bean
        static BeanFactoryPostProcessor excludeBackfillAttachment() {
            return factory -> ((BeanDefinitionRegistry) factory).removeBeanDefinition("restaurantMediaBackfillAttachmentService");
        }

        @Bean("japanClock")
        Clock clock() {
            return CLOCK;
        }
    }
}
