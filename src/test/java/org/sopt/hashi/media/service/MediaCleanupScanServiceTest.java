package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupMetrics;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCandidate;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCandidateReader;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCursor;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidate;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateCursor;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateReader;
import org.sopt.hashi.media.internal.recovery.MediaRecoveryProperties;
import org.sopt.hashi.media.service.MediaCleanupScanResult.Status;

class MediaCleanupScanServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final MediaCreationOrigin DIRECT = MediaCreationOrigin.DIRECT_UPLOAD;
    private static final ImageBindingStatus UNBOUND = ImageBindingStatus.UNBOUND;
    private static final ImageProcessingStatus PENDING = ImageProcessingStatus.PENDING_UPLOAD;

    private final MediaCleanupService cleanup = mock(MediaCleanupService.class);
    private final MediaCleanupCandidateReader candidates = mock(MediaCleanupCandidateReader.class);
    private final MediaPurgeCandidateReader purges = mock(MediaPurgeCandidateReader.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MediaCleanupMetrics metrics = new MediaCleanupMetrics(registry);
    private final AtomicLong nanoTime = new AtomicLong();
    private final Map<Group, List<MediaCleanupCandidate>> rows = new HashMap<>();
    private final List<MediaPurgeCandidate> pendingPurges = new ArrayList<>();
    private final MediaRecoveryProperties retention = new MediaRecoveryProperties(false, null, null, 0,
            null, null, 0, 0, 0, null, null, null, null, null);

    @BeforeEach
    void 정렬된_조회와_처리_결과를_준비한다() {
        when(candidates.findUnboundBatch(any(), anyCollection(), any(), any(), anyInt()))
                .thenAnswer(invocation -> read(new Group(invocation.getArgument(0), UNBOUND,
                                invocation.<Collection<ImageProcessingStatus>>getArgument(1).iterator().next()),
                        invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4)));
        when(candidates.findFailedBoundBatch(any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> read(new Group(invocation.getArgument(0), ImageBindingStatus.BOUND,
                                ImageProcessingStatus.FAILED), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3)));
        when(purges.findBatch(any(), any(), anyInt())).thenAnswer(invocation -> {
            LocalDateTime before = invocation.getArgument(0);
            MediaPurgeCursor cursor = invocation.getArgument(1);
            int size = invocation.getArgument(2);
            return pendingPurges.stream().filter(row -> !row.lastAttemptAt().isAfter(before))
                    .filter(row -> row.lastAttemptAt().isAfter(cursor.lastAttemptAt())
                            || (row.lastAttemptAt().equals(cursor.lastAttemptAt())
                            && row.work().assetId() > cursor.assetId())).limit(size).toList();
        });
        when(cleanup.clean(any())).thenReturn(MediaCleanupOutcome.WOULD_PURGE);
        when(cleanup.resume(any())).thenReturn(MediaCleanupOutcome.WOULD_PURGE);
    }

    @AfterEach
    void 중단_플래그와_지표를_정리한다() {
        Thread.interrupted();
        registry.close();
    }

    @Test
    void 기본_한도는_범주별_50개가_아니라_실행_전체에서_50개다() {
        put(DIRECT, UNBOUND, PENDING, 1, 40);
        put(DIRECT, UNBOUND, ImageProcessingStatus.EXPIRED, 101, 40);
        MediaCleanupScanService scanner = scanner(true, 25, 2);

        assertThat(scanner.scan()).isEqualTo(new MediaCleanupScanResult(Status.WORK_LIMIT_REACHED, 50, 2, 0));
        assertThat(scanner.scan()).isEqualTo(new MediaCleanupScanResult(Status.COMPLETED, 30, 2, 0));

        ArgumentCaptor<MediaCleanupCandidate> attempted = ArgumentCaptor.forClass(MediaCleanupCandidate.class);
        verify(cleanup, times(80)).clean(attempted.capture());
        assertThat(attempted.getAllValues()).extracting(MediaCleanupCandidate::assetId).doesNotHaveDuplicates();
    }

    @Test
    void 한_범주에_계속_데이터가_있어도_재개와_열_범주가_차례로_처리된다() {
        long id = 1;
        for (MediaCreationOrigin origin : MediaCreationOrigin.values()) {
            for (ImageProcessingStatus state : List.of(PENDING, ImageProcessingStatus.EXPIRED,
                    ImageProcessingStatus.READY, ImageProcessingStatus.FAILED)) {
                put(origin, UNBOUND, state, id, 3);
                id += 10;
            }
            put(origin, ImageBindingStatus.BOUND, ImageProcessingStatus.FAILED, id, 3);
            id += 10;
        }
        for (long purgeId = 201; purgeId < 204; purgeId++) {
            pendingPurges.add(new MediaPurgeCandidate(new MediaPurgeWork(purgeId,
                    new UUID(0, purgeId), new UUID(1, purgeId)), NOW.minusHours(1)));
        }
        MediaCleanupScanService scanner = scanner(true, 2, 1);

        for (int run = 0; run < 11; run++) {
            assertThat(scanner.scan().attemptedCount()).isEqualTo(2);
        }

        ArgumentCaptor<MediaCleanupCandidate> attempted = ArgumentCaptor.forClass(MediaCleanupCandidate.class);
        verify(cleanup, times(20)).clean(attempted.capture());
        assertThat(attempted.getAllValues()).extracting(MediaCleanupCandidate::assetId).doesNotHaveDuplicates();
        assertThat(attempted.getAllValues().stream().map(row -> row.assetId() / 10).distinct()).hasSize(10);
        verify(cleanup, times(2)).resume(any());
    }

    @Test
    void 빈_범주는_한번씩만_조회하고_반복하지_않는다() {
        assertThat(scanner(true, 25, 2).scan()).isEqualTo(new MediaCleanupScanResult(Status.COMPLETED, 0, 0, 0));
        verify(candidates, times(8)).findUnboundBatch(any(), anyCollection(), any(), any(), eq(25));
        verify(candidates, times(2)).findFailedBoundBatch(any(), any(), any(), eq(25));
        verify(purges).findBatch(any(), eq(MediaPurgeCursor.initial()), eq(25));
        verifyNoInteractions(cleanup);
    }

    @Test
    void DRY_RUN의_짧은_페이지는_같은_실행에서_중복_처리하지_않는다() {
        put(DIRECT, UNBOUND, PENDING, 1, 1);
        MediaCleanupScanService scanner = scanner(true, 25, 10);

        assertThat(scanner.scan()).isEqualTo(new MediaCleanupScanResult(Status.COMPLETED, 1, 1, 0));
        assertThat(scanner.scan()).isEqualTo(new MediaCleanupScanResult(Status.COMPLETED, 1, 1, 0));
        verify(cleanup, times(2)).clean(any());
    }

    @Test
    void 실행_중간_시간_초과는_시도한_행까지만_전진하고_나머지는_다음에_처리한다() {
        put(DIRECT, UNBOUND, PENDING, 1, 2);
        when(cleanup.clean(any())).thenAnswer(invocation -> {
            nanoTime.set(Duration.ofMinutes(2).toNanos());
            return MediaCleanupOutcome.SKIPPED;
        });
        MediaCleanupScanService scanner = scanner(true, 25, 2);

        assertThat(scanner.scan()).isEqualTo(new MediaCleanupScanResult(Status.TIME_LIMIT_REACHED, 1, 1, 0));
        assertThat(scanner.scan()).isEqualTo(new MediaCleanupScanResult(Status.COMPLETED, 1, 1, 0));

        ArgumentCaptor<MediaCleanupCandidate> attempted = ArgumentCaptor.forClass(MediaCleanupCandidate.class);
        verify(cleanup, times(2)).clean(attempted.capture());
        assertThat(attempted.getAllValues()).extracting(MediaCleanupCandidate::assetId).containsExactly(1L, 2L);
        verify(candidates).findUnboundBatch(eq(DIRECT), eq(List.of(PENDING)), any(),
                eq(new MediaCleanupCandidateCursor(NOW.minusDays(10), 1)), eq(25));
    }

    @Test
    void 조회_중_시간이_끝나면_읽어둔_행을_처리하지_않는다() {
        List<MediaCleanupCandidate> batch = put(DIRECT, UNBOUND, PENDING, 1, 1);
        doAnswer(invocation -> {
            nanoTime.set(Duration.ofMinutes(2).toNanos());
            return batch;
        }).when(candidates).findUnboundBatch(eq(DIRECT), eq(List.of(PENDING)), any(), any(), anyInt());

        assertThat(scanner(true, 25, 2).scan().status()).isEqualTo(Status.TIME_LIMIT_REACHED);
        verifyNoInteractions(cleanup);
    }

    @Test
    void 실패한_항목_뒤의_행도_처리하고_정상_완료로_보고하지_않는다() {
        List<MediaCleanupCandidate> batch = put(DIRECT, UNBOUND, PENDING, 1, 2);
        when(cleanup.clean(batch.getFirst())).thenThrow(new IllegalStateException("private SQL detail"));

        assertThat(scanner(true, 25, 2).scan()).isEqualTo(new MediaCleanupScanResult(Status.PARTIAL_FAILURE, 2, 1, 1));
        verify(cleanup).clean(batch.getLast());
        assertThat(registry.get("hashi.media.cleanup.failure").tag("reason", "internal_error").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 특정_범주의_조회가_실패해도_다른_범주는_처리한다() {
        List<MediaCleanupCandidate> batch = put(DIRECT, UNBOUND, ImageProcessingStatus.EXPIRED, 1, 1);
        doThrow(new IllegalStateException("private provider detail")).when(candidates)
                .findUnboundBatch(eq(DIRECT), eq(List.of(PENDING)), any(), any(), anyInt());

        assertThat(scanner(true, 25, 2).scan()).isEqualTo(new MediaCleanupScanResult(Status.PARTIAL_FAILURE, 1, 1, 1));
        verify(cleanup).clean(batch.getFirst());
    }

    @Test
    void 부분_삭제는_재시도_필요로_보고한다() {
        put(DIRECT, UNBOUND, PENDING, 1, 1);
        when(cleanup.clean(any())).thenReturn(MediaCleanupOutcome.INCOMPLETE);

        assertThat(scanner(true, 25, 2).scan().status()).isEqualTo(Status.RETRY_PENDING);
    }

    @Test
    void 보존_기간은_origin과_상태에_맞게_조회에_반영한다() {
        scanner(true, 25, 2).scan();

        verify(candidates).findUnboundBatch(eq(DIRECT), eq(List.of(ImageProcessingStatus.READY)),
                eq(NOW.minusDays(1)), any(), eq(25));
        verify(candidates).findUnboundBatch(eq(MediaCreationOrigin.SYSTEM_BACKFILL), eq(List.of(PENDING)),
                eq(NOW.minusDays(7)), any(), eq(25));
        verify(candidates).findFailedBoundBatch(eq(DIRECT), eq(NOW.minusDays(7)), any(), eq(25));
        verify(purges).findBatch(eq(NOW.minusMinutes(15)), any(), eq(25));
    }

    @Test
    void 비활성이나_이미_중단된_실행은_DB를_조회하지_않는다() {
        assertThat(scanner(false, 25, 2).scan().status()).isEqualTo(Status.DISABLED);
        Thread.currentThread().interrupt();
        assertThat(scanner(true, 25, 2).scan().status()).isEqualTo(Status.INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(candidates, purges, cleanup);
    }

    @Test
    void 내부_중단_예외는_플래그를_복원하고_뒤의_행을_처리하지_않는다() {
        put(DIRECT, UNBOUND, PENDING, 1, 2);
        when(cleanup.clean(any())).thenThrow(
                new MediaCleanupStorageException(MediaCleanupStorageException.Reason.INTERRUPTED));

        assertThat(scanner(true, 25, 2).scan()).isEqualTo(new MediaCleanupScanResult(Status.INTERRUPTED, 1, 1, 1));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(cleanup).clean(any());
    }

    @Test
    void 동일_서비스의_동시_호출은_중복_실행하지_않고_종료_후_다시_실행한다() throws Exception {
        put(DIRECT, UNBOUND, PENDING, 1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(cleanup.clean(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return MediaCleanupOutcome.WOULD_PURGE;
        });
        MediaCleanupScanService scanner = scanner(true, 25, 2);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(scanner::scan);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(scanner.scan().status()).isEqualTo(Status.ALREADY_RUNNING);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(Status.COMPLETED);
            assertThat(scanner.scan().status()).isEqualTo(Status.COMPLETED);
            verify(cleanup, times(2)).clean(any());
        }
    }

    private MediaCleanupScanService scanner(boolean enabled, int batchSize, int maxBatches) {
        MediaCleanupProperties properties = new MediaCleanupProperties(enabled, MediaCleanupProperties.Mode.DRY_RUN,
                Duration.ofMinutes(30), null, null, batchSize, maxBatches, 0, 0, null, null, null, null, null);
        return new MediaCleanupScanService(cleanup, candidates, purges,
                new MediaCleanupEligibilityPolicy(retention, properties), properties, metrics, CLOCK, nanoTime::get);
    }

    private List<MediaCleanupCandidate> put(MediaCreationOrigin origin, ImageBindingStatus binding,
                                             ImageProcessingStatus state, long firstId, int count) {
        List<MediaCleanupCandidate> batch = LongStream.range(firstId, firstId + count)
                .mapToObj(id -> new MediaCleanupCandidate(id, new UUID(0, id), state, origin, NOW.minusDays(10)))
                .toList();
        rows.put(new Group(origin, binding, state), batch);
        return batch;
    }

    private List<MediaCleanupCandidate> read(Group group, LocalDateTime cutoff,
                                              MediaCleanupCandidateCursor cursor, int limit) {
        return rows.getOrDefault(group, List.of()).stream().filter(row -> !row.updatedAt().isAfter(cutoff))
                .filter(row -> row.updatedAt().isAfter(cursor.updatedAt())
                        || (row.updatedAt().equals(cursor.updatedAt()) && row.assetId() > cursor.assetId()))
                .limit(limit).toList();
    }

    private record Group(MediaCreationOrigin origin, ImageBindingStatus binding, ImageProcessingStatus state) {
    }
}
