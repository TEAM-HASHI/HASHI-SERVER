package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
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
import org.sopt.hashi.media.internal.backfill.LegacyImageSource;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorage;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException;
import org.sopt.hashi.media.internal.cleanup.MediaObjectPurgeResult;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCandidateReader;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCursor;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectLocation;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersion;
import org.sopt.hashi.media.internal.queue.MediaTransformFailedResult;
import org.sopt.hashi.media.internal.queue.MediaTransformFailureCode;
import org.sopt.hashi.media.internal.queue.MediaTransformRequestPublisher;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidate;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateCursor;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateReader;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorage;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
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
@Import(MediaCleanupTransactionIntegrationTest.Infrastructure.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "hashi.media.recovery.enabled=false",
        "hashi.media.cleanup.enabled=true",
        "hashi.media.cleanup.mode=DELETE",
        "hashi.media.cleanup.upload-safety-window=30m",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaCleanupTransactionIntegrationTest {

    private static final Instant START = Instant.parse("2026-09-04T12:00:00Z");
    private static final String CHECKSUM = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_cleanup").withUsername("hashi").withPassword("hashi");

    @Autowired
    private MediaCleanupService cleanup;
    @Autowired
    private MediaCleanupScanService scanner;
    @Autowired
    private MediaCleanupTransactionService transactions;
    @Autowired
    private MediaReconciliationTransactionService reconciliationTransactions;
    @Autowired
    private MediaCleanupCandidateReader candidateReader;
    @Autowired
    private MediaPurgeCandidateReader purgeReader;
    @Autowired
    private MediaAssetTransactionService uploadTransactions;
    @Autowired
    private MediaBackfillTransactionService backfillTransactions;
    @Autowired
    private MediaTransformResultService resultService;
    @Autowired
    private ImageAssetRepository assets;
    @Autowired
    private MediaSpecRegistry specs;
    @Autowired
    private MediaPort mediaPort;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @MockitoBean
    private MediaCleanupStorage storage;
    @MockitoBean
    private CurrentActorProvider actors;
    @MockitoBean
    private FileStorage fileStorage;
    @MockitoBean
    private MediaOriginalStorage originalStorage;
    @MockitoBean
    private MediaTransformRequestPublisher publisher;
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    private ExecutorService executor;

    @BeforeEach
    void 격리된_DB와_시각을_준비한다() {
        clock.reset();
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM image_rendition");
        jdbc.update("DELETE FROM image_asset");
        jdbc.update("UPDATE media_pipeline_config SET issuance_enabled=TRUE WHERE id=1");
        executor = Executors.newFixedThreadPool(3);
        when(actors.currentActor()).thenReturn(new CurrentActor(ActorType.USER, 1L));
        when(storage.purgeAssetObjects(any())).thenReturn(new MediaObjectPurgeResult(true, 0));
    }

    @AfterEach
    void 실행중인_테스트_작업과_이벤트를_정리한다() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(publications()).isZero());
        verifyNoInteractions(originalStorage, fileStorage);
    }

    @Test
    void PURGING_커밋을_확인한_뒤_트랜잭션_밖에서_파일을_삭제하고_부모와_자식을_정리한다() {
        ImageAsset asset = fixture(ImageProcessingStatus.READY, false, false, Duration.ofDays(2));
        assertThat(renditionCount(asset)).isEqualTo(1);
        when(storage.purgeAssetObjects(asset.getPublicId())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ImageAsset committed = reload(asset);
            assertThat(committed.getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGING);
            assertThat(committed.getPurgeToken()).isNotNull();
            assertThat(renditionCount(asset)).isEqualTo(1);
            transactionTemplate.executeWithoutResult(status ->
                    assertThat(assets.findByIdForUpdate(asset.getId())).isPresent());
            return new MediaObjectPurgeResult(true, 3);
        });

        assertThat(cleanup.clean(candidate(asset))).isEqualTo(MediaCleanupOutcome.PURGED);

        assertThat(assets.findById(asset.getId())).isEmpty();
        assertThat(renditionCount(asset)).isZero();
        assertThat(publications()).isZero();
    }

    @Test
    void 만료_상태와_미완료_업로드도_파일_정리_완료_뒤에만_행을_지운다() {
        ImageAsset pending = fixture(ImageProcessingStatus.PENDING_UPLOAD, false, false, Duration.ofDays(2));
        ImageAsset expired = fixture(ImageProcessingStatus.EXPIRED, false, false, Duration.ofDays(2));

        assertThat(cleanup.clean(candidate(pending))).isEqualTo(MediaCleanupOutcome.PURGED);
        assertThat(cleanup.clean(candidate(expired))).isEqualTo(MediaCleanupOutcome.PURGED);

        assertThat(assets.count()).isZero();
        verify(storage, times(2)).purgeAssetObjects(any());
    }

    @Test
    void 연결된_실패는_파일만_정리하고_공개_실패_상태와_식별자를_유지한다() {
        ImageAsset asset = fixture(ImageProcessingStatus.FAILED, false, true, Duration.ofDays(8));
        MediaImageRequest request = new MediaImageRequest(asset.getPublicId(), MediaImageRole.REVIEW_DETAIL);
        when(storage.purgeAssetObjects(asset.getPublicId())).thenAnswer(invocation -> {
            assertThat(mediaPort.findImages(List.of(request)).get(request).status()).isEqualTo(MediaImageStatus.FAILED);
            return new MediaObjectPurgeResult(true, 1);
        });

        assertThat(cleanup.clean(candidate(asset))).isEqualTo(MediaCleanupOutcome.PURGED);

        ImageAsset purged = reload(asset);
        assertThat(purged.getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGED);
        assertThat(purged.getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
        assertThat(purged.getProcessingStatus()).isEqualTo(ImageProcessingStatus.FAILED);
        assertThat(purged.getLastFailureCode()).isEqualTo("INVALID_IMAGE_DATA");
        assertThat(purged.getObjectsPurgedAt()).isEqualTo(now());
        var image = mediaPort.findImages(List.of(request)).get(request);
        assertThat(image.status()).isEqualTo(MediaImageStatus.FAILED);
        assertThat(image.defaultSource()).isNull();
        assertThat(image.sourceSets()).isEmpty();
    }

    @Test
    void 일반_미연결_실패는_지우고_backfill_실패는_같은_identity의_기록을_보존한다() {
        ImageAsset direct = fixture(ImageProcessingStatus.FAILED, false, false, Duration.ofDays(8));
        ImageAsset backfill = fixture(ImageProcessingStatus.FAILED, true, false, Duration.ofDays(8));

        assertThat(cleanup.clean(candidate(direct))).isEqualTo(MediaCleanupOutcome.PURGED);
        assertThat(cleanup.clean(candidate(backfill))).isEqualTo(MediaCleanupOutcome.PURGED);

        assertThat(assets.findById(direct.getId())).isEmpty();
        assertThat(reload(backfill).getBackfillIdentityHash()).isEqualTo(backfill.getBackfillIdentityHash());
        var reservation = backfillTransactions.findReservation(MediaPurpose.REVIEW, backfill.getBackfillIdentityHash(),
                new LegacyImageSource("test-delivery", "legacy/photo.jpg", null, "etag", "image/jpeg", 1024));
        assertThat(reservation).hasValueSatisfying(value -> {
            assertThat(value.assetId()).isEqualTo(backfill.getPublicId());
            assertThat(value.processingStatus()).isEqualTo(ImageProcessingStatus.FAILED);
        });
        assertThat(assets.count()).isEqualTo(1);
    }

    @Test
    void 일부_삭제나_응답_유실은_PURGING을_남기고_같은_token으로_재개한다() {
        ImageAsset asset = fixture(ImageProcessingStatus.FAILED, true, false, Duration.ofDays(8));
        when(storage.purgeAssetObjects(asset.getPublicId())).thenReturn(new MediaObjectPurgeResult(false, 1));
        LocalDateTime started = now();

        assertThat(cleanup.clean(candidate(asset))).isEqualTo(MediaCleanupOutcome.INCOMPLETE);
        ImageAsset firstAttempt = reload(asset);
        MediaPurgeWork work = work(firstAttempt);
        assertThat(cleanup.resume(work)).isEqualTo(MediaCleanupOutcome.SKIPPED);
        clock.advance(Duration.ofMinutes(15));
        when(storage.purgeAssetObjects(asset.getPublicId())).thenThrow(
                new MediaCleanupStorageException(MediaCleanupStorageException.Reason.STORAGE_UNAVAILABLE));
        assertThatThrownBy(() -> cleanup.resume(work)).isInstanceOf(MediaCleanupStorageException.class);
        assertThat(reload(asset).getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGING);
        assertThat(reload(asset).getPurgeToken()).isEqualTo(work.purgeToken());
        assertThat(reload(asset).getPurgeLastAttemptAt()).isEqualTo(started.plusMinutes(15));

        clock.advance(Duration.ofMinutes(15));
        doReturn(new MediaObjectPurgeResult(true, 0)).when(storage).purgeAssetObjects(asset.getPublicId());
        assertThat(cleanup.resume(work)).isEqualTo(MediaCleanupOutcome.PURGED);

        assertThat(reload(asset).getPurgeToken()).isEqualTo(work.purgeToken());
        assertThat(reload(asset).getPurgeStartedAt()).isEqualTo(started);
        assertThat(reload(asset).getPurgeLastAttemptAt()).isEqualTo(now());
        verify(storage, times(3)).purgeAssetObjects(asset.getPublicId());
    }

    @Test
    void DRY_RUN은_재개_가능성만_확인하고_정리_기록을_바꾸지_않는다() {
        ImageAsset asset = fixture(ImageProcessingStatus.READY, false, false, Duration.ofDays(2));
        MediaCleanupService dryRun = new MediaCleanupService(transactions, storage,
                new MediaCleanupProperties(true, MediaCleanupProperties.Mode.DRY_RUN,
                        Duration.ofMinutes(30), null, null, 0, 0, 0, 0, null, null, null, null, null));
        Map<String, Object> before = row(asset);

        assertThat(dryRun.clean(candidate(asset))).isEqualTo(MediaCleanupOutcome.WOULD_PURGE);
        assertThat(row(asset)).isEqualTo(before);
        MediaPurgeWork work = transactions.begin(asset.getId(), asset.getPublicId()).orElseThrow();
        clock.advance(Duration.ofMinutes(15));
        before = row(asset);
        assertThat(dryRun.resume(work)).isEqualTo(MediaCleanupOutcome.WOULD_PURGE);
        assertThat(row(asset)).isEqualTo(before);
        verifyNoInteractions(storage);
    }

    @Test
    void 중복_완료는_RETIRE된_실패_tombstone을_삭제하지_않는다() {
        ImageAsset asset = fixture(ImageProcessingStatus.FAILED, false, true, Duration.ofDays(8));
        MediaPurgeWork work = transactions.begin(asset.getId(), asset.getPublicId()).orElseThrow();
        assertThat(transactions.finish(work)).isEqualTo(MediaCleanupOutcome.PURGED);
        transactionTemplate.executeWithoutResult(status ->
                mediaPort.reconcileBindings(List.of(), List.of(use(asset))));
        Map<String, Object> before = row(asset);

        assertThat(transactions.finish(work)).isEqualTo(MediaCleanupOutcome.ALREADY_PURGED);

        assertThat(row(asset)).isEqualTo(before);
        assertThat(reload(asset).getBindingStatus()).isEqualTo(ImageBindingStatus.RETIRED);
    }

    @Test
    void 다른_token이나_asset의_재개와_완료는_아무_상태도_바꾸지_않는다() {
        ImageAsset asset = fixture(ImageProcessingStatus.FAILED, true, false, Duration.ofDays(8));
        MediaPurgeWork actual = transactions.begin(asset.getId(), asset.getPublicId()).orElseThrow();
        clock.advance(Duration.ofMinutes(15));
        Map<String, Object> before = row(asset);
        for (MediaPurgeWork wrong : List.of(new MediaPurgeWork(asset.getId(), asset.getPublicId(), UUID.randomUUID()),
                new MediaPurgeWork(asset.getId(), UUID.randomUUID(), actual.purgeToken()))) {
            assertThat(transactions.resume(wrong)).isEmpty();
            assertThat(transactions.finish(wrong)).isEqualTo(MediaCleanupOutcome.SKIPPED);
        }
        assertThat(row(asset)).isEqualTo(before);
        verifyNoInteractions(storage);
    }

    @Test
    void 호출자_트랜잭션이_열려있으면_정리_오케스트레이션을_거부한다() {
        ImageAsset asset = fixture(ImageProcessingStatus.READY, false, false, Duration.ofDays(2));

        assertThatThrownBy(() -> transactionTemplate.execute(status -> cleanup.clean(candidate(asset))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactionTemplate.execute(status -> scanner.scan()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(reload(asset).getCleanupStatus()).isEqualTo(MediaCleanupStatus.ACTIVE);
        verifyNoInteractions(storage);
    }

    @Test
    void 실제_스캔은_만료와_미연결_이미지만_지우고_정상_연결과_실패_슬롯은_보존한다() {
        ImageAsset pending = fixture(ImageProcessingStatus.PENDING_UPLOAD, false, false, Duration.ofDays(2));
        ImageAsset ready = fixture(ImageProcessingStatus.READY, false, false, Duration.ofDays(2));
        ImageAsset bound = fixture(ImageProcessingStatus.READY, false, true, Duration.ofDays(2));
        ImageAsset failed = fixture(ImageProcessingStatus.FAILED, false, true, Duration.ofDays(8));

        int attempted = scanner.scan().attemptedCount() + scanner.scan().attemptedCount();

        assertThat(attempted).isEqualTo(3);
        assertThat(assets.findById(pending.getId())).isEmpty();
        assertThat(assets.findById(ready.getId())).isEmpty();
        assertThat(reload(bound).getCleanupStatus()).isEqualTo(MediaCleanupStatus.ACTIVE);
        assertThat(reload(failed).getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGED);
        verify(storage, times(3)).purgeAssetObjects(any());
    }

    @Test
    void 콘텐츠_연결이_먼저_잠그면_정리는_기다린_뒤_BOUND를_확인하고_제외한다() throws Exception {
        ImageAsset asset = fixture(ImageProcessingStatus.READY, false, false, Duration.ofDays(2));
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> claim = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            mediaPort.reconcileBindings(List.of(use(asset)), List.of());
            assets.flush();
            changed.countDown();
            awaitLatch(release);
        }));
        awaitLatch(changed);
        Future<MediaCleanupOutcome> purge = executor.submit(() -> cleanup.clean(candidate(asset)));
        try {
            awaitAssetLockWait();
            assertThat(purge.isDone()).isFalse();
        } finally {
            release.countDown();
        }
        claim.get(20, TimeUnit.SECONDS);

        assertThat(purge.get(20, TimeUnit.SECONDS)).isEqualTo(MediaCleanupOutcome.SKIPPED);
        assertThat(reload(asset).getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
        assertThat(renditionCount(asset)).isEqualTo(1);
        verifyNoInteractions(storage);
    }

    @Test
    void 정리가_먼저_잠그면_콘텐츠_연결은_기다린_뒤_롤백된다() throws Exception {
        ImageAsset asset = fixture(ImageProcessingStatus.READY, false, false, Duration.ofDays(2));
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> purge = holdPurge(asset, changed, release);
        awaitLatch(changed);
        Future<?> claim = executor.submit(() -> assertMediaFailure(() -> transactionTemplate.executeWithoutResult(
                status -> mediaPort.reconcileBindings(List.of(use(asset)), List.of()))));
        try {
            awaitAssetLockWait();
            assertThat(claim.isDone()).isFalse();
        } finally {
            release.countDown();
        }
        purge.get(20, TimeUnit.SECONDS);
        claim.get(20, TimeUnit.SECONDS);

        assertThat(reload(asset).getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGING);
        assertThat(reload(asset).getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
        verifyNoInteractions(storage);
    }

    @Test
    void 정리_직후_늦은_완료요청은_잠금을_기다린_뒤_거부하며_이벤트를_남기지_않는다() throws Exception {
        ImageAsset asset = fixture(ImageProcessingStatus.PENDING_UPLOAD, false, false, Duration.ofDays(2));
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> purge = holdPurge(asset, changed, release);
        awaitLatch(changed);
        Future<?> complete = executor.submit(() -> assertMediaFailure(() -> uploadTransactions.completeAssets(
                new CurrentActor(ActorType.USER, 1L), List.of(asset.getPublicId()), Map.of(asset.getPublicId(),
                        new OriginalObjectMetadata(asset.getOriginalObjectKey(),
                                "source-v1", "etag", "image/jpeg", 1024))
        )));
        try {
            awaitAssetLockWait();
            assertThat(complete.isDone()).isFalse();
        } finally {
            release.countDown();
        }
        purge.get(20, TimeUnit.SECONDS);
        complete.get(20, TimeUnit.SECONDS);

        assertThat(reload(asset).getSourceVersionId()).isNull();
        assertThat(reload(asset).getCurrentJobId()).isNull();
        assertThat(publications()).isZero();
        verifyNoInteractions(storage, publisher);
    }

    @Test
    void 업로드_완료가_먼저_시작됐다면_정리는_나중에_만료되어도_진행중인_변환을_보호한다() throws Exception {
        ImageAsset asset = fixture(ImageProcessingStatus.PENDING_UPLOAD, false, false, Duration.ofDays(2));
        jdbc.update("UPDATE image_asset SET upload_expires_at=? WHERE id=?", now().plusMinutes(1), asset.getId());
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> complete = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            uploadTransactions.completeAssets(new CurrentActor(ActorType.USER, 1L), List.of(asset.getPublicId()),
                    Map.of(asset.getPublicId(), new OriginalObjectMetadata(asset.getOriginalObjectKey(),
                            "source-v1", "etag", "image/jpeg", 1024)));
            assets.flush();
            changed.countDown();
            awaitLatch(release);
        }));
        awaitLatch(changed);
        Future<MediaCleanupOutcome> purge = executor.submit(() -> cleanup.clean(candidate(asset)));
        try {
            awaitAssetLockWait();
            clock.advance(Duration.ofDays(2));
        } finally {
            release.countDown();
        }
        complete.get(20, TimeUnit.SECONDS);

        assertThat(purge.get(20, TimeUnit.SECONDS)).isEqualTo(MediaCleanupOutcome.SKIPPED);
        assertThat(reload(asset).getProcessingStatus()).isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(reload(asset).getSourceVersionId()).isEqualTo("source-v1");
        verifyNoInteractions(storage);
    }

    @Test
    void 잠금_대기_전에_읽은_시각으로_보존기간을_판단하지_않는다() throws Exception {
        ImageAsset asset = fixture(ImageProcessingStatus.READY, false, false, Duration.ofHours(23).plusMinutes(50));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            assets.findByIdForUpdate(asset.getId()).orElseThrow();
            locked.countDown();
            awaitLatch(release);
        }));
        awaitLatch(locked);
        Future<Optional<MediaPurgeWork>> purge = executor.submit(
                () -> transactions.begin(asset.getId(), asset.getPublicId()));
        try {
            awaitAssetLockWait();
            clock.advance(Duration.ofMinutes(15));
        } finally {
            release.countDown();
        }
        holder.get(20, TimeUnit.SECONDS);

        assertThat(purge.get(20, TimeUnit.SECONDS)).isPresent();
        assertThat(reload(asset).getPurgeStartedAt()).isEqualTo(now());
        verifyNoInteractions(storage);
    }

    @Test
    void 정리중인_이미지의_늦은_result는_잠금을_기다린_뒤_무시한다() throws Exception {
        ImageAsset asset = fixture(ImageProcessingStatus.FAILED, false, true, Duration.ofDays(8));
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> purge = holdPurge(asset, changed, release);
        awaitLatch(changed);
        MediaTransformFailedResult failed = new MediaTransformFailedResult(1, UUID.randomUUID(), asset.getPublicId(),
                1, digest(), "source-v1", "etag", MediaTransformFailureCode.INVALID_IMAGE_DATA);
        Future<MediaTransformResultApplication> result = executor.submit(() -> resultService.apply(failed));
        try {
            awaitAssetLockWait();
            assertThat(result.isDone()).isFalse();
        } finally {
            release.countDown();
        }
        purge.get(20, TimeUnit.SECONDS);

        assertThat(result.get(20, TimeUnit.SECONDS).disposition()).isEqualTo(MediaTransformResultDisposition.STALE);
        assertThat(reload(asset).getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGING);
        assertThat(reload(asset).getLastFailureCode()).isEqualTo("INVALID_IMAGE_DATA");
        verifyNoInteractions(storage);
    }

    @Test
    void 정리_대상과_중단_재개_조회는_실제_DB에서_중복없이_커서를_이동한다() {
        ImageAsset first = fixture(ImageProcessingStatus.FAILED, false, true, Duration.ofDays(8));
        ImageAsset second = fixture(ImageProcessingStatus.FAILED, false, true, Duration.ofDays(8));
        fixture(ImageProcessingStatus.READY, false, true, Duration.ofDays(20));
        var candidates = candidateReader.findFailedBoundBatch(MediaCreationOrigin.DIRECT_UPLOAD,
                now().minusDays(7), MediaCleanupCandidateCursor.initial(), 1);
        var next = candidateReader.findFailedBoundBatch(MediaCreationOrigin.DIRECT_UPLOAD,
                now().minusDays(7), candidates.getFirst().nextCursor(), 1);
        assertThat(candidates).extracting(MediaCleanupCandidate::publicId).containsExactly(first.getPublicId());
        assertThat(next).extracting(MediaCleanupCandidate::publicId).containsExactly(second.getPublicId());
        MediaPurgeWork firstWork = transactions.begin(first.getId(), first.getPublicId()).orElseThrow();
        MediaPurgeWork secondWork = transactions.begin(second.getId(), second.getPublicId()).orElseThrow();
        assertThat(purgeReader.findBatch(now().minusMinutes(15), MediaPurgeCursor.initial(), 10)).isEmpty();
        clock.advance(Duration.ofMinutes(15));

        var page = purgeReader.findBatch(now().minusMinutes(15), MediaPurgeCursor.initial(), 1);
        var nextPage = purgeReader.findBatch(now().minusMinutes(15), page.getFirst().nextCursor(), 1);

        assertThat(page.getFirst().work()).isEqualTo(firstWork);
        assertThat(nextPage.getFirst().work()).isEqualTo(secondWork);
        assertThat(transactions.resume(firstWork)).contains(firstWork);
        assertThat(purgeReader.findBatch(now().minusMinutes(15), MediaPurgeCursor.initial(), 10))
                .extracting(value -> value.work()).containsExactly(secondWork);
    }

    @Test
    void reconciliation은_실제_DB에서_canonical_원본과_manifest_spec을_보존한다() {
        ImageAsset asset = fixture(ImageProcessingStatus.READY, false, true, Duration.ofDays(8));
        Instant old = START.minus(Duration.ofDays(8));
        Instant cutoff = START.minus(Duration.ofDays(7));

        var canonical = new MediaObjectVersion(MediaObjectLocation.ORIGINAL,
                asset.getOriginalObjectKey(), "source-v1", old);
        var noncanonical = new MediaObjectVersion(MediaObjectLocation.ORIGINAL,
                asset.getOriginalObjectKey(), "source-v0", old);
        var historicalSpec = new MediaObjectVersion(MediaObjectLocation.RENDITION,
                "media/renditions/%s/v1/review-detail/1080.webp".formatted(asset.getPublicId()), "delivery-v1", old);

        assertThat(reconciliationTransactions.assess(canonical, cutoff))
                .isEqualTo(MediaReconciliationDecision.PROTECT);
        assertThat(reconciliationTransactions.assess(noncanonical, cutoff))
                .isEqualTo(MediaReconciliationDecision.DELETE);
        assertThat(reconciliationTransactions.assess(historicalSpec, cutoff))
                .isEqualTo(MediaReconciliationDecision.PROTECT);
    }

    @Test
    void reconciliation은_실패_spec과_PURGED_뒤_늦은_파일을_삭제_후보에_둔다() {
        ImageAsset failed = fixture(ImageProcessingStatus.FAILED, true, false, Duration.ofDays(8));
        Instant old = START.minus(Duration.ofDays(8));
        Instant cutoff = START.minus(Duration.ofDays(7));
        var partial = new MediaObjectVersion(MediaObjectLocation.RENDITION,
                "media/renditions/%s/v1/review-preview/135.webp".formatted(failed.getPublicId()), "partial", old);

        assertThat(reconciliationTransactions.assess(partial, cutoff))
                .isEqualTo(MediaReconciliationDecision.DELETE);
        assertThat(cleanup.clean(candidate(failed))).isEqualTo(MediaCleanupOutcome.PURGED);
        var late = new MediaObjectVersion(MediaObjectLocation.RENDITION,
                "media/renditions/%s/v1/review-detail/1080.webp".formatted(failed.getPublicId()), "late", old);
        assertThat(reconciliationTransactions.assess(late, cutoff))
                .isEqualTo(MediaReconciliationDecision.DELETE);
    }

    private Future<?> holdPurge(ImageAsset asset, CountDownLatch changed, CountDownLatch release) {
        return executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(transactions.begin(asset.getId(), asset.getPublicId())).isPresent();
            assets.flush();
            changed.countDown();
            awaitLatch(release);
        }));
    }

    private ImageAsset fixture(ImageProcessingStatus state, boolean backfill, boolean bound, Duration age) {
        UUID id = UUID.randomUUID();
        String key = "media/originals/" + id + "/original";
        ImageAsset asset = backfill
                ? ImageAsset.createSystemBackfill(id, MediaPurpose.REVIEW, key, "image/jpeg", 1024,
                now().minusDays(40), "a".repeat(64))
                : ImageAsset.createDirectUpload(id, MediaPurpose.REVIEW, MediaOwnerType.USER, 1L,
                key, "image/jpeg", 1024, now().minusDays(40));
        if (state == ImageProcessingStatus.EXPIRED) {
            asset.expireUpload();
        } else if (state != ImageProcessingStatus.PENDING_UPLOAD) {
            UUID job = UUID.randomUUID();
            asset.beginInitialProcessing("source-v1", "etag", 1, digest(), job, now().minus(age));
            if (bound) {
                asset.bind();
            }
            if (state == ImageProcessingStatus.FAILED) {
                asset.failCurrentProcessing(job, 1, digest(), "INVALID_IMAGE_DATA");
            } else if (state == ImageProcessingStatus.READY) {
                asset.addRendition(job, 1, digest(), ImageRole.REVIEW_PREVIEW, ImageFormat.WEBP,
                        135, 135, 100, "media/renditions/" + id + "/v1/review-preview/135.webp");
                asset.completeCurrentProcessing(job, 1, digest(), "image/jpeg", 1024, 400, 400, CHECKSUM);
            }
        }
        ImageAsset saved = transactionTemplate.execute(status -> assets.saveAndFlush(asset));
        jdbc.update("UPDATE image_asset SET updated_at=?, created_at=? WHERE id=?",
                now().minus(age), now().minus(age), saved.getId());
        return reload(saved);
    }

    private ImageAsset reload(ImageAsset asset) {
        return assets.findById(asset.getId()).orElseThrow();
    }

    private Map<String, Object> row(ImageAsset asset) {
        return jdbc.queryForMap("SELECT * FROM image_asset WHERE id=?", asset.getId());
    }

    private int renditionCount(ImageAsset asset) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM image_rendition WHERE image_asset_id=?",
                Integer.class, asset.getId());
    }

    private int publications() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_publication", Integer.class);
    }

    private MediaCleanupCandidate candidate(ImageAsset asset) {
        return new MediaCleanupCandidate(asset.getId(), asset.getPublicId(), asset.getProcessingStatus(),
                asset.getCreationOrigin(), asset.getUpdatedAt());
    }

    private MediaPurgeWork work(ImageAsset asset) {
        return new MediaPurgeWork(asset.getId(), asset.getPublicId(), asset.getPurgeToken());
    }

    private MediaAssetUse use(ImageAsset asset) {
        return new MediaAssetUse(asset.getPublicId(), MediaAssetPurpose.REVIEW);
    }

    private String digest() {
        return specs.find(1).orElseThrow().digest();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void assertMediaFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.INVALID_STATE);
    }

    private void awaitAssetLockWait() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
                try (ResultSet rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                        JOIN performance_schema.data_locks locks
                          ON waits.REQUESTING_ENGINE_LOCK_ID = locks.ENGINE_LOCK_ID AND waits.ENGINE = locks.ENGINE
                        WHERE locks.OBJECT_SCHEMA = 'hashi_cleanup' AND locks.OBJECT_NAME = 'image_asset'
                        """)) {
                    rows.next();
                    return rows.getInt(1) > 0;
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

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing(dateTimeProviderRef = "cleanupTestDateTimeProvider")
    static class Infrastructure {

        @Bean("japanClock")
        MutableClock japanClock() {
            return new MutableClock(new AtomicReference<>(START), ZoneId.of("Asia/Tokyo"));
        }

        @Bean
        DateTimeProvider cleanupTestDateTimeProvider(MutableClock clock) {
            return () -> Optional.of(LocalDateTime.now(clock));
        }
    }

    static class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void reset() {
            instant.set(START);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
