package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
class MediaPortIntegrationTest {

    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";
    private static final String SOURCE_CHECKSUM =
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private MediaPort mediaPort;

    @Autowired
    private MediaAssetTransactionService transactionService;

    @Autowired
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
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
    void binding은_외부_transaction을_필수로_하고_claim과_retire를_함께_commit한다() {
        ImageAsset claim = readyAsset(MediaPurpose.RESTAURANT, false);
        ImageAsset retire = readyAsset(MediaPurpose.RESTAURANT_MENU, true);
        transactionTemplate.executeWithoutResult(status ->
                imageAssetRepository.saveAll(List.of(claim, retire)));

        assertThatThrownBy(() -> mediaPort.reconcileBindings(
                List.of(new MediaAssetUse(
                        claim.getPublicId(), MediaAssetPurpose.RESTAURANT)),
                List.of(new MediaAssetUse(
                        retire.getPublicId(), MediaAssetPurpose.RESTAURANT_MENU))
        )).isInstanceOf(IllegalTransactionStateException.class);

        transactionTemplate.executeWithoutResult(status -> mediaPort.reconcileBindings(
                List.of(new MediaAssetUse(
                        claim.getPublicId(), MediaAssetPurpose.RESTAURANT)),
                List.of(new MediaAssetUse(
                        retire.getPublicId(), MediaAssetPurpose.RESTAURANT_MENU))
        ));

        assertThat(imageAssetRepository.findByPublicId(claim.getPublicId()).orElseThrow()
                .getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
        assertThat(imageAssetRepository.findByPublicId(retire.getPublicId()).orElseThrow()
                .getBindingStatus()).isEqualTo(ImageBindingStatus.RETIRED);
    }

    @Test
    void projection_SQL은_asset_개수와_무관하게_두_번이다() {
        long oneAssetQueries = projectionQueryCount(1);
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
        long manyAssetQueries = projectionQueryCount(8);

        assertThat(oneAssetQueries).isEqualTo(2L);
        assertThat(manyAssetQueries).isEqualTo(2L);
    }

    @Test
    void 동일_asset을_동시에_claim하면_하나만_BOUND로_전이한다() throws Exception {
        ImageAsset asset = readyAsset(MediaPurpose.RESTAURANT, false);
        transactionTemplate.executeWithoutResult(status -> imageAssetRepository.save(asset));
        List<MediaAssetUse> claims = List.of(new MediaAssetUse(
                asset.getPublicId(), MediaAssetPurpose.RESTAURANT));

        List<ClaimResult> results = claimConcurrently(claims, claims);

        assertThat(results).containsExactlyInAnyOrder(
                ClaimResult.success(),
                ClaimResult.failure(MediaErrorCode.ALREADY_BOUND)
        );
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow()
                .getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
    }

    @Test
    void 반대_입력_순서의_다중_asset_claim도_내부_ID_순서로_잠가_deadlock을_피한다() throws Exception {
        ImageAsset first = readyAsset(MediaPurpose.RESTAURANT, false);
        ImageAsset second = readyAsset(MediaPurpose.RESTAURANT, false);
        transactionTemplate.executeWithoutResult(status ->
                imageAssetRepository.saveAll(List.of(first, second)));
        MediaAssetUse firstUse = new MediaAssetUse(
                first.getPublicId(), MediaAssetPurpose.RESTAURANT);
        MediaAssetUse secondUse = new MediaAssetUse(
                second.getPublicId(), MediaAssetPurpose.RESTAURANT);

        List<ClaimResult> results = claimConcurrently(
                List.of(firstUse, secondUse),
                List.of(secondUse, firstUse)
        );

        assertThat(results).containsExactlyInAnyOrder(
                ClaimResult.success(),
                ClaimResult.failure(MediaErrorCode.ALREADY_BOUND)
        );
        assertThat(imageAssetRepository.findAllByPublicIdIn(
                List.of(first.getPublicId(), second.getPublicId())))
                .extracting(ImageAsset::getBindingStatus)
                .containsOnly(ImageBindingStatus.BOUND);
    }

    @Test
    void 완료와_claim이_경쟁해도_UUID와_반대인_내부_ID_순서로_잠근다() throws Exception {
        List<ImageAsset> assets = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            UUID assetId = new UUID(0L, 1_000L - index);
            assets.add(index < 10
                    ? readyAsset(MediaPurpose.RESTAURANT, false, assetId)
                    : pendingAsset(MediaPurpose.RESTAURANT, assetId));
        }
        transactionTemplate.executeWithoutResult(status -> imageAssetRepository.saveAll(assets));
        jdbcTemplate.execute("ANALYZE TABLE image_asset");
        List<ImageAsset> requestedAssets = assets.subList(0, 10);
        List<UUID> requestedIds = requestedAssets.stream().map(ImageAsset::getPublicId).toList();
        List<MediaAssetUse> claims = requestedIds.stream()
                .map(assetId -> new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT))
                .toList();

        try (Connection blocker = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = blocker.prepareStatement(
                     "SELECT id FROM image_asset WHERE id = ? FOR UPDATE")) {
            blocker.setAutoCommit(false);
            statement.setLong(1, requestedAssets.get(4).getId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }

            Future<List<OwnedAssetSnapshot>> completed = executor.submit(() ->
                    transactionService.completeAssets(
                            new CurrentActor(ActorType.ADMIN, 1L), requestedIds, Map.of()));
            Future<ClaimResult> claimed;
            try {
                awaitMySqlRowLockCompetition(1);
                claimed = executor.submit(() -> claimOnce(claims));
                awaitMySqlRowLockCompetition(2);
            } finally {
                blocker.commit();
            }

            assertThat(completed.get(20, TimeUnit.SECONDS)).hasSize(10);
            assertThat(claimed.get(20, TimeUnit.SECONDS)).isEqualTo(ClaimResult.success());
        }

        assertThat(imageAssetRepository.findAllByPublicIdIn(requestedIds))
                .hasSize(10)
                .extracting(ImageAsset::getBindingStatus)
                .containsOnly(ImageBindingStatus.BOUND);
    }

    private long projectionQueryCount(int count) {
        List<ImageAsset> assets = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            assets.add(readyAsset(MediaPurpose.RESTAURANT, true));
        }
        transactionTemplate.executeWithoutResult(status -> imageAssetRepository.saveAll(assets));
        List<MediaImageRequest> requests = assets.stream()
                .map(asset -> new MediaImageRequest(
                        asset.getPublicId(), MediaImageRole.RESTAURANT_CARD))
                .toList();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Map<MediaImageRequest, MediaImage> result = mediaPort.findImages(requests);

        assertThat(result).hasSize(count);
        assertThat(result.values()).allSatisfy(image ->
                assertThat(image.defaultSource().width()).isEqualTo(270));
        return statistics.getPrepareStatementCount();
    }

    private List<ClaimResult> claimConcurrently(
            List<MediaAssetUse> firstClaims,
            List<MediaAssetUse> secondClaims
    ) throws Exception {
        CountDownLatch firstClaimFlushed = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        Future<ClaimResult> first = executor.submit(() ->
                claimAndHold(firstClaims, firstClaimFlushed, allowFirstCommit));
        assertThat(firstClaimFlushed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<ClaimResult> second = executor.submit(() -> claimOnce(secondClaims));
        try {
            awaitMySqlRowLockCompetition();
        } finally {
            allowFirstCommit.countDown();
        }
        return List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
        );
    }

    private ClaimResult claimAndHold(
            List<MediaAssetUse> claims,
            CountDownLatch firstClaimFlushed,
            CountDownLatch allowFirstCommit
    ) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                mediaPort.reconcileBindings(claims, List.of());
                imageAssetRepository.flush();
                firstClaimFlushed.countDown();
                await(allowFirstCommit, "첫 claim transaction commit");
            });
            return ClaimResult.success();
        } catch (BusinessException exception) {
            return ClaimResult.failure((MediaErrorCode) exception.getErrorCode());
        }
    }

    private ClaimResult claimOnce(List<MediaAssetUse> claims) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    mediaPort.reconcileBindings(claims, List.of()));
            return ClaimResult.success();
        } catch (BusinessException exception) {
            return ClaimResult.failure((MediaErrorCode) exception.getErrorCode());
        }
    }

    private void awaitMySqlRowLockCompetition() throws InterruptedException, SQLException {
        awaitMySqlRowLockCompetition(1);
    }

    private void awaitMySqlRowLockCompetition(int minimumWaitingTransactions)
            throws InterruptedException, SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) "
                                + "FROM performance_schema.data_lock_waits")) {
                    resultSet.next();
                    if (resultSet.getInt(1) >= minimumWaitingTransactions) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("MySQL asset row lock 경쟁이 제한 시간 안에 관찰되지 않았습니다");
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

    private ImageAsset readyAsset(MediaPurpose purpose, boolean bound) {
        return readyAsset(purpose, bound, UUID.randomUUID());
    }

    private ImageAsset pendingAsset(MediaPurpose purpose, UUID assetId) {
        return ImageAsset.createDirectUpload(
                assetId,
                purpose,
                MediaOwnerType.ADMIN,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5)
        );
    }

    private ImageAsset readyAsset(MediaPurpose purpose, boolean bound, UUID assetId) {
        ImageAsset asset = pendingAsset(purpose, assetId);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        if (purpose == MediaPurpose.RESTAURANT) {
            addRendition(asset, jobId, ImageRole.RESTAURANT_CARD, 135);
            addRendition(asset, jobId, ImageRole.RESTAURANT_CARD, 270);
            addRendition(asset, jobId, ImageRole.RESTAURANT_CARD, 405);
        } else {
            addRendition(asset, jobId, ImageRole.MENU_LIST, 200);
        }
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032,
                SOURCE_CHECKSUM);
        if (bound) {
            asset.bind();
        }
        return asset;
    }

    private void addRendition(ImageAsset asset, UUID jobId, ImageRole role, int width) {
        asset.addRendition(
                jobId,
                1,
                SPEC_DIGEST,
                role,
                ImageFormat.WEBP,
                width,
                width,
                100L,
                "media/renditions/%s/v1/%s/%d.webp".formatted(
                        asset.getPublicId(), role.name().toLowerCase(), width)
        );
    }

    private record ClaimResult(boolean succeeded, MediaErrorCode errorCode) {

        private static ClaimResult success() {
            return new ClaimResult(true, null);
        }

        private static ClaimResult failure(MediaErrorCode errorCode) {
            return new ClaimResult(false, errorCode);
        }
    }
}
