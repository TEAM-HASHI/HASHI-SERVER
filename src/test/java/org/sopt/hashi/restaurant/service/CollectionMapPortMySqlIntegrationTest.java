package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantMapQueryRepository;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.MapQueryFailureLogger;
import org.sopt.hashi.restaurant.internal.map.MapQueryProperties;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.CollectionColor;
import org.sopt.hashi.user.collection.domain.CollectionVisibility;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest;
import org.sopt.hashi.user.collection.dto.SaveRestaurantRequest;
import org.sopt.hashi.user.collection.service.CollectionMapQueryService;
import org.sopt.hashi.user.collection.service.RestaurantCollectionService;
import org.sopt.hashi.user.collection.service.RestaurantSaveSummaryService;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** user→실제 RestaurantPort→MapService→MySQL을 검증한다. 미사용 admin/detail Service와 외부 media/storage만 격리한다. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CollectionMapPortMySqlIntegrationTest.Infrastructure.class, RestaurantPortImpl.class,
        RestaurantMapService.class, RestaurantMapQueryRepository.class, TimeConfig.class, JpaAuditingConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CollectionMapPortMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRIVATE_DETAIL = "SELECT private_sql collectionId=987654321 key=synthetic-secret";
    private static final String PRIVATE_CAUSE = "private-address 35.654321 139.123456";
    private static final AtomicInteger BATCHES = new AtomicInteger();
    private static volatile boolean failSecondBatch;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi").withUsername("hashi").withPassword("hashi");

    @TestConfiguration
    @EnableConfigurationProperties(MapQueryProperties.class)
    @ComponentScan(basePackageClasses = RestaurantCollectionService.class)
    static class Infrastructure {
        @Bean
        HibernatePropertiesCustomizer inspectMapBatches() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                if (sql.contains("date_format(l.valid_until") && sql.contains("from restaurant r left join")) {
                    if (BATCHES.incrementAndGet() == 2 && failSecondBatch) {
                        throw new DataAccessResourceFailureException(PRIVATE_DETAIL,
                                new IllegalStateException(PRIVATE_CAUSE));
                    }
                }
                return sql;
            });
        }
    }

    @Autowired CollectionMapQueryService maps;
    @Autowired RestaurantCollectionService writes;
    @Autowired RestaurantSaveSummaryService summaries;
    @Autowired RestaurantRepository restaurants;
    @Autowired RestaurantCollectionRepository collections;
    @Autowired SavedRestaurantRepository saved;
    @Autowired UserRepository users;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean RestaurantService unusedRestaurantService;
    @MockitoBean RestaurantLocationService unusedAdminLocationService;
    @MockitoBean MediaPort media;
    @MockitoBean FileStorage storage;
    @MockitoBean(name = "japanClock") Clock clock;
    private Long userId;

    @BeforeEach
    void setUp() {
        User active = users.saveAndFlush(User.onboard(
                "지도Port회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01011110005", "map-port@hashi.test", null));
        userId = active.getId();
        given(currentUser.isAuthenticatedUser()).willReturn(true);
        given(currentUser.currentUserId()).willReturn(userId);
        given(clock.instant()).willReturn(NOW);
        BATCHES.set(0);
        failSecondBatch = false;
    }

    @AfterEach
    void clean() {
        failSecondBatch = false;
        saved.deleteAllInBatch();
        collections.deleteAllInBatch();
        users.deleteAllInBatch();
        jdbc.update("delete from restaurant");
        jdbc.update("delete from restaurant_location");
    }

    @Test
    void 실제_저장과_Port로_23개중_21개핀을_연결하고_삭제식당은_제외한다() {
        List<Restaurant> rows = seedRestaurants(23, 21);
        var created = writes.create(new CreateRestaurantCollectionRequest("실제 경로", "red", null, "public"));
        writes.saveRestaurant(created.collectionId(), new SaveRestaurantRequest(rows.getFirst().getId()));
        assertThat(maps.getMarkers(created.collectionId()).content()).hasSize(1);
        Long collectionId = seedCollection("전체 경로", rows);
        var response = maps.getMarkers(collectionId);
        assertThat(response.visibleRestaurantCount()).isEqualTo(23);
        assertThat(response.locationUnavailableCount()).isEqualTo(2);
        assertThat(response.content()).hasSize(21);
        assertThat(summaries.getSaveCounts(List.of(rows.getFirst().getId())).restaurants().getFirst().saveCount()).isEqualTo(1);
        assertThat(summaries.getMySaves(List.of(rows.getFirst().getId())).restaurants().getFirst().saved()).isTrue();
        jdbc.update("update restaurant set deleted=true where id=?", rows.getFirst().getId());
        var afterDelete = maps.getMarkers(collectionId);
        assertThat(afterDelete.visibleRestaurantCount()).isEqualTo(22);
        assertThat(afterDelete.content()).hasSize(20);
        assertThat(saved.findAllByCollection_IdOrderByIdDesc(collectionId)).hasSize(23);
    }

    @Test
    void 갱신과_재시도중_유효핀은_유지하고_만료와_주소변경은_핀만_제외한다() {
        List<Restaurant> rows = seedRestaurants(4, 4);
        Long collectionId = seedCollection("좌표 갱신", rows);
        rows.forEach(Restaurant::refreshLocation);
        Restaurant retrying = rows.get(1);
        retrying.deferLocation(1, retrying.getLocation().getRequestId(),
                LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC), FIXED);
        Restaurant failed = rows.get(2);
        failed.rejectLocation(1, failed.getLocation().getRequestId(),
                org.sopt.hashi.restaurant.domain.RestaurantLocationStatus.FAILED);
        Restaurant moved = rows.get(3);
        moved.updateBasicInfo(null, null, null, null, "changed address", null, null, null,
                null, null, null, null);
        restaurants.saveAllAndFlush(rows);

        var refreshing = maps.getMarkers(collectionId);
        assertThat(refreshing.visibleRestaurantCount()).isEqualTo(4);
        assertThat(refreshing.content()).extracting(marker -> marker.restaurantId())
                .containsExactly(rows.get(0).getId(), retrying.getId(), failed.getId());
        assertThat(refreshing.locationUnavailableCount()).isEqualTo(1);
        assertThat(saved.findAllByCollection_IdOrderByIdDesc(collectionId)).hasSize(4);

        given(clock.instant()).willReturn(NOW.plusSeconds(3600));
        var expired = maps.getMarkers(collectionId);
        assertThat(expired.visibleRestaurantCount()).isEqualTo(4);
        assertThat(expired.content()).isEmpty();
        assertThat(expired.locationUnavailableCount()).isEqualTo(4);
        assertThat(saved.findAllByCollection_IdOrderByIdDesc(collectionId)).hasSize(4);
    }

    @Test
    void 실제_1000개_전체핀은_Port_2batch와_전체_5SQL로_조회한다() {
        Long collectionId = seedCollection("1000 경로", seedRestaurants(1000, 1000));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(maps.getMarkers(collectionId).content()).hasSize(1000);
        assertThat(BATCHES).hasValue(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(1);
    }

    @Test
    void 실제_둘째_batch_SQL실패는_부분_핀_대신_503이다() {
        Long collectionId = seedCollection("실패 경로", seedRestaurants(501, 501));
        failSecondBatch = true;
        Logger collectionLogger = (Logger) LoggerFactory.getLogger(CollectionMapQueryService.class);
        Logger queryLogger = (Logger) LoggerFactory.getLogger(MapQueryFailureLogger.class);
        Level collectionLevel = collectionLogger.getLevel();
        Level queryLevel = queryLogger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        collectionLogger.setLevel(Level.WARN);
        queryLogger.setLevel(Level.WARN);
        logs.start();
        collectionLogger.addAppender(logs);
        queryLogger.addAppender(logs);
        try {
            BusinessException failure = catchThrowableOfType(
                    () -> maps.getMarkers(collectionId), BusinessException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.getErrorCode()).isEqualTo(UserErrorCode.COLLECTION_MAP_UNAVAILABLE);
            assertThat(failure.getCause()).isInstanceOfSatisfying(BusinessException.class, downstream -> {
                assertThat(downstream.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_QUERY_UNAVAILABLE);
                assertThat(downstream.getCause()).isInstanceOf(DataAccessResourceFailureException.class)
                        .hasMessage(PRIVATE_DETAIL).hasCauseInstanceOf(IllegalStateException.class);
            });
            assertThat(BATCHES).hasValue(2);
            assertThat(logs.list).hasSize(2);
            assertThat(logs.list).filteredOn(event -> event.getLoggerName().equals(collectionLogger.getName()))
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).isEqualTo(
                                "Collection map failed. operation=collection-map-port exceptionType=BusinessException");
                        assertThat(event.getArgumentArray()).containsExactly("BusinessException");
                    });
            assertThat(logs.list).filteredOn(event -> event.getLoggerName().equals(queryLogger.getName()))
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).isEqualTo(
                                "Map query failed. operation=restaurant-map-query exceptionType="
                                        + "DataAccessResourceFailureException");
                        assertThat(event.getArgumentArray()).containsExactly("DataAccessResourceFailureException");
                    });
            assertThat(logs.list).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_DETAIL, PRIVATE_CAUSE,
                        "987654321", "private_sql", "synthetic-secret", "35.654321", "139.123456");
            });
        } finally {
            collectionLogger.detachAppender(logs);
            queryLogger.detachAppender(logs);
            logs.stop();
            collectionLogger.setLevel(collectionLevel);
            queryLogger.setLevel(queryLevel);
        }
    }

    private List<Restaurant> seedRestaurants(int count, int located) {
        List<Restaurant> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        for (int index = 0; index < count; index++) {
            Restaurant restaurant = Restaurant.create("합성 식당 " + index, "fixture", "fixture", "fixture",
                    "synthetic address", "synthetic area", RestaurantGenre.SUSHI, "fixture", RestaurantPlaceType.RESTAURANT,
                    PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
            if (index < located) {
                restaurant.requestLocationResolution();
                restaurant.completeLocation(1, restaurant.getLocation().getRequestId(),
                        MapCoordinates.of(new BigDecimal("35.6"), new BigDecimal("139.7")), RestaurantLocationSource.ADMIN,
                        now.minusHours(1), now.plusHours(1), FIXED);
            }
            rows.add(restaurant);
        }
        return restaurants.saveAllAndFlush(rows);
    }

    private Long seedCollection(String name, List<Restaurant> rows) {
        RestaurantCollection collection = RestaurantCollection.create(
                userId, name, CollectionColor.RED, null, CollectionVisibility.PUBLIC);
        rows.forEach(restaurant -> collection.save(restaurant.getId()));
        return collections.saveAndFlush(collection).getId();
    }
}
