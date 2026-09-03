package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
@Import(MediaAssetTransactionIntegrationTest.EventCollector.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaAssetTransactionIntegrationTest {

    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";
    private static final CurrentActor USER = new CurrentActor(ActorType.USER, 1L);

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private MediaAssetTransactionService transactionService;

    @Autowired
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventCollector eventCollector;

    @Autowired
    @Qualifier("japanClock")
    private Clock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
        jdbcTemplate.update("""
                UPDATE media_pipeline_config
                SET current_spec_version = 1,
                    current_spec_digest = ?,
                    issuance_enabled = TRUE,
                    lock_version = lock_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = 1
                """, SPEC_DIGEST);
        eventCollector.clear();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void 동일한_complete_동시_요청은_job을_한_번만_생성한다() throws Exception {
        UUID assetId = createAsset(USER);
        OriginalObjectMetadata metadata = metadata(assetId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<List<OwnedAssetSnapshot>> first = executor.submit(() ->
                completeAfterBarrier(assetId, metadata, ready, start));
        Future<List<OwnedAssetSnapshot>> second = executor.submit(() ->
                completeAfterBarrier(assetId, metadata, ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(20, TimeUnit.SECONDS).getFirst().status())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(second.get(20, TimeUnit.SECONDS).getFirst().status())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(eventCollector.events()).hasSize(1);

        ImageAsset asset = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        assertThat(asset.getCurrentJobId())
                .isEqualTo(MediaProcessingJobId.from(assetId, "version-1", 1));
        assertThat(eventCollector.events().getFirst().jobId()).isEqualTo(asset.getCurrentJobId());
        assertThat(asset.getLastIssuedSpecVersion()).isEqualTo(1);
    }

    @Test
    void S3_version_ID_1024_bytes를_손실없이_저장한다() {
        UUID assetId = createAsset(USER);
        String versionId = "v".repeat(MediaSourceIdentity.MAX_VERSION_ID_BYTES);
        OriginalObjectMetadata metadata = metadata(assetId, versionId);

        transactionService.completeAssets(USER, List.of(assetId), Map.of(assetId, metadata));

        ImageAsset asset = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        assertThat(asset.getSourceVersionId()).isEqualTo(versionId);
        assertThat(asset.getCurrentJobId())
                .isEqualTo(MediaProcessingJobId.from(assetId, versionId, 1));
        assertThat(eventCollector.events()).singleElement().satisfies(event -> {
            assertThat(event.assetId()).isEqualTo(assetId);
            assertThat(event.jobId()).isEqualTo(asset.getCurrentJobId());
        });
    }

    @Test
    void S3_version_ID가_1024_bytes를_넘으면_전체_complete를_롤백한다() {
        UUID firstId = createAsset(USER);
        UUID secondId = createAsset(USER);
        OriginalObjectMetadata tooLongVersion = metadata(
                secondId,
                "v".repeat(MediaSourceIdentity.MAX_VERSION_ID_BYTES + 1)
        );

        assertThatThrownBy(() -> transactionService.completeAssets(
                USER,
                List.of(firstId, secondId),
                Map.of(firstId, metadata(firstId), secondId, tooLongVersion)
        )).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.UPLOAD_METADATA_MISMATCH);

        assertThat(imageAssetRepository.findAllByPublicIdIn(List.of(firstId, secondId)))
                .allSatisfy(asset -> {
                    assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
                    assertThat(asset.getSourceVersionId()).isNull();
                    assertThat(asset.getCurrentJobId()).isNull();
                });
        assertThat(eventCollector.events()).isEmpty();
    }

    @Test
    void complete_batch의_metadata가_하나라도_없으면_모두_PENDING을_유지한다() {
        UUID firstId = createAsset(USER);
        UUID secondId = createAsset(USER);

        assertThatThrownBy(() -> transactionService.completeAssets(
                USER,
                List.of(firstId, secondId),
                Map.of(firstId, metadata(firstId))
        )).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.UPLOAD_NOT_FOUND);

        assertThat(imageAssetRepository.findAllByPublicIdIn(List.of(firstId, secondId)))
                .extracting(ImageAsset::getProcessingStatus)
                .containsOnly(ImageProcessingStatus.PENDING_UPLOAD);
        assertThat(eventCollector.events()).isEmpty();
    }

    @Test
    void 다른_actor의_asset은_존재하지_않는_것처럼_처리한다() {
        UUID assetId = createAsset(USER);
        CurrentActor otherUser = new CurrentActor(ActorType.USER, 2L);

        assertThatThrownBy(() -> transactionService.loadOwnedAssets(otherUser, List.of(assetId)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.ASSET_NOT_FOUND);
    }

    @Test
    void issuance가_중지되면_신규_asset을_만들지_않는다() {
        pauseIssuance();

        assertThatThrownBy(() -> createAsset(USER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PIPELINE_UNAVAILABLE);
        assertThat(imageAssetRepository.count()).isZero();
    }

    @Test
    void DB_digest가_packaged_manifest와_다르면_신규_asset을_만들지_않는다() {
        jdbcTemplate.update("""
                UPDATE media_pipeline_config
                SET current_spec_digest = ?,
                    lock_version = lock_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = 1
                """, "0".repeat(64));

        assertThatThrownBy(() -> createAsset(USER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PIPELINE_UNAVAILABLE);
        assertThat(imageAssetRepository.count()).isZero();
    }

    @Test
    void issuance가_중지돼도_PROCESSING_complete_재호출은_성공한다() {
        UUID assetId = createAsset(USER);
        transactionService.completeAssets(USER, List.of(assetId), Map.of(assetId, metadata(assetId)));
        pauseIssuance();

        List<OwnedAssetSnapshot> result = transactionService.completeAssets(
                USER,
                List.of(assetId),
                Map.of()
        );

        assertThat(result).singleElement().satisfies(snapshot ->
                assertThat(snapshot.status()).isEqualTo(ImageProcessingStatus.PROCESSING));
        assertThat(eventCollector.events()).hasSize(1);
    }

    @Test
    void issuance가_중지되면_PENDING_complete는_전이하지_않는다() {
        UUID assetId = createAsset(USER);
        pauseIssuance();

        assertThatThrownBy(() -> transactionService.completeAssets(
                USER,
                List.of(assetId),
                Map.of(assetId, metadata(assetId))
        )).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PIPELINE_UNAVAILABLE);

        assertThat(imageAssetRepository.findByPublicId(assetId).orElseThrow().getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        assertThat(eventCollector.events()).isEmpty();
    }

    private void pauseIssuance() {
        jdbcTemplate.update("""
                UPDATE media_pipeline_config
                SET issuance_enabled = FALSE,
                    lock_version = lock_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = 1
                """);
    }

    private UUID createAsset(CurrentActor actor) {
        UUID assetId = UUID.randomUUID();
        transactionService.createAssets(
                actor,
                MediaPurpose.REVIEW,
                List.of(new PreparedMediaAsset(
                        assetId,
                        objectKey(assetId),
                        "image/jpeg",
                        1024L,
                        LocalDateTime.now(clock).plusMinutes(5)
                ))
        );
        return assetId;
    }

    private List<OwnedAssetSnapshot> completeAfterBarrier(
            UUID assetId,
            OriginalObjectMetadata metadata,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent complete start timed out");
        }
        return transactionService.completeAssets(
                USER,
                List.of(assetId),
                Map.of(assetId, metadata)
        );
    }

    private OriginalObjectMetadata metadata(UUID assetId) {
        return metadata(assetId, "version-1");
    }

    private OriginalObjectMetadata metadata(UUID assetId, String versionId) {
        return new OriginalObjectMetadata(
                objectKey(assetId),
                versionId,
                "\"etag-1\"",
                "image/jpeg",
                1024L
        );
    }

    private String objectKey(UUID assetId) {
        return "media/originals/%s/original".formatted(assetId);
    }

    static class EventCollector {

        private final Queue<MediaProcessingRequestedEvent> events = new ConcurrentLinkedQueue<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void collect(MediaProcessingRequestedEvent event) {
            events.add(event);
        }

        List<MediaProcessingRequestedEvent> events() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
