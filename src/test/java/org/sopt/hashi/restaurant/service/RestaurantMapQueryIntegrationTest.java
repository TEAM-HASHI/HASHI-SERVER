package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapBounds;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapRegion;
import org.sopt.hashi.restaurant.domain.MapRegionRepository;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapQueryRepository;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.dto.RestaurantMapRegionsResponse.RegionResponse;
import org.sopt.hashi.restaurant.internal.map.MapQueryProperties;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ApplicationModuleTest
@Import(RestaurantMapQueryIntegrationTest.Infrastructure.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantMapQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDateTime UTC_NOW = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final MapQueryBounds BOUNDS = MapQueryBounds.parse("0", "1", "0", "1");
    private static final List<String> SQL = new CopyOnWriteArrayList<>();

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_map_query").withUsername("hashi").withPassword("hashi")
            .withUrlParam("serverTimezone", "Asia/Seoul");

    @Autowired private RestaurantMapService service;
    @Autowired private RestaurantMapQueryRepository queries;
    @Autowired private RestaurantRepository restaurants;
    @Autowired private MapRegionRepository regions;
    @Autowired private RestaurantPort port;
    @Autowired private MapQueryProperties properties;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MediaPort mediaPort;
    @MockitoBean private FileStorage fileStorage;
    @MockitoBean private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void configureSyntheticBounds() {
        properties.setInitialBounds(propertyBounds("0", "1"));
        properties.setSupportedBounds(propertyBounds("-1", "2"));
    }

    @Test
    void 실제_MySQL에서_네_경계를_포함하며_정상_0좌표와_미분류도_반환한다() {
        List<Long> expected = List.of(ready("south", "0", ".5").getId(),
                ready("north", "1", ".5").getId(), ready("west", ".5", "0").getId(),
                ready("east", ".5", "1").getId(), ready("origin", "0", "0").getId());
        ready("outside", "1.000001", ".5");
        flushAndReset();
        var snapshot = service.findCandidates(criteria(null), 20);
        assertThat(snapshot.candidates()).extracting(RestaurantMapCandidate::restaurantId).containsExactlyElementsOf(expected);
        assertThat(snapshot.rankingAsOf()).isEqualTo(NOW);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics().getEntityLoadCount()).isZero();
        verifyNoInteractions(mediaPort, fileStorage);
    }

    @Test
    void 긴_소수점_입력은_DB에서도_그대로_비교하고_경계를_반올림하지_않는다() {
        ready("zero", "0", "0");
        Long inside = ready("micro", ".000001", ".000001").getId();
        ready("one", "1", "1");
        flushAndReset();
        var query = MapSearchCriteria.of(MapQueryBounds.parse("0.000000000001", "0.999999999999",
                "0.000000000001", "0.999999999999"), null, null, null, null);
        assertThat(service.findCandidates(query, 10).candidates())
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(inside);
    }

    @Test
    void 후보_평점과_리뷰수는_조회시점의_값이며_이후_DB변경이_반환값을_바꾸지_않는다() {
        Long id = ready("statistics", ".5", ".5").getId();
        entityManager.flush();
        jdbc.update("update restaurant set rating_sum=9, review_count=2, rating=4.5 where id=?", id);
        flushAndReset();
        var snapshot = service.findCandidates(criteria(null), 10);
        assertThat(snapshot.candidates().getFirst().rating()).isEqualByComparingTo("4.5");
        assertThat(snapshot.candidates().getFirst().reviewCount()).isEqualTo(2);
        jdbc.update("update restaurant set rating_sum=12, review_count=3, rating=4.0 where id=?", id);
        var current = service.findMatchingCandidates(criteria(null), List.of(id)).getFirst();
        assertThat(current.rating()).isEqualByComparingTo("4.0");
        assertThat(current.reviewCount()).isEqualTo(3);
        assertThat(snapshot.candidates().getFirst().rating()).isEqualByComparingTo("4.5");
        assertThat(snapshot.candidates().getFirst().reviewCount()).isEqualTo(2);
    }

    @Test
    void DB의_UTC_DATETIME을_연결_시간대와_무관하게_조회하고_직렬화한다() {
        Long id = ready("UTC storage", ".5", ".5").getId();
        entityManager.flush();
        jdbc.update("""
                update restaurant_location set obtained_at='2025-12-31 23:00:00.000000',
                valid_until='2026-01-01 01:00:00.123456'
                where id=(select location_id from restaurant where id=?)
                """, id);
        flushAndReset();
        assertThat(service.findCandidates(criteria(null), 10).candidates())
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(id);
        assertThat(port.findActiveMapInfos(List.of(id)).getFirst().location().validUntil())
                .isEqualTo(Instant.parse("2026-01-01T01:00:00.123456Z"));
    }

    @Test
    void 삭제_위치없음_미준비_주소변경_만료경계는_후보에서_제외한다() {
        Restaurant deleted = ready("deleted", ".5", ".5");
        deleted.softDelete();
        restaurants.save(restaurant("no location", RestaurantGenre.SUSHI, RestaurantPlaceType.RESTAURANT));
        Restaurant pending = restaurant("pending", RestaurantGenre.SUSHI, RestaurantPlaceType.RESTAURANT);
        pending.requestLocationResolution();
        restaurants.save(pending);
        Restaurant rejected = restaurant("rejected", RestaurantGenre.SUSHI, RestaurantPlaceType.RESTAURANT);
        rejected.requestLocationResolution();
        rejected.rejectLocation(1, rejected.getLocation().getRequestId(), RestaurantLocationStatus.REVIEW_REQUIRED);
        restaurants.save(rejected);
        Restaurant changed = ready("changed", ".5", ".5");
        changed.updateBasicInfo(null, null, null, null, "new synthetic address", null, null, null, null, null, null, null);
        Restaurant expired = ready("boundary", ".5", ".5");
        Long fresh = ready("micro future", ".5", ".5").getId();
        entityManager.flush();
        jdbc.update("update restaurant_location set valid_until=? where id=?", UTC_NOW, expired.getLocation().getId());
        jdbc.update("update restaurant_location set valid_until=? where id=(select location_id from restaurant where id=?)",
                UTC_NOW.plusNanos(1000), fresh);
        flushAndReset();
        assertThat(service.findCandidates(criteria(null), 10).candidates())
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(fresh);
    }

    @Test
    void 식당명과_여러_메뉴가_함께_맞아도_한번만_반환하고_대소문자를_구분하지_않는다() {
        Restaurant match = ready("SuShI house", ".5", ".5");
        match.addMenu(menu("sushi first"));
        match.addMenu(menu("SUSHI second"));
        Restaurant menuOnly = ready("menu only", ".5", ".5");
        menuOnly.addMenu(menu("sushi menu"));
        ready("unrelated", ".5", ".5");
        flushAndReset();
        assertThat(service.findCandidates(criteria("  sUsHi  "), 2).candidates())
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(match.getId(), menuOnly.getId());
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics().getEntityLoadCount()).isZero();
    }

    @Test
    void 퍼센트_밑줄_escape_백슬래시는_리터럴로_검색한다() {
        Restaurant literal = ready("100%_!\\ hit", ".5", ".5");
        Restaurant menuLiteral = ready("menu", ".5", ".5");
        menuLiteral.addMenu(menu("100%_!\\ menu"));
        ready("100ANY!\\ false", ".5", ".5");
        flushAndReset();
        assertThat(service.findCandidates(criteria("%_!\\"), 10).candidates())
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(literal.getId(), menuLiteral.getId());
    }

    @Test
    void 지역_ID와_장르_음식점분류_검색어를_함께_적용한다() {
        MapRegion region = activeRegion("FILTER", 0);
        Restaurant chosen = ready("chosen", ".5", ".5");
        chosen.assignMapRegion(region.getId());
        Restaurant otherGenre = ready("chosen noodle", ".5", ".5");
        otherGenre.assignMapRegion(region.getId());
        otherGenre.updateBasicInfo(null, null, null, null, null, null, RestaurantGenre.NOODLE,
                null, null, null, null, null);
        Restaurant otherType = ready("chosen cafe", ".5", ".5");
        otherType.assignMapRegion(region.getId());
        otherType.updateBasicInfo(null, null, null, null, null, null, null, null, RestaurantPlaceType.CAFE, null, null, null);
        ready("chosen unclassified", ".5", ".5");
        flushAndReset();
        var filter = MapSearchCriteria.of(BOUNDS, region.getId(), "sushi", "restaurant", "chosen");
        assertThat(service.findCandidates(filter, 10).candidates())
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(chosen.getId());
    }

    @Test
    void 없는_지역과_비활성_지역은_400이며_빈_유효검색은_성공이다() {
        MapRegion inactive = regions.saveAndFlush(MapRegion.create("INACTIVE", "합성 지역", point(".5", ".5"), bounds(), 0));
        for (long id : List.of(inactive.getId(), Long.MAX_VALUE)) {
            assertCode(() -> service.findCandidates(MapSearchCriteria.of(BOUNDS, id, null, null, null), 10),
                    RestaurantErrorCode.MAP_REGION_INVALID);
        }
        assertThat(service.findCandidates(criteria(null), 10).candidates()).isEmpty();
    }

    @Test
    void 후보는_상한_플러스_하나로_초과를_감지하고_잘린_성공을_반환하지_않는다() {
        ready("first", ".5", ".5");
        ready("second", ".5", ".5");
        ready("third", ".5", ".5");
        flushAndReset();
        assertThat(queries.findCandidates(criteria(null), NOW, 2)).hasSize(2);
        assertCode(() -> service.findCandidates(criteria(null), 2), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        assertThat(service.findCandidates(criteria(null), 3).candidates()).hasSize(3);
        assertThat(SQL).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("limit"));
    }

    @Test
    void 지정_ID를_같은_조건으로_재검사해_순서와_중복제거를_유지한다() {
        Restaurant first = ready("match first", ".5", ".5");
        Restaurant second = ready("match second", ".5", ".5");
        Restaurant removed = ready("match removed", ".5", ".5");
        service.findCandidates(criteria("match"), 10);
        removed.softDelete();
        first.updateBasicInfo("renamed", null, null, null, null, null, null, null, null, null, null, null);
        Restaurant newMatch = ready("match new", ".5", ".5");
        flushAndReset();
        assertThat(service.findMatchingCandidates(criteria("match"),
                List.of(second.getId(), first.getId(), removed.getId(), second.getId())))
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(second.getId());
        assertThat(service.findMatchingCandidates(criteria("match"), List.of(newMatch.getId(), second.getId())))
                .extracting(RestaurantMapCandidate::restaurantId).containsExactly(newMatch.getId(), second.getId());
    }

    @Test
    void 관광지역은_0건도_표시순서와_ID순서로_반환하며_같은_유효조건을_집계한다() {
        MapRegion later = activeRegion("LATER", 2);
        MapRegion first = activeRegion("FIRST", 0);
        MapRegion tied = activeRegion("TIED", 0);
        Restaurant valid = ready("valid", "0", "0");
        valid.assignMapRegion(first.getId());
        Restaurant deleted = ready("deleted", ".5", ".5");
        deleted.assignMapRegion(first.getId());
        deleted.softDelete();
        Restaurant expired = ready("expired", ".5", ".5");
        expired.assignMapRegion(first.getId());
        ready("unclassified", ".5", ".5");
        entityManager.flush();
        jdbc.update("update restaurant_location set valid_until=? where id=?", UTC_NOW, expired.getLocation().getId());
        flushAndReset();
        var response = service.getRegions();
        assertThat(response.regions()).extracting(RegionResponse::mapRegionId)
                .containsExactly(first.getId(), tied.getId(), later.getId());
        assertThat(response.regions()).extracting(RegionResponse::restaurantCount).containsExactly(1L, 0L, 0L);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics().getEntityLoadCount()).isZero();
        assertThat(service.findCandidates(MapSearchCriteria.of(BOUNDS, first.getId(), null, null, null), 10).candidates())
                .hasSize(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 지역_범위밖_매핑은_count에서_제외하되_다른_지역과_유효count를_반환하고_이상건수를_기록한다(CapturedOutput output) {
        MapRegion region = activeRegion("MISMATCH", 0);
        MapRegion other = activeRegion("OTHER", 1);
        Restaurant valid = ready("inside", ".5", ".5");
        valid.assignMapRegion(region.getId());
        Restaurant outside = ready("outside", "1.1", ".5");
        outside.assignMapRegion(region.getId());
        flushAndReset();
        var summary = queries.findActiveRegions(NOW).getFirst();
        assertThat(summary.restaurantCount()).isEqualTo(1);
        assertThat(summary.outsideBoundsCount()).isEqualTo(1);
        var response = service.getRegions();
        assertThat(response.regions()).extracting(RegionResponse::mapRegionId).containsExactly(region.getId(), other.getId());
        assertThat(response.regions()).extracting(RegionResponse::restaurantCount).containsExactly(1L, 0L);
        assertThat(output).contains("Map region mapping outside bounds: regionId=" + region.getId() + ", count=1");
    }

    @Test
    void 실제_지역_seed가_없거나_지역_화면이_제한을_넘으면_503이다() {
        assertCode(service::getRegions, RestaurantErrorCode.MAP_CONFIGURATION_UNAVAILABLE);
        MapRegion oversized = MapRegion.create("OVERSIZED", "합성 지역", point(".5", ".5"),
                MapBounds.of(BigDecimal.ZERO, new BigDecimal("1.1"), BigDecimal.ZERO, BigDecimal.ONE), 0);
        oversized.activate();
        regions.saveAndFlush(oversized);
        assertCode(service::getRegions, RestaurantErrorCode.MAP_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void 공개_Port는_삭제만_제외하고_위치없는_식당과_만료식당은_null_위치로_남긴다() {
        Restaurant ready = ready("ready", "0", "0");
        Restaurant missing = restaurants.save(restaurant("missing", RestaurantGenre.ETC, RestaurantPlaceType.CAFE));
        Restaurant expired = ready("expired", ".5", ".5");
        Restaurant deleted = ready("deleted", ".5", ".5");
        deleted.softDelete();
        entityManager.flush();
        jdbc.update("update restaurant_location set valid_until=? where id=?", UTC_NOW, expired.getLocation().getId());
        flushAndReset();
        var infos = port.findActiveMapInfos(List.of(expired.getId(), ready.getId(), missing.getId(), deleted.getId(), ready.getId()));
        assertThat(infos).extracting(RestaurantMapInfo::restaurantId).containsExactly(expired.getId(), ready.getId(), missing.getId());
        assertThat(infos.get(0).location()).isNull();
        assertThat(infos.get(1).location().latitude()).isEqualByComparingTo("0");
        assertThat(infos.get(1).location().validUntil()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(infos.get(2).location()).isNull();
        assertThat(infos.get(2).placeType()).isEqualTo("cafe");
        assertThat(infos.get(2).genre()).isEqualTo("etc");
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics().getEntityLoadCount()).isZero();
        verifyNoInteractions(mediaPort, fileStorage);
    }

    @Test
    void 선택식당은_삭제와_위치미준비를_구분하고_설정없이도_현재위치를_반환한다() {
        Restaurant ready = ready("ready", "0", "0");
        Restaurant missing = restaurants.save(restaurant("missing", RestaurantGenre.SUSHI, RestaurantPlaceType.RESTAURANT));
        Restaurant deleted = ready("deleted", ".5", ".5");
        deleted.softDelete();
        flushAndReset();
        properties.setInitialBounds(new MapQueryProperties.Bounds());
        assertThat(service.getLocation(ready.getId()).location().validUntil()).isEqualTo(NOW.plusSeconds(3600));
        assertCode(() -> service.getLocation(missing.getId()), RestaurantErrorCode.MAP_LOCATION_UNAVAILABLE);
        assertCode(() -> service.getLocation(deleted.getId()), RestaurantErrorCode.NOT_FOUND);
        assertCode(() -> service.getLocation(Long.MAX_VALUE), RestaurantErrorCode.NOT_FOUND);
    }

    @Test
    void 실제_501개_Port와_재검사는_각각_2쿼리이며_item별_조회가_없다() {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            ids.add(ready("batch " + index, ".5", ".5").getId());
        }
        Collections.reverse(ids);
        flushAndReset();
        assertThat(port.findActiveMapInfos(ids)).extracting(RestaurantMapInfo::restaurantId).containsExactlyElementsOf(ids);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics().getEntityLoadCount()).isZero();
        statistics().clear();
        assertThat(service.findMatchingCandidates(criteria(null), ids))
                .extracting(RestaurantMapCandidate::restaurantId).containsExactlyElementsOf(ids);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics().getEntityLoadCount()).isZero();
        verifyNoInteractions(mediaPort, fileStorage);
    }

    @Test
    void 실제_후보_SQL의_EXPLAIN을_합성데이터에서_기록한다() {
        for (int index = 0; index < 20; index++) {
            ready("plan " + index, ".5", ".5");
        }
        flushAndReset();
        assertThat(service.findCandidates(criteria(null), 30).candidates()).hasSize(20);
        String candidateSql = SQL.stream().filter(sql -> sql.contains("select r.id, r.rating")).findFirst().orElseThrow();
        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + candidateSql,
                "2026-01-01 00:00:00.000000", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, 31);
        assertThat(plan).isNotEmpty();
        System.out.println("MAP candidate EXPLAIN; 20 synthetic rows, not production performance: " + plan);
    }

    private Restaurant ready(String name, String latitude, String longitude) {
        Restaurant restaurant = restaurant(name, RestaurantGenre.SUSHI, RestaurantPlaceType.RESTAURANT);
        restaurant.requestLocationResolution();
        restaurant.completeLocation(1, restaurant.getLocation().getRequestId(), point(latitude, longitude),
                RestaurantLocationSource.OPERATOR, UTC_NOW.minusHours(1), UTC_NOW.plusHours(1), CLOCK);
        return restaurants.save(restaurant);
    }

    private Restaurant restaurant(String name, RestaurantGenre genre, RestaurantPlaceType type) {
        return Restaurant.create(name, "fixture", "fixture", "fixture", "synthetic address", "same display area",
                genre, "fixture", type, PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
    }

    private MapRegion activeRegion(String code, int order) {
        MapRegion region = MapRegion.create(code, "합성 지역", point(".5", ".5"), bounds(), order);
        region.activate();
        return regions.saveAndFlush(region);
    }

    private RestaurantMenu menu(String name) {
        return RestaurantMenu.create(name, "fixture", null, PriceCurrency.JPY, BigDecimal.ONE, false);
    }

    private MapSearchCriteria criteria(String keyword) {
        return MapSearchCriteria.of(BOUNDS, null, null, null, keyword);
    }

    private static MapCoordinates point(String latitude, String longitude) {
        return MapCoordinates.of(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    private static MapBounds bounds() {
        return MapBounds.of(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE);
    }

    private MapQueryProperties.Bounds propertyBounds(String minimum, String maximum) {
        var bounds = new MapQueryProperties.Bounds();
        bounds.setSouth(minimum);
        bounds.setNorth(maximum);
        bounds.setWest(minimum);
        bounds.setEast(maximum);
        return bounds;
    }

    private void flushAndReset() {
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
        SQL.clear();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void assertCode(Runnable query, RestaurantErrorCode code) {
        assertThatThrownBy(query::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class Infrastructure {
        /** 기존 backfill 전용 빈은 항상 등록되므로 일반 조회 모듈 테스트에서는 그 빈만 제외한다. */
        @Bean
        static BeanFactoryPostProcessor excludeBackfillAttachment() {
            return factory -> ((BeanDefinitionRegistry) factory)
                    .removeBeanDefinition("restaurantMediaBackfillAttachmentService");
        }

        @Bean("japanClock")
        Clock clock() {
            return CLOCK;
        }

        @Bean
        HibernatePropertiesCustomizer statementInspector() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                SQL.add(sql);
                return sql;
            });
        }
    }
}
