package org.sopt.hashi.user.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManagerFactory;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.CollectionColor;
import org.sopt.hashi.user.collection.domain.CollectionVisibility;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsRequest;
import org.sopt.hashi.user.collection.dto.SaveRestaurantRequest;
import org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** H2 회귀 전체를 실제 MySQL/Flyway에서도 실행한다. Docker가 없으면 성공으로 간주하지 않고 skip을 보고한다. */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class CollectionMySqlIntegrationTest extends RestaurantCollectionIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi").withUsername("hashi").withPassword("hashi");

    @Autowired RestaurantCollectionService service;
    @Autowired RestaurantCollectionRepository collections;
    @Autowired SavedRestaurantRepository saved;
    @Autowired RestaurantPort restaurants;
    @Autowired CollectionMapSnapshotStore snapshots;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void 반대_방향_동시_이동은_ID순_부모_잠금으로_둘다_완료한다() throws Exception {
        RestaurantCollection first = RestaurantCollection.create(1L, "첫째", CollectionColor.RED, null, CollectionVisibility.PUBLIC);
        RestaurantCollection second = RestaurantCollection.create(1L, "둘째", CollectionColor.RED, null, CollectionVisibility.PUBLIC);
        first.save(101L);
        second.save(102L);
        Long firstId = collections.saveAndFlush(first).getId();
        Long secondId = collections.saveAndFlush(second).getId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var forward = executor.submit(() -> {
                start.await();
                return service.moveRestaurants(firstId, new MoveSavedRestaurantsRequest(secondId, List.of(101L)));
            });
            var backward = executor.submit(() -> {
                start.await();
                return service.moveRestaurants(secondId, new MoveSavedRestaurantsRequest(firstId, List.of(102L)));
            });
            start.countDown();
            forward.get(20, TimeUnit.SECONDS);
            backward.get(20, TimeUnit.SECONDS);
        }
        assertThat(saved.findAllByCollection_IdOrderByIdDesc(firstId))
                .extracting(item -> item.getRestaurantId()).containsExactly(102L);
        assertThat(saved.findAllByCollection_IdOrderByIdDesc(secondId))
                .extracting(item -> item.getRestaurantId()).containsExactly(101L);
    }

    @Test
    void OSIV처럼_EntityManager가_재사용돼도_최종검사는_현재_비공개_상태를_읽는다() throws Exception {
        Long id = collections.saveAndFlush(RestaurantCollection.create(
                1L, "OSIV", CollectionColor.RED, null, CollectionVisibility.PUBLIC)).getId();
        var entityManager = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var snapshot = snapshots.read(id, null);
            executor.submit(() -> service.update(id,
                    new UpdateRestaurantCollectionRequest(null, null, null, "private"))).get(20, TimeUnit.SECONDS);
            // 기존 managed Entity는 stale임을 먼저 증명한다. native scalar current read만 최신 상태를 본다.
            assertThat(entityManager.find(RestaurantCollection.class, id).isPublic()).isTrue();
            assertThatThrownBy(() -> snapshots.validate(snapshot, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.COLLECTION_NOT_FOUND));
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            entityManager.close();
        }
    }

    @Test
    void 마지막_한자리_동시_저장은_상한을_넘기지_않는다() throws Exception {
        RestaurantCollection collection = RestaurantCollection.create(1L, "상한", CollectionColor.RED, null, CollectionVisibility.PUBLIC);
        for (long id = 1; id < 1000; id++) {
            collection.save(id);
        }
        Long collectionId = collections.saveAndFlush(collection).getId();
        given(restaurants.existsActiveById(1000L)).willReturn(true);
        given(restaurants.existsActiveById(1001L)).willReturn(true);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> saveResult(start, collectionId, 1000L));
            var second = executor.submit(() -> saveResult(start, collectionId, 1001L));
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "USER-011");
        }
        assertThat(saved.findAllByCollection_IdOrderByIdDesc(collectionId)).hasSize(1000);
    }

    private String saveResult(CountDownLatch start, Long collectionId, Long restaurantId) throws InterruptedException {
        start.await();
        try {
            service.saveRestaurant(collectionId, new SaveRestaurantRequest(restaurantId));
            return "saved";
        } catch (BusinessException error) {
            return error.getErrorCode().getCode();
        }
    }

    @Test
    void 전체_ID_snapshot과_최종검사는_저장수와_무관하게_각각_두개와_한개_SQL이다() {
        RestaurantCollection collection = RestaurantCollection.create(1L, "쿼리", CollectionColor.RED, null, CollectionVisibility.PUBLIC);
        for (long id = 1; id <= 1000; id++) {
            collection.save(id);
        }
        Long collectionId = collections.saveAndFlush(collection).getId();
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        var snapshot = snapshots.read(collectionId, null);
        assertThat(snapshot.restaurantIds()).hasSize(1000);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        statistics.clear();
        snapshots.validate(snapshot, null);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }
}
