package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.ImageCommand;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantWriteConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void 수정과_삭제가_경쟁해도_먼저_commit된_수정값을_삭제가_되돌리지_않는다() throws Exception {
        Restaurant restaurant = restaurantRepository.saveAndFlush(createRestaurant("잠금 전 식당"));
        Long restaurantId = restaurant.getId();
        CountDownLatch updateFlushed = new CountDownLatch(1);
        CountDownLatch allowUpdateCommit = new CountDownLatch(1);

        Future<?> update = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            restaurantService.updateByAdmin(
                    restaurantId,
                    updateNameCommand("잠금 후 식당")
            );
            updateFlushed.countDown();
            await(allowUpdateCommit, "수정 transaction commit");
        }));
        assertThat(updateFlushed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> delete = executor.submit(() -> restaurantService.deleteByAdmin(restaurantId));
        try {
            awaitMySqlRowLockCompetition();
        } finally {
            allowUpdateCommit.countDown();
        }

        update.get(20, TimeUnit.SECONDS);
        delete.get(20, TimeUnit.SECONDS);

        Restaurant reloaded = restaurantRepository.findById(restaurantId).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("잠금 후 식당");
        assertThat(reloaded.isDeleted()).isTrue();
    }

    @Test
    void 같은_식당의_동시_재정렬은_완전한_요청_하나로_직렬화된다() throws Exception {
        Restaurant restaurant = createRestaurant("동시 재정렬 식당");
        restaurant.replaceImages(List.of(
                RestaurantImage.createLegacy("restaurants/A.jpg", 1),
                RestaurantImage.createLegacy("restaurants/B.jpg", 2),
                RestaurantImage.createLegacy("restaurants/C.jpg", 3)
        ));
        restaurantRepository.saveAndFlush(restaurant);
        Long restaurantId = restaurant.getId();
        Long firstId = restaurant.getImages().get(0).getId();
        Long secondId = restaurant.getImages().get(1).getId();
        Long thirdId = restaurant.getImages().get(2).getId();
        List<Long> firstOrder = List.of(thirdId, firstId, secondId);
        List<Long> secondOrder = List.of(secondId, thirdId, firstId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> reorderAfterBarrier(
                restaurantId, firstOrder, ready, start));
        Future<?> second = executor.submit(() -> reorderAfterBarrier(
                restaurantId, secondOrder, ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        Restaurant reloaded = restaurantRepository
                .findActiveByIdWithImages(restaurantId)
                .orElseThrow();
        List<Long> finalOrder = reloaded.getImages().stream()
                .map(RestaurantImage::getId)
                .toList();
        assertThat(List.of(firstOrder, secondOrder)).contains(finalOrder);
        assertThat(reloaded.getImages())
                .extracting(RestaurantImage::getDisplayOrder)
                .containsExactly(1, 2, 3);
    }

    private void reorderAfterBarrier(
            Long restaurantId,
            List<Long> imageIds,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start, "동시 재정렬 시작");
        restaurantService.updateByAdmin(
                restaurantId,
                updateImagesCommand(imageIds.stream()
                        .map(id -> new ImageCommand(id, null))
                        .toList())
        );
    }

    private void awaitMySqlRowLockCompetition() throws InterruptedException, SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("MySQL row lock 경쟁이 제한 시간 안에 관찰되지 않았습니다");
    }

    private void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(description + " 대기 시간이 초과되었습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " 대기가 중단되었습니다", exception);
        }
    }

    private AdminRestaurantCommand updateNameCommand(String name) {
        return new AdminRestaurantCommand(
                name, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        );
    }

    private AdminRestaurantCommand updateImagesCommand(List<ImageCommand> images) {
        return new AdminRestaurantCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, images, null, null, null, null
        );
    }

    private Restaurant createRestaurant(String name) {
        return Restaurant.create(
                name,
                "Concurrency Restaurant",
                "식당 소개",
                "식당 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                "초밥",
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000)
        );
    }
}
