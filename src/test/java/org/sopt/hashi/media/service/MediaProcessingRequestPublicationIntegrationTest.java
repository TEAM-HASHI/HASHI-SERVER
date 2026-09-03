package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestPublisher;
import org.sopt.hashi.media.internal.recovery.MediaProcessingRecoveryCandidate;
import org.sopt.hashi.media.internal.recovery.MediaProcessingRecoveryTransactionService;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;
import org.sopt.hashi.media.internal.queue.MediaTransformRequestPublisher;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
@Import(MediaProcessingRequestPublicationIntegrationTest.PublisherConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaProcessingRequestPublicationIntegrationTest {

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
    private MediaProcessingRecoveryTransactionService recoveryTransactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRequestPublisher requestPublisher;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM event_publication");
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
        requestPublisher.reset();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM event_publication");
    }

    @Test
    void commit된_job_snapshot을_발행한_뒤_EPR_publication을_삭제한다() throws Exception {
        UUID assetId = createAndCompleteAsset();

        MediaTransformRequest request = requestPublisher.awaitAttempt();

        assertThat(request.assetId()).isEqualTo(assetId);
        assertThat(request.jobId()).isEqualTo(
                imageAssetRepository.findByPublicId(assetId).orElseThrow().getCurrentJobId());
        assertThat(request.sourceVersionId()).isEqualTo("version-1");
        assertThat(request.specVersion()).isEqualTo(1);
        assertThat(requestPublisher.awaitTransactionState()).isFalse();
        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void SQS_발행이_실패하면_EPR_publication을_미완료로_남긴다() throws Exception {
        requestPublisher.failNext();
        UUID assetId = createAndCompleteAsset();

        MediaTransformRequest request = requestPublisher.awaitAttempt();

        assertThat(request.assetId()).isEqualTo(assetId);
        assertThat(requestPublisher.awaitTransactionState()).isFalse();
        assertThat(awaitCondition(() -> publicationCount() == 1, Duration.ofSeconds(5))).isTrue();
        ImageAsset asset = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 정체_job은_같은_jobId로_EPR을_통해_재발행하고_간격_안에는_중복하지_않는다()
            throws Exception {
        UUID assetId = createAndCompleteAsset();
        MediaTransformRequest initialRequest = requestPublisher.awaitAttempt();
        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
        ImageAsset asset = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        LocalDateTime startedAt = asset.getTargetProcessingStartedAt();
        MediaProcessingRecoveryCandidate candidate = new MediaProcessingRecoveryCandidate(
                asset.getId(), asset.getCurrentJobId(), startedAt);

        boolean requested = recoveryTransactionService.requestRetryIfStillStalled(
                candidate,
                startedAt.plusMinutes(20),
                startedAt.plusMinutes(10),
                startedAt.plusMinutes(5),
                3
        );
        MediaTransformRequest recoveredRequest = requestPublisher.awaitAttempt();

        assertThat(requested).isTrue();
        assertThat(recoveredRequest).isEqualTo(initialRequest);
        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
        ImageAsset recovered = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        assertThat(recovered.getProcessingRecoveryAttempts()).isEqualTo(1);
        assertThat(recovered.getLastRecoveryRequestedAt())
                .isEqualTo(startedAt.plusMinutes(20));

        boolean duplicateRequested = recoveryTransactionService.requestRetryIfStillStalled(
                candidate,
                startedAt.plusMinutes(21),
                startedAt.plusMinutes(10),
                startedAt.plusMinutes(19),
                3
        );

        assertThat(duplicateRequested).isFalse();
        assertThat(requestPublisher.hasNoAttempt(Duration.ofMillis(300))).isTrue();
    }

    private UUID createAndCompleteAsset() {
        UUID assetId = UUID.randomUUID();
        transactionService.createAssets(
                USER,
                MediaPurpose.REVIEW,
                List.of(new PreparedMediaAsset(
                        assetId,
                        objectKey(assetId),
                        "image/jpeg",
                        1024L,
                        LocalDateTime.now().plusMinutes(5)
                ))
        );
        transactionService.completeAssets(
                USER,
                List.of(assetId),
                Map.of(assetId, new OriginalObjectMetadata(
                        objectKey(assetId),
                        "version-1",
                        "\"etag-1\"",
                        "image/jpeg",
                        1024L
                ))
        );
        return assetId;
    }

    private int publicationCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM event_publication
                WHERE listener_id = ?
                  AND completion_date IS NULL
                """, Integer.class, MediaProcessingRequestPublisher.LISTENER_ID);
    }

    private boolean awaitCondition(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private String objectKey(UUID assetId) {
        return "media/originals/%s/original".formatted(assetId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublisherConfig {

        @Bean
        TestRequestPublisher testRequestPublisher() {
            return new TestRequestPublisher();
        }
    }

    static class TestRequestPublisher implements MediaTransformRequestPublisher {

        private final BlockingQueue<MediaTransformRequest> attempts = new LinkedBlockingQueue<>();
        private final BlockingQueue<Boolean> transactionStates = new LinkedBlockingQueue<>();
        private volatile boolean failNext;

        @Override
        public void publish(MediaTransformRequest request) {
            attempts.add(request);
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("simulated SQS failure");
            }
        }

        MediaTransformRequest awaitAttempt() throws InterruptedException {
            MediaTransformRequest request = attempts.poll(10, TimeUnit.SECONDS);
            if (request == null) {
                throw new IllegalStateException("media request publication timed out");
            }
            return request;
        }

        boolean awaitTransactionState() throws InterruptedException {
            Boolean transactionActive = transactionStates.poll(10, TimeUnit.SECONDS);
            if (transactionActive == null) {
                throw new IllegalStateException("media publication transaction state timed out");
            }
            return transactionActive;
        }

        void failNext() {
            failNext = true;
        }

        void reset() {
            attempts.clear();
            transactionStates.clear();
            failNext = false;
        }

        boolean hasNoAttempt(Duration timeout) throws InterruptedException {
            return attempts.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) == null;
        }
    }
}
