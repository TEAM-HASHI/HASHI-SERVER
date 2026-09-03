package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillClaim;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillTarget;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.BackfillOriginalCopy;
import org.sopt.hashi.media.internal.backfill.LegacyImageSource;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorage;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.queue.MediaQueueExecutionConfig;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;
import org.sopt.hashi.media.internal.queue.MediaTransformRequestPublisher;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorage;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ApplicationModuleTest
@Import(MediaBackfillTransactionIntegrationTest.Infrastructure.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "hashi.media.recovery.enabled=false",
        "hashi.media.backfill.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaBackfillTransactionIntegrationTest {

    private static final String HASH = "a".repeat(64);
    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi").withUsername("hashi").withPassword("hashi");

    @Autowired
    private MediaBackfillReservationService reservationService;
    @Autowired
    private MediaBackfillTransactionService transactionService;
    @Autowired
    private MediaAssetTransactionService publicAssetService;
    @Autowired
    private ImageAssetRepository assetRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MediaBackfillPort backfillPort;
    @Autowired
    private IncompleteEventPublications incompletePublications;
    @Autowired
    @Qualifier(MediaQueueExecutionConfig.PUBLISHER_EXECUTOR)
    private Executor publisherExecutor;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;
    @MockitoBean
    private FileStorage fileStorage;
    @MockitoBean
    private MediaOriginalStorage originalStorage;
    @MockitoBean
    private MediaBackfillStorage backfillStorage;
    @MockitoBean
    private MediaTransformRequestPublisher publisher;
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    private final BlockingQueue<PublishAttempt> attempts = new LinkedBlockingQueue<>();
    private final AtomicBoolean failPublisher = new AtomicBoolean();
    private ExecutorService executor;

    @BeforeEach
    void 준비한다() {
        jdbcTemplate.update("DELETE FROM event_publication");
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
        jdbcTemplate.update("""
                UPDATE media_pipeline_config
                SET current_spec_version = 1, current_spec_digest = ?, issuance_enabled = TRUE,
                    lock_version = lock_version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = 1
                """, SPEC_DIGEST);
        attempts.clear();
        failPublisher.set(false);
        executor = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            boolean shouldFail = failPublisher.get();
            attempts.add(new PublishAttempt(invocation.getArgument(0),
                    TransactionSynchronizationManager.isActualTransactionActive()));
            if (shouldFail) {
                throw new IllegalStateException("test publisher failure");
            }
            return null;
        }).when(publisher).publish(any(MediaTransformRequest.class));
    }

    @AfterEach
    void 정리한다() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(publicationCount()).isEqualTo(failPublisher.get() ? 1 : 0));
        verifyNoInteractions(currentActorProvider, originalStorage, fileStorage);
    }

    @Test
    void 동일_hash_예약은_같은_SYSTEM_BACKFILL_asset을_반환한다() {
        BackfillAssetSnapshot first = reserve();
        BackfillAssetSnapshot second = reserve();

        assertThat(second.assetId()).isEqualTo(first.assetId());
        assertThat(assetRepository.count()).isEqualTo(1);
        ImageAsset asset = assetRepository.findByPublicId(first.assetId()).orElseThrow();
        assertThat(asset.getCreationOrigin()).isEqualTo(MediaCreationOrigin.SYSTEM_BACKFILL);
        assertThat(asset.getOwnerActorType()).isEqualTo(MediaOwnerType.SYSTEM_BACKFILL);
        assertThat(asset.getOwnerSubjectId()).isNull();
        assertThat(asset.getCreatorActorType()).isNull();
        assertThat(asset.getCreatorSubjectId()).isNull();
        assertThat(asset.getOriginalObjectKey()).isEqualTo("media/originals/" + first.assetId() + "/original");
        assertThat(first.processingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        assertThat(first.bindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @Test
    void backfill_identity는_DB에서도_NULL로_변경할_수_없다() {
        BackfillAssetSnapshot reserved = reserve();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE image_asset SET backfill_identity_hash=NULL WHERE public_id=?",
                reserved.assetId().toString()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_image_asset_backfill_identity_required")
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
        assertThat(reserve().assetId()).isEqualTo(reserved.assetId());
        assertThat(assetRepository.count()).isEqualTo(1);
    }

    @Test
    void 실제_unique_잠금_경합_후_실패_transaction_밖에서_승자_asset으로_수렴한다() throws Exception {
        CountDownLatch firstFlushed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<BackfillAssetSnapshot> first = executor.submit(() -> transactionTemplate.execute(status -> {
            BackfillAssetSnapshot reserved = transactionService.reserve(MediaPurpose.PROFILE, HASH, source());
            firstFlushed.countDown();
            awaitLatch(allowCommit);
            return reserved;
        }));
        assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
        Future<BackfillAssetSnapshot> second = executor.submit(this::reserve);
        try {
            awaitMySqlLockWait();
        } finally {
            allowCommit.countDown();
        }

        assertThat(second.get(20, TimeUnit.SECONDS).assetId()).isEqualTo(first.get(20, TimeUnit.SECONDS).assetId());
        assertThat(assetRepository.count()).isEqualTo(1);
        assertThat(publicationCount()).isZero();
    }

    @Test
    void 예약_facade는_외부_transaction에서_호출할_수_없다() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> reserve()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(assetRepository.count()).isZero();
    }

    @Test
    void 복사_완료는_source와_job을_고정하고_commit_뒤_transaction_밖에서_발행한다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();

        BackfillAssetSnapshot processing = transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1"));
        PublishAttempt attempt = awaitPublish();

        assertThat(processing.processingStatus()).isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(processing.sourceVersionId()).isEqualTo("v1");
        assertThat(processing.jobId()).isEqualTo(MediaProcessingJobId.from(reserved.assetId(), "v1", 1));
        assertThat(attempt.inTransaction()).isFalse();
        assertThat(attempt.request().sourceVersionId()).isEqualTo("v1");
        assertThat(attempt.request().sourceETag()).isEqualTo("copy-etag-v1");
        assertThat(attempt.request().purpose()).isEqualTo(MediaPurpose.PROFILE);
        assertThat(attempt.request().specDigest()).isEqualTo(SPEC_DIGEST);
        assertThat(attempt.request().originalKey()).isEqualTo(reserved.originalObjectKey());
    }

    @Test
    void 서로_다른_copy_version이_동시에_완료돼도_최초_job과_version을_유지한다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();
        CountDownLatch firstChanged = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<BackfillAssetSnapshot> first = executor.submit(() -> transactionTemplate.execute(status -> {
            BackfillAssetSnapshot processing = transactionService.completeCopy(
                    reserved.assetId(), copy(reserved, "winner"));
            assetRepository.flush();
            firstChanged.countDown();
            awaitLatch(allowCommit);
            return processing;
        }));
        assertThat(firstChanged.await(10, TimeUnit.SECONDS)).isTrue();
        Future<BackfillAssetSnapshot> second = executor.submit(() ->
                transactionService.completeCopy(reserved.assetId(), copy(reserved, "late")));
        try {
            awaitMySqlLockWait();
        } finally {
            allowCommit.countDown();
        }

        assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(first.get(20, TimeUnit.SECONDS));
        assertThat(awaitPublish().request().sourceVersionId()).isEqualTo("winner");
        assertThat(attempts.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void outer_transaction이_rollback되면_job과_EPR이_함께_사라진다() {
        BackfillAssetSnapshot reserved = reserve();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1"));
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);

        ImageAsset asset = assetRepository.findByPublicId(reserved.assetId()).orElseThrow();
        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        assertThat(asset.getSourceVersionId()).isNull();
        assertThat(asset.getCurrentJobId()).isNull();
        assertThat(publicationCount()).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void pause는_새_예약과_PENDING_완료를_차단하지만_기존_PROCESSING_재호출은_허용한다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();
        pause();
        assertMediaFailure(() -> reservationService.reserveOrReuse(
                MediaPurpose.PROFILE, "b".repeat(64), source()), MediaErrorCode.PIPELINE_UNAVAILABLE);
        assertMediaFailure(() -> transactionService.completeCopy(
                reserved.assetId(), copy(reserved, "v1")), MediaErrorCode.PIPELINE_UNAVAILABLE);
        assertThat(assetRepository.count()).isEqualTo(1);
        assertThat(reserve().assetId()).isEqualTo(reserved.assetId());

        jdbcTemplate.update("UPDATE media_pipeline_config SET issuance_enabled=TRUE WHERE id=1");
        BackfillAssetSnapshot processing = transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1"));
        awaitPublish();
        pause();
        assertThat(transactionService.completeCopy(reserved.assetId(), copy(reserved, "late"))).isEqualTo(processing);
        assertThat(attempts.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void config_pause와_완료가_경합하면_pause_commit_이후_새_job을_발급하지_않는다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> pause = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            pause();
            paused.countDown();
            awaitLatch(allowCommit);
        }));
        assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> complete = executor.submit(() -> assertMediaFailure(() ->
                transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1")),
                MediaErrorCode.PIPELINE_UNAVAILABLE));
        try {
            awaitMySqlLockWait();
        } finally {
            allowCommit.countDown();
        }
        pause.get(20, TimeUnit.SECONDS);
        complete.get(20, TimeUnit.SECONDS);
        assertThat(assetRepository.findByPublicId(reserved.assetId()).orElseThrow().getCurrentJobId()).isNull();
    }

    @Test
    void copy의_hash_key_MIME_크기가_다르면_상태와_EPR을_변경하지_않는다() {
        BackfillAssetSnapshot reserved = reserve();
        List<BackfillOriginalCopy> wrongCopies = List.of(
                new BackfillOriginalCopy(reserved.originalObjectKey(), "v1", "etag", "image/jpeg", 1024,
                        "b".repeat(64)),
                new BackfillOriginalCopy("media/originals/other/original", "v1", "etag", "image/jpeg", 1024, HASH),
                new BackfillOriginalCopy(reserved.originalObjectKey(), "v1", "etag", "image/png", 1024, HASH),
                new BackfillOriginalCopy(reserved.originalObjectKey(), "v1", "etag", "image/jpeg", 2048, HASH));

        wrongCopies.forEach(copy -> assertMediaFailure(() ->
                transactionService.completeCopy(reserved.assetId(), copy), MediaErrorCode.UPLOAD_METADATA_MISMATCH));

        assertThat(assetRepository.findByPublicId(reserved.assetId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        assertThat(publicationCount()).isZero();
    }

    @Test
    void FAILED_PURGED_tombstone은_재예약해도_새_asset이나_job을_만들지_않는다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();
        BackfillAssetSnapshot processing = transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1"));
        awaitPublish();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(publicationCount()).isZero());
        transactionTemplate.executeWithoutResult(status -> {
            ImageAsset asset = assetRepository.findByPublicIdForUpdate(reserved.assetId()).orElseThrow();
            asset.failCurrentProcessing(processing.jobId(), 1, SPEC_DIGEST, "SOURCE_INVALID");
        });
        jdbcTemplate.update("""
                UPDATE image_asset SET cleanup_status='PURGED', purge_token=?,
                    purge_started_at=CURRENT_TIMESTAMP(6), objects_purged_at=CURRENT_TIMESTAMP(6)
                WHERE public_id=?
                """, UUID.randomUUID().toString(), reserved.assetId().toString());

        BackfillAssetSnapshot reused = reserve();
        assertThat(reused.assetId()).isEqualTo(reserved.assetId());
        assertThat(reused.processingStatus()).isEqualTo(ImageProcessingStatus.FAILED);
        assertThat(reused.cleanupStatus()).isEqualTo(MediaCleanupStatus.PURGED);
        assertMediaFailure(() -> transactionService.completeCopy(
                reserved.assetId(), copy(reserved, "late")), MediaErrorCode.INVALID_STATE);
        assertThat(assetRepository.count()).isEqualTo(1);
        assertThat(attempts.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void public_actor는_backfill_asset을_조회하거나_완료할_수_없다() {
        BackfillAssetSnapshot reserved = reserve();
        for (ActorType type : ActorType.values()) {
            CurrentActor actor = new CurrentActor(type, 1L);
            assertMediaFailure(() -> publicAssetService.loadOwnedAssets(actor, List.of(reserved.assetId())),
                    MediaErrorCode.ASSET_NOT_FOUND);
            assertMediaFailure(() -> publicAssetService.completeAssets(actor, List.of(reserved.assetId()), Map.of()),
                    MediaErrorCode.ASSET_NOT_FOUND);
        }
        assertThat(reserve().processingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
    }

    @Test
    void publisher_실패는_미완료_EPR을_남기고_asset을_PROCESSING으로_유지한다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();
        failPublisher.set(true);

        transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1"));

        assertThat(awaitPublish().inTransaction()).isFalse();
        assertThat(assetRepository.findByPublicId(reserved.assetId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 발급을_pause해도_실패_EPR은_최초_job을_그대로_재전송하고_완료된다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();
        failPublisher.set(true);
        BackfillAssetSnapshot processing = transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1"));
        PublishAttempt first = awaitPublish();
        awaitFailedPublication();
        pause();

        failPublisher.set(false);
        incompletePublications.resubmitIncompletePublications(publication ->
                publication.getEvent() instanceof MediaProcessingRequestedEvent);
        PublishAttempt retried = awaitPublish();

        assertThat(retried.request()).isEqualTo(first.request());
        assertThat(retried.inTransaction()).isFalse();
        assertThat(assetRepository.findByPublicId(reserved.assetId()).orElseThrow().getCurrentJobId())
                .isEqualTo(processing.jobId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(publicationCount()).isZero());
    }

    @Test
    void FAILED로_종료된_job의_실패_EPR은_재전송하지_않고_noop으로_완료한다() throws Exception {
        BackfillAssetSnapshot reserved = reserve();
        failPublisher.set(true);
        BackfillAssetSnapshot processing = transactionService.completeCopy(reserved.assetId(), copy(reserved, "v1"));
        awaitPublish();
        awaitFailedPublication();
        transactionTemplate.executeWithoutResult(status -> {
            ImageAsset asset = assetRepository.findByPublicIdForUpdate(reserved.assetId()).orElseThrow();
            asset.failCurrentProcessing(processing.jobId(), 1, SPEC_DIGEST, "SOURCE_INVALID");
        });

        failPublisher.set(false);
        incompletePublications.resubmitIncompletePublications(publication ->
                publication.getEvent() instanceof MediaProcessingRequestedEvent);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(publicationCount()).isZero());
        assertThat(attempts).isEmpty();
        assertThat(assetRepository.findByPublicId(reserved.assetId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.FAILED);
    }

    @Test
    void backfill_claim은_외부_쓰기_transaction이_필수다() {
        MediaBackfillClaim claim = claim(readyBackfill(HASH));
        assertThatThrownBy(() -> backfillPort.claimReady(List.of(claim)))
                .isInstanceOf(IllegalTransactionStateException.class);
        TransactionTemplate readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> backfillPort.claimReady(List.of(claim))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(assetRepository.findByPublicId(claim.assetId()).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @Test
    void READY_backfill은_원_transaction에_참여하고_실패하면_함께_rollback한다() {
        MediaBackfillClaim committed = claim(readyBackfill(HASH));
        transactionTemplate.executeWithoutResult(status -> backfillPort.claimReady(List.of(committed)));
        assertThat(assetRepository.findByPublicId(committed.assetId()).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.BOUND);
        MediaBackfillClaim rolledBack = claim(readyBackfill("b".repeat(64)));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            backfillPort.claimReady(List.of(rolledBack));
            assetRepository.flush();
            throw new IllegalStateException("test association rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(assetRepository.findByPublicId(rolledBack.assetId()).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @Test
    void 다른_source_hash나_purpose를_claim하면_전체_연결을_거부한다() {
        MediaBackfillClaim first = claim(readyBackfill(HASH));
        ImageAsset second = readyBackfill("b".repeat(64));
        List<MediaBackfillClaim> invalidClaims = List.of(
                new MediaBackfillClaim(second.getPublicId(), MediaAssetPurpose.PROFILE, HASH),
                new MediaBackfillClaim(second.getPublicId(), MediaAssetPurpose.REVIEW,
                        second.getBackfillIdentityHash()));

        for (MediaBackfillClaim invalid : invalidClaims) {
            assertMediaFailure(() -> transactionTemplate.executeWithoutResult(status ->
                    backfillPort.claimReady(List.of(first, invalid))), MediaErrorCode.INVALID_STATE);
            assertThat(assetRepository.findAll().stream().map(ImageAsset::getBindingStatus))
                    .containsOnly(ImageBindingStatus.UNBOUND);
        }
    }

    @Test
    void 일반_업로드_asset을_migration_Port로_연결할_수_없다() {
        UUID id = UUID.randomUUID();
        ImageAsset direct = ImageAsset.createDirectUpload(id, MediaPurpose.PROFILE, MediaOwnerType.ADMIN, 1L,
                "media/originals/" + id + "/original", "image/jpeg", 1024, LocalDateTime.now().plusDays(1));
        transactionTemplate.executeWithoutResult(status -> assetRepository.saveAndFlush(direct));

        assertMediaFailure(() -> transactionTemplate.executeWithoutResult(status -> backfillPort.claimReady(
                List.of(new MediaBackfillClaim(id, MediaAssetPurpose.PROFILE, HASH)))), MediaErrorCode.ASSET_NOT_FOUND);
        assertThat(assetRepository.findByPublicId(id).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @Test
    void 반대_순서의_동시_backfill_claim은_실제_잠금_대기_뒤_한_번만_연결된다() throws Exception {
        MediaBackfillClaim firstClaim = claim(readyBackfill(HASH));
        MediaBackfillClaim secondClaim = claim(readyBackfill("b".repeat(64)));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            backfillPort.claimReady(List.of(firstClaim, secondClaim));
            assetRepository.flush();
            claimed.countDown();
            awaitLatch(allowCommit);
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> second = executor.submit(() -> assertMediaFailure(() ->
                transactionTemplate.executeWithoutResult(status ->
                        backfillPort.claimReady(List.of(secondClaim, firstClaim))), MediaErrorCode.ALREADY_BOUND));
        try {
            awaitMySqlLockWait();
        } finally {
            allowCommit.countDown();
        }
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);
        assertThat(assetRepository.findAll().stream().map(ImageAsset::getBindingStatus))
                .containsOnly(ImageBindingStatus.BOUND);
    }

    @Test
    void 정리가_먼저_잠근_asset은_대기하던_claim이_연결하지_않는다() throws Exception {
        MediaBackfillClaim claim = claim(readyBackfill(HASH));
        CountDownLatch purging = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> cleanup = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    UPDATE image_asset SET cleanup_status='PURGING', purge_token=?,
                        purge_started_at=CURRENT_TIMESTAMP(6) WHERE public_id=?
                    """, UUID.randomUUID().toString(), claim.assetId().toString());
            purging.countDown();
            awaitLatch(allowCommit);
        }));
        assertThat(purging.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> attach = executor.submit(() -> assertMediaFailure(() ->
                transactionTemplate.executeWithoutResult(status -> backfillPort.claimReady(List.of(claim))),
                MediaErrorCode.INVALID_STATE));
        try {
            awaitMySqlLockWait();
        } finally {
            allowCommit.countDown();
        }
        cleanup.get(20, TimeUnit.SECONDS);
        attach.get(20, TimeUnit.SECONDS);
        assertThat(assetRepository.findByPublicId(claim.assetId()).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @Test
    void 조사와_준비_Port는_외부_transaction에서_S3를_호출하지_않는다() {
        MediaBackfillReference reference = legacyReference();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> backfillPort.inspect(reference))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> backfillPort.prepare(reference, HASH))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(assetRepository.count()).isZero();
        verifyNoInteractions(backfillStorage, publisher);
    }

    @Test
    void 발급_pause_중에도_조사는_가능하지만_준비는_새_asset을_만들지_않는다() {
        mockLegacyHead();
        pause();
        MediaBackfillReference reference = legacyReference();

        var inspected = backfillPort.inspect(reference);

        assertThat(inspected.asset()).isEmpty();
        assertMediaFailure(() -> backfillPort.prepare(reference, inspected.identityHash()),
                MediaErrorCode.PIPELINE_UNAVAILABLE);
        assertThat(assetRepository.count()).isZero();
        assertThat(publicationCount()).isZero();
        verify(backfillStorage, never()).findOrCopyOriginal(any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void 준비는_transaction_밖에서_복사하고_재호출은_같은_asset과_job을_유지한다() throws Exception {
        mockLegacyHead();
        MediaBackfillReference reference = legacyReference();
        var inspected = backfillPort.inspect(reference);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            UUID assetId = invocation.getArgument(0);
            ImageAsset reserved = assetRepository.findByPublicId(assetId).orElseThrow();
            assertThat(reserved.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
            return preparedCopy(assetId, invocation.getArgument(1), "prepared-v1");
        }).when(backfillStorage).findOrCopyOriginal(any(), any(), any());

        MediaBackfillAssetInfo prepared = backfillPort.prepare(reference, inspected.identityHash());
        PublishAttempt published = awaitPublish();
        pause();
        MediaBackfillAssetInfo repeated = backfillPort.prepare(reference, inspected.identityHash());

        assertThat(prepared.state()).isEqualTo(MediaBackfillAssetInfo.State.PROCESSING);
        assertThat(repeated).isEqualTo(prepared);
        assertThat(assetRepository.count()).isEqualTo(1);
        assertThat(published.inTransaction()).isFalse();
        assertThat(published.request().assetId()).isEqualTo(prepared.assetId());
        assertThat(published.request().sourceVersionId()).isEqualTo("prepared-v1");
        assertThat(published.request().purpose()).isEqualTo(MediaPurpose.PROFILE);
        assertThat(assetRepository.findByPublicId(prepared.assetId()).orElseThrow().getCurrentJobId())
                .isEqualTo(published.request().jobId());
        verify(backfillStorage, times(1)).findOrCopyOriginal(any(), any(), any());
        assertThat(attempts.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void HEAD_사이에_ETag가_바뀌면_예약이나_변환_요청을_남기지_않는다() {
        LegacyImageSource changed = new LegacyImageSource(source().bucket(), source().objectKey(),
                null, "changed-etag", "image/jpeg", 1024);
        when(backfillStorage.inspectSource(source().objectKey())).thenReturn(source(), changed);
        var inspected = backfillPort.inspect(legacyReference());

        assertThatThrownBy(() -> backfillPort.prepare(legacyReference(), inspected.identityHash()))
                .isInstanceOfSatisfying(MediaBackfillSourceException.class, exception ->
                        assertThat(exception.getReason())
                                .isEqualTo(MediaBackfillSourceException.Reason.SOURCE_CHANGED));
        assertThat(assetRepository.count()).isZero();
        assertThat(publicationCount()).isZero();
        verify(backfillStorage, never()).findOrCopyOriginal(any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void 복사_응답이_유실되면_예약을_보존하고_같은_asset으로_재시도한다() throws Exception {
        mockLegacyHead();
        var inspected = backfillPort.inspect(legacyReference());
        AtomicInteger copyAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (copyAttempts.incrementAndGet() == 1) {
                throw new MediaBackfillStorageException(MediaBackfillStorageException.Reason.STORAGE_UNAVAILABLE);
            }
            return preparedCopy(invocation.getArgument(0), invocation.getArgument(1), "existing-copy-v1");
        }).when(backfillStorage).findOrCopyOriginal(any(), any(), any());

        assertThatThrownBy(() -> backfillPort.prepare(legacyReference(), inspected.identityHash()))
                .isInstanceOf(MediaBackfillSourceException.class);
        ImageAsset reserved = assetRepository.findByBackfillIdentityHash(inspected.identityHash()).orElseThrow();
        assertThat(reserved.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        assertThat(reserved.getCurrentJobId()).isNull();
        assertThat(publicationCount()).isZero();
        assertThat(backfillPort.inspect(legacyReference()).asset()).hasValueSatisfying(
                asset -> assertThat(asset.assetId()).isEqualTo(reserved.getPublicId()));

        MediaBackfillAssetInfo resumed = backfillPort.prepare(legacyReference(), inspected.identityHash());

        assertThat(resumed.assetId()).isEqualTo(reserved.getPublicId());
        assertThat(resumed.state()).isEqualTo(MediaBackfillAssetInfo.State.PROCESSING);
        assertThat(assetRepository.count()).isEqualTo(1);
        assertThat(awaitPublish().request().sourceVersionId()).isEqualTo("existing-copy-v1");
    }

    @Test
    void copy_중_pause되면_job을_발급하지_않고_resume_후_같은_예약으로_이어간다() throws Exception {
        mockLegacyHead();
        var inspected = backfillPort.inspect(legacyReference());
        AtomicBoolean pauseDuringCopy = new AtomicBoolean(true);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (pauseDuringCopy.getAndSet(false)) {
                pause();
            }
            return preparedCopy(invocation.getArgument(0), invocation.getArgument(1), "paused-copy-v1");
        }).when(backfillStorage).findOrCopyOriginal(any(), any(), any());

        assertMediaFailure(() -> backfillPort.prepare(legacyReference(), inspected.identityHash()),
                MediaErrorCode.PIPELINE_UNAVAILABLE);
        ImageAsset reserved = assetRepository.findByBackfillIdentityHash(inspected.identityHash()).orElseThrow();
        assertThat(reserved.getCurrentJobId()).isNull();
        assertThat(reserved.getSourceVersionId()).isNull();
        assertThat(publicationCount()).isZero();
        jdbcTemplate.update("UPDATE media_pipeline_config SET issuance_enabled=TRUE WHERE id=1");

        MediaBackfillAssetInfo resumed = backfillPort.prepare(legacyReference(), inspected.identityHash());

        assertThat(resumed.assetId()).isEqualTo(reserved.getPublicId());
        assertThat(assetRepository.count()).isEqualTo(1);
        assertThat(awaitPublish().request().sourceVersionId()).isEqualTo("paused-copy-v1");
    }

    @Test
    void 동시_준비는_같은_예약과_최초_확정된_copy_job으로_수렴한다() throws Exception {
        mockLegacyHead();
        var inspected = backfillPort.inspect(legacyReference());
        CountDownLatch copiesStarted = new CountDownLatch(2);
        AtomicInteger copySequence = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String version = "concurrent-copy-" + copySequence.incrementAndGet();
            copiesStarted.countDown();
            awaitLatch(copiesStarted);
            return preparedCopy(invocation.getArgument(0), invocation.getArgument(1), version);
        }).when(backfillStorage).findOrCopyOriginal(any(), any(), any());
        Future<MediaBackfillAssetInfo> first = executor.submit(
                () -> backfillPort.prepare(legacyReference(), inspected.identityHash()));
        Future<MediaBackfillAssetInfo> second = executor.submit(
                () -> backfillPort.prepare(legacyReference(), inspected.identityHash()));

        MediaBackfillAssetInfo firstResult = first.get(20, TimeUnit.SECONDS);
        MediaBackfillAssetInfo secondResult = second.get(20, TimeUnit.SECONDS);
        PublishAttempt published = awaitPublish();

        assertThat(firstResult).isEqualTo(secondResult);
        assertThat(firstResult.state()).isEqualTo(MediaBackfillAssetInfo.State.PROCESSING);
        assertThat(assetRepository.count()).isEqualTo(1);
        ImageAsset canonical = assetRepository.findByPublicId(firstResult.assetId()).orElseThrow();
        assertThat(canonical.getSourceVersionId()).isIn("concurrent-copy-1", "concurrent-copy-2");
        assertThat(published.request().sourceVersionId()).isEqualTo(canonical.getSourceVersionId());
        assertThat(published.request().jobId()).isEqualTo(canonical.getCurrentJobId());
        assertThat(copySequence).hasValue(2);
        assertThat(attempts.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void READY_예약은_복사없이_조회하고_claim_뒤에는_BOUND로_확인한다() {
        mockLegacyHead();
        var inspected = backfillPort.inspect(legacyReference());
        ImageAsset ready = readyBackfill(inspected.identityHash());

        MediaBackfillAssetInfo prepared = backfillPort.prepare(legacyReference(), inspected.identityHash());

        assertThat(prepared.assetId()).isEqualTo(ready.getPublicId());
        assertThat(prepared.state()).isEqualTo(MediaBackfillAssetInfo.State.READY);
        transactionTemplate.executeWithoutResult(status -> backfillPort.claimReady(
                List.of(new MediaBackfillClaim(prepared.assetId(), prepared.purpose(), prepared.identityHash()))));
        assertThat(backfillPort.inspect(legacyReference()).asset()).hasValueSatisfying(
                asset -> assertThat(asset.state()).isEqualTo(MediaBackfillAssetInfo.State.BOUND));
        assertThat(assetRepository.count()).isEqualTo(1);
        verify(backfillStorage, never()).findOrCopyOriginal(any(), any(), any());
        verifyNoInteractions(publisher);
    }

    private MediaBackfillReference legacyReference() {
        return new MediaBackfillReference(MediaBackfillTarget.USER_PROFILE, 31L, source().objectKey());
    }

    private void mockLegacyHead() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return source();
        }).when(backfillStorage).inspectSource(source().objectKey());
    }

    private BackfillOriginalCopy preparedCopy(UUID assetId, String identityHash, String version) {
        return new BackfillOriginalCopy("media/originals/" + assetId + "/original", version,
                "copy-etag-" + version, source().contentType(), source().bytes(), identityHash);
    }

    private ImageAsset readyBackfill(String hash) {
        UUID id = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createSystemBackfill(id, MediaPurpose.PROFILE,
                "media/originals/" + id + "/original", "image/jpeg", 1024, LocalDateTime.now().plusDays(1), hash);
        asset.beginInitialProcessing("v1", "etag", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        for (int width : List.of(90, 180, 270)) {
            asset.addRendition(jobId, 1, SPEC_DIGEST, ImageRole.PROFILE_AVATAR, ImageFormat.WEBP, width, width, 100,
                    "media/renditions/" + id + "/v1/PROFILE_AVATAR/" + width + ".webp");
        }
        asset.completeCurrentProcessing(jobId, 1, SPEC_DIGEST, "image/jpeg", 1024, 300, 300,
                "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
        return transactionTemplate.execute(status -> assetRepository.saveAndFlush(asset));
    }

    private MediaBackfillClaim claim(ImageAsset asset) {
        return new MediaBackfillClaim(asset.getPublicId(), MediaAssetPurpose.PROFILE, asset.getBackfillIdentityHash());
    }

    private BackfillAssetSnapshot reserve() {
        return reservationService.reserveOrReuse(MediaPurpose.PROFILE, HASH, source());
    }

    private LegacyImageSource source() {
        return new LegacyImageSource("test-delivery", "legacy/photo.jpg", null, "legacy-etag", "image/jpeg", 1024);
    }

    private BackfillOriginalCopy copy(BackfillAssetSnapshot reserved, String version) {
        return new BackfillOriginalCopy(reserved.originalObjectKey(), version, "copy-etag-" + version,
                "image/jpeg", 1024, HASH);
    }

    private void pause() {
        jdbcTemplate.update("UPDATE media_pipeline_config SET issuance_enabled=FALSE WHERE id=1");
    }

    private int publicationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_publication", Integer.class);
    }

    private PublishAttempt awaitPublish() throws InterruptedException {
        PublishAttempt attempt = attempts.poll(10, TimeUnit.SECONDS);
        assertThat(attempt).isNotNull();
        return attempt;
    }

    private void awaitFailedPublication() {
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) publisherExecutor;
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(taskExecutor.getActiveCount()).isZero();
            assertThat(taskExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
            assertThat(publicationCount()).isEqualTo(1);
        });
    }

    private void awaitMySqlLockWait() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
                try (ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                    result.next();
                    return result.getInt(1) > 0;
                }
            });
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch interrupted", exception);
        }
    }

    private void assertMediaFailure(Runnable action, MediaErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", code);
    }

    private record PublishAttempt(MediaTransformRequest request, boolean inTransaction) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class Infrastructure {

        @Bean("japanClock")
        Clock japanClock() {
            return new TimeConfig().japanClock();
        }
    }
}
