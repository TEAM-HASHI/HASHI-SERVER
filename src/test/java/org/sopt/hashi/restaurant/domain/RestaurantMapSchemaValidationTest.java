package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RestaurantService.class, TimeConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true"
})
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantMapSchemaValidationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_map_schema").withUsername("hashi").withPassword("hashi");

    @Autowired
    private RestaurantRepository restaurants;
    @Autowired
    private MapRegionRepository regions;
    @Autowired
    private RestaurantService service;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @MockitoBean
    private MediaPort mediaPort;
    @MockitoBean
    private FileStorage fileStorage;

    @Test
    void 빈_스키마_migration과_validate_후_위치_좌표와_지역을_정확히_왕복한다() {
        MapRegion region = regions.saveAndFlush(MapRegion.create("ROUNDTRIP", "합성 지역", point(), bounds(), 0));
        region.activate();
        Restaurant restaurant = ready();
        restaurant.assignMapRegion(region.getId());
        restaurants.saveAndFlush(restaurant);
        Long id = restaurant.getId();
        entityManager.clear();

        Restaurant reloaded = restaurants.findById(id).orElseThrow();
        assertThat(reloaded.hasUsableMapLocation(CLOCK)).isTrue();
        assertThat(reloaded.getLocation().getCoordinates()).isEqualTo(point());
        assertThat(reloaded.getLocation().getObtainedAt()).isEqualTo(NOW);
        assertThat(reloaded.getLocation().getValidUntil()).isEqualTo(NOW.plusDays(1));
        assertThat(reloaded.getLocation().getSource()).isEqualTo(RestaurantLocationSource.GOOGLE_GEOCODING);
        assertThat(reloaded.getMapRegionId()).isEqualTo(region.getId());
        assertThat(regions.findAllByActiveTrueOrderByDisplayOrderAscIdAsc())
                .extracting(MapRegion::getCode).contains("ROUNDTRIP");
        assertThat(regions.findById(region.getId()).orElseThrow().getCameraBounds()).isEqualTo(bounds());
    }

    @Test
    void 기존_관리자_주소_PATCH는_위치와_같이_커밋하고_지역과_통계를_보존한다() {
        Restaurant restaurant = restaurants.saveAndFlush(ready());
        restaurant.assignMapRegion(123L);
        restaurants.flush();
        Long id = restaurant.getId();
        jdbc.update("UPDATE restaurant SET rating_sum=9, review_count=2, rating=4.5 WHERE id=?", id);
        entityManager.clear();

        var response = service.updateByAdmin(id, addressCommand("새 합성 주소"));
        entityManager.flush();
        entityManager.clear();

        Restaurant reloaded = restaurants.findById(id).orElseThrow();
        assertThat(response.address()).isEqualTo("새 합성 주소");
        assertThat(reloaded.getLocation().getStatus()).isEqualTo(RestaurantLocationStatus.PENDING);
        assertThat(reloaded.getLocation().getAddressRevision()).isEqualTo(2);
        assertThat(reloaded.getLocation().getCoordinates()).isNull();
        assertThat(reloaded.getLocation().getSource()).isNull();
        assertThat(reloaded.getMapRegionId()).isEqualTo(123L);
        assertThat(reloaded.getRatingSum()).isEqualTo(9);
        assertThat(reloaded.getReviewCount()).isEqualTo(2);
        assertThat(reloaded.getRating()).isEqualByComparingTo("4.5");
    }

    @Test
    void 일반_조회는_위치_수에_따른_추가_SELECT를_발생시키지_않는다() {
        List<Restaurant> fixtures = restaurants.saveAllAndFlush(List.of(ready(), ready(), ready(), restaurant()));
        List<Long> ids = fixtures.stream().map(Restaurant::getId).toList();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<Restaurant> rows = restaurants.findAllByIdInAndDeletedFalse(ids);

        assertThat(rows).hasSize(4);
        for (Restaurant row : rows) {
            if (row.getLocation() != null) {
                assertThat(Hibernate.isInitialized(row.getLocation())).isFalse();
            }
        }
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void soft_delete는_위치_행을_물리_삭제하지_않고_일반_공개_조회에서_제외한다() {
        Restaurant restaurant = restaurants.saveAndFlush(ready());
        Long id = restaurant.getId();
        Long locationId = restaurant.getLocation().getId();
        restaurants.delete(restaurant);
        restaurants.flush();
        entityManager.clear();

        assertThat(restaurants.findByIdAndDeletedFalse(id)).isEmpty();
        Restaurant deleted = restaurants.findById(id).orElseThrow();
        assertThat(deleted.hasUsableMapLocation(CLOCK)).isFalse();
        assertThat(deleted.getLocation().getId()).isEqualTo(locationId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_location WHERE id=?", Integer.class,
                locationId)).isEqualTo(1);
    }

    @Test
    void 위치는_여러_식당에_공유될_수_없고_존재하지_않는_자식을_참조하지_못한다() {
        Restaurant first = restaurants.saveAndFlush(ready());
        Restaurant second = restaurants.saveAndFlush(restaurant());
        assertThatThrownBy(() -> jdbc.update("UPDATE restaurant SET location_id=? WHERE id=?",
                first.getLocation().getId(), second.getId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE restaurant SET location_id=-1 WHERE id=?", second.getId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE restaurant SET map_region_id=0 WHERE id=?", second.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 오래된_위치_엔티티의_동시_변경은_낙관적_버전으로_거절한다() {
        Restaurant saved = restaurant();
        saved.requestLocationResolution();
        Long id = restaurants.saveAndFlush(saved).getId();
        try (EntityManager first = entityManagerFactory.createEntityManager();
             EntityManager second = entityManagerFactory.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            Restaurant winner = first.find(Restaurant.class, id);
            Restaurant stale = second.find(Restaurant.class, id);
            UUID request = stale.getLocation().getRequestId();
            winner.rejectLocation(1, request, RestaurantLocationStatus.REVIEW_REQUIRED);
            first.getTransaction().commit();
            stale.rejectLocation(1, request, RestaurantLocationStatus.FAILED);
            assertThatThrownBy(() -> second.getTransaction().commit())
                    .hasCauseInstanceOf(OptimisticLockException.class);
        }
        assertThat(jdbc.queryForObject("""
                SELECT l.status FROM restaurant r JOIN restaurant_location l ON r.location_id=l.id WHERE r.id=?
                """, String.class, id)).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 주소_PATCH가_rollback되면_주소와_READY_위치가_함께_복원된다() {
        Restaurant saved = restaurants.saveAndFlush(ready());
        Long id = saved.getId();
        UUID request = saved.getLocation().getRequestId();
        transactions.executeWithoutResult(transaction -> {
            service.updateByAdmin(id, addressCommand("rollback 합성 주소"));
            transaction.setRollbackOnly();
        });
        transactions.executeWithoutResult(transaction -> {
            Restaurant reloaded = restaurants.findById(id).orElseThrow();
            assertThat(reloaded.getAddress()).isEqualTo("합성 주소");
            assertThat(reloaded.getLocation().getAddressRevision()).isEqualTo(1);
            assertThat(reloaded.getLocation().getRequestId()).isEqualTo(request);
            assertThat(reloaded.hasUsableMapLocation(CLOCK)).isTrue();
        });
    }

    private Restaurant restaurant() {
        return Restaurant.create("합성 식당", "fixture", "요약", "설명", "합성 주소", "표시 지역",
                RestaurantGenre.SUSHI, "초밥", RestaurantPlaceType.RESTAURANT,
                PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
    }

    private Restaurant ready() {
        Restaurant restaurant = restaurant();
        restaurant.requestLocationResolution();
        restaurant.completeLocation(1, restaurant.getLocation().getRequestId(), point(),
                RestaurantLocationSource.GOOGLE_GEOCODING, NOW, NOW.plusDays(1), CLOCK);
        return restaurant;
    }

    private MapCoordinates point() {
        return MapCoordinates.of(new BigDecimal("10.123456"), new BigDecimal("20.654321"));
    }

    private MapBounds bounds() {
        return MapBounds.of(new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("20"), new BigDecimal("21"));
    }

    private AdminRestaurantCommand addressCommand(String address) {
        return new AdminRestaurantCommand(null, null, null, null, address, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

}
