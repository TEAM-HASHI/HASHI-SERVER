package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.ImageCommand;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";
    private static final String SOURCE_CHECKSUM =
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

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
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ADMIN, 1L));
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
    void 같은_식당의_동시_collection_수정은_binding과_순서를_완전한_요청_단위로_직렬화한다()
            throws Exception {
        ImageAsset retiredAsset = readyRestaurantAsset(true);
        ImageAsset firstNewAsset = readyRestaurantAsset(false);
        ImageAsset secondNewAsset = readyRestaurantAsset(false);
        transactionTemplate.executeWithoutResult(status -> imageAssetRepository.saveAll(
                List.of(retiredAsset, firstNewAsset, secondNewAsset)));

        Restaurant restaurant = createRestaurant("동시 이미지 수정 식당");
        restaurant.replaceImages(List.of(
                RestaurantImage.createLegacy("restaurants/retained-legacy.jpg", 1),
                RestaurantImage.createAsset(retiredAsset.getPublicId(), 2)
        ));
        restaurantRepository.saveAndFlush(restaurant);
        Long restaurantId = restaurant.getId();
        Long retainedLegacyImageId = restaurant.getImages().getFirst().getId();
        Long retiredRestaurantImageId = restaurant.getImages().get(1).getId();
        CountDownLatch firstUpdateFlushed = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        AtomicReference<Long> firstNewAssociationId = new AtomicReference<>();

        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            AdminRestaurantInfo response = restaurantService.updateByAdmin(
                    restaurantId,
                    updateImagesCommand(List.of(
                            new ImageCommand(retainedLegacyImageId, null),
                            new ImageCommand(null, firstNewAsset.getPublicId())
                    ))
            );
            firstNewAssociationId.set(findAssociationId(response, firstNewAsset.getPublicId()));
            firstUpdateFlushed.countDown();
            await(allowFirstCommit, "첫 collection 수정 transaction commit");
        }));
        assertThat(firstUpdateFlushed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<AdminRestaurantInfo> second = executor.submit(() -> restaurantService.updateByAdmin(
                restaurantId,
                updateImagesCommand(List.of(
                        new ImageCommand(firstNewAssociationId.get(), null),
                        new ImageCommand(null, secondNewAsset.getPublicId())
                ))
        ));
        try {
            awaitMySqlRowLockCompetition();
        } finally {
            allowFirstCommit.countDown();
        }

        first.get(20, TimeUnit.SECONDS);
        AdminRestaurantInfo secondResponse = second.get(20, TimeUnit.SECONDS);
        Long secondNewAssociationId = findAssociationId(
                secondResponse, secondNewAsset.getPublicId());

        Restaurant reloaded = restaurantRepository
                .findActiveByIdWithImages(restaurantId)
                .orElseThrow();
        assertThat(reloaded.getImages())
                .extracting(RestaurantImage::getId)
                .containsExactly(firstNewAssociationId.get(), secondNewAssociationId);
        assertThat(reloaded.getImages())
                .extracting(RestaurantImage::getImageAssetId)
                .containsExactly(firstNewAsset.getPublicId(), secondNewAsset.getPublicId());
        assertThat(reloaded.getImages())
                .extracting(RestaurantImage::getDisplayOrder)
                .containsExactly(1, 2);
        assertThat(reloaded.getImages())
                .extracting(RestaurantImage::getId)
                .doesNotContain(retainedLegacyImageId, retiredRestaurantImageId);
        assertThat(imageAssetRepository.findAllByPublicIdIn(List.of(
                retiredAsset.getPublicId(),
                firstNewAsset.getPublicId(),
                secondNewAsset.getPublicId()
        ))).extracting(ImageAsset::getBindingStatus)
                .containsExactlyInAnyOrder(
                        ImageBindingStatus.RETIRED,
                        ImageBindingStatus.BOUND,
                        ImageBindingStatus.BOUND
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

    private Long findAssociationId(AdminRestaurantInfo response, UUID assetId) {
        return response.heroImages().stream()
                .filter(image -> image.image() != null && assetId.equals(image.image().assetId()))
                .map(image -> image.restaurantImageId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("응답에서 asset association을 찾을 수 없습니다"));
    }

    private ImageAsset readyRestaurantAsset(boolean bound) {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.RESTAURANT,
                MediaOwnerType.ADMIN,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5)
        );
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        addSquareRenditions(asset, jobId, ImageRole.RESTAURANT_THUMBNAIL, 96, 192, 288);
        addSquareRenditions(asset, jobId, ImageRole.RESTAURANT_CARD, 135, 270, 405);
        addRendition(asset, jobId, ImageRole.RESTAURANT_HERO, 430, 256);
        addRendition(asset, jobId, ImageRole.RESTAURANT_HERO, 860, 512);
        addRendition(asset, jobId, ImageRole.RESTAURANT_HERO, 1290, 769);
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032,
                SOURCE_CHECKSUM);
        if (bound) {
            asset.bind();
        }
        return asset;
    }

    private void addSquareRenditions(
            ImageAsset asset,
            UUID jobId,
            ImageRole role,
            int... widths
    ) {
        for (int width : widths) {
            addRendition(asset, jobId, role, width, width);
        }
    }

    private void addRendition(
            ImageAsset asset,
            UUID jobId,
            ImageRole role,
            int width,
            int height
    ) {
        asset.addRendition(
                jobId,
                1,
                SPEC_DIGEST,
                role,
                ImageFormat.WEBP,
                width,
                height,
                100L,
                "media/renditions/%s/v1/%s/%d.webp".formatted(
                        asset.getPublicId(), role.name().toLowerCase(), width)
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
