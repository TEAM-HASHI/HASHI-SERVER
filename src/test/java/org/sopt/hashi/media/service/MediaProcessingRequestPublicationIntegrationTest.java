package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
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
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.sopt.hashi.media.internal.recovery.MediaEventPublicationRecovery;
import org.sopt.hashi.media.internal.recovery.MediaProcessingRecoveryCandidate;
import org.sopt.hashi.media.internal.recovery.MediaProcessingRecoveryTransactionService;
import org.sopt.hashi.media.internal.recovery.MediaRecoveryProperties;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;
import org.sopt.hashi.media.internal.queue.MediaTransformRequestPublisher;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
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

    @Autowired
    private IncompleteEventPublications incompleteEventPublications;

    @Autowired
    private MediaPipelineMetrics metrics;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("japanClock")
    private Clock clock;

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
    void 실제_EPR은_실패한_publication을_같은_job으로_재발행하고_완료_처리한다()
            throws Exception {
        requestPublisher.failNext();
        UUID assetId = createAndCompleteAsset();
        MediaTransformRequest failedAttempt = requestPublisher.awaitAttempt();
        assertThat(awaitCondition(() -> publicationCount() == 1, Duration.ofSeconds(5))).isTrue();
        requestPublisher.reset();

        recovery().resubmitOnStartup();

        MediaTransformRequest recovered = requestPublisher.awaitAttempt();
        assertThat(recovered).isEqualTo(failedAttempt);
        assertThat(recovered.assetId()).isEqualTo(assetId);
        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void 실제_EPR은_이미_종료된_job을_SQS로_보내지_않고_publication을_완료한다()
            throws Exception {
        requestPublisher.failNext();
        UUID assetId = createAndCompleteAsset();
        requestPublisher.awaitAttempt();
        assertThat(awaitCondition(() -> publicationCount() == 1, Duration.ofSeconds(5))).isTrue();
        jdbcTemplate.update("""
                UPDATE image_asset
                SET processing_status = 'FAILED',
                    target_spec_version = NULL,
                    target_spec_digest = NULL,
                    target_processing_status = NULL,
                    current_job_id = NULL,
                    target_processing_started_at = NULL,
                    last_recovery_requested_at = NULL,
                    processing_recovery_attempts = 0
                WHERE public_id = ?
                """, assetId.toString());
        jdbcTemplate.update("""
                UPDATE event_publication
                SET publication_date = '2000-01-01 00:00:00.000000'
                WHERE listener_id = ?
                  AND completion_date IS NULL
                """, MediaProcessingRequestPublisher.LISTENER_ID);
        requestPublisher.reset();

        recovery().resubmitOldPublications();

        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
        assertThat(requestPublisher.hasNoAttempt(Duration.ofMillis(300))).isTrue();
    }

    @Test
    void 정체_job은_같은_jobId로_EPR을_통해_재발행하고_간격_안에는_중복하지_않는다()
            throws Exception {
        UUID assetId = createAndCompleteAsset();
        MediaTransformRequest initialRequest = requestPublisher.awaitAttempt();
        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
        ImageAsset asset = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        LocalDateTime startedAt = LocalDateTime.now(clock).minusMinutes(30);
        jdbcTemplate.update(
                "UPDATE image_asset SET target_processing_started_at = ? WHERE id = ?",
                startedAt,
                asset.getId());
        MediaProcessingRecoveryCandidate candidate = new MediaProcessingRecoveryCandidate(
                asset.getId(), asset.getCurrentJobId(), startedAt);

        boolean requested = recoveryTransactionService.requestRetryIfStillStalled(
                candidate,
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                3
        );
        MediaTransformRequest recoveredRequest = requestPublisher.awaitAttempt();

        assertThat(requested).isTrue();
        assertThat(recoveredRequest).isEqualTo(initialRequest);
        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
        ImageAsset recovered = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        assertThat(recovered.getProcessingRecoveryAttempts()).isEqualTo(1);
        assertThat(recovered.getLastRecoveryRequestedAt()).isAfter(startedAt);

        boolean duplicateRequested = recoveryTransactionService.requestRetryIfStillStalled(
                candidate,
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                3
        );

        assertThat(duplicateRequested).isFalse();
        assertThat(requestPublisher.hasNoAttempt(Duration.ofMillis(300))).isTrue();
    }

    @Test
    void 복구_요청_시간은_row_lock을_얻은_뒤에_관측한다() throws Exception {
        UUID assetId = createAndCompleteAsset();
        requestPublisher.awaitAttempt();
        assertThat(awaitCondition(() -> publicationCount() == 0, Duration.ofSeconds(5))).isTrue();
        ImageAsset asset = imageAssetRepository.findByPublicId(assetId).orElseThrow();
        LocalDateTime startedAt = LocalDateTime.now(clock).minusMinutes(30);
        jdbcTemplate.update(
                "UPDATE image_asset SET target_processing_started_at = ? WHERE id = ?",
                startedAt,
                asset.getId());
        MediaProcessingRecoveryCandidate candidate = new MediaProcessingRecoveryCandidate(
                asset.getId(), asset.getCurrentJobId(), startedAt);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = dataSource.getConnection();
             PreparedStatement lockStatement = lockConnection.prepareStatement(
                     "SELECT id FROM image_asset WHERE id = ? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lockStatement.setLong(1, asset.getId());
            lockStatement.executeQuery();
            CountDownLatch retryStarted = new CountDownLatch(1);
            Future<Boolean> retry = executor.submit(() -> {
                retryStarted.countDown();
                return recoveryTransactionService.requestRetryIfStillStalled(
                        candidate, Duration.ofMinutes(10), Duration.ofMinutes(15), 3);
            });
            assertThat(retryStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> retry.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            LocalDateTime lockReleasedAt = LocalDateTime.now(clock)
                    .truncatedTo(ChronoUnit.MICROS);
            lockConnection.commit();

            assertThat(retry.get(5, TimeUnit.SECONDS)).isTrue();
            LocalDateTime recordedAt = jdbcTemplate.queryForObject(
                    "SELECT last_recovery_requested_at FROM image_asset WHERE id = ?",
                    LocalDateTime.class,
                    asset.getId());
            assertThat(recordedAt).isAfterOrEqualTo(lockReleasedAt);
        } finally {
            executor.shutdownNow();
        }
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

    private MediaEventPublicationRecovery recovery() {
        return new MediaEventPublicationRecovery(
                incompleteEventPublications,
                new MediaRecoveryProperties(
                        true,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        50,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(15),
                        3,
                        100,
                        10,
                        Duration.ofSeconds(30),
                        Duration.ofHours(24),
                        Duration.ofHours(24),
                        Duration.ofDays(7),
                        Duration.ofDays(7)),
                metrics,
                clock);
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
