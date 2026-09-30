package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectLocation;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersion;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersionCursor;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersionPage;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationMetrics;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationProperties;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationProperties.Mode;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationStorage;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationStorageException;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationStorageException.Reason;
import org.sopt.hashi.media.service.MediaReconciliationScanResult.Status;

class MediaReconciliationScanServiceTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final MediaObjectVersion ORIGINAL = new MediaObjectVersion(
            MediaObjectLocation.ORIGINAL,
            "media/originals/" + ASSET_ID + "/original",
            "version-1",
            NOW.minus(Duration.ofDays(8))
    );

    private final MediaReconciliationTransactionService transactions =
            mock(MediaReconciliationTransactionService.class);
    private final MediaReconciliationStorage storage = mock(MediaReconciliationStorage.class);
    private final MediaReconciliationMetrics metrics =
            new MediaReconciliationMetrics(new SimpleMeterRegistry());
    private final AtomicLong nanoTime = new AtomicLong();

    @AfterEach
    void clearInterrupted() {
        Thread.interrupted();
    }

    @Test
    void DRY_RUN은_삭제_후보를_집계하지만_S3_delete를_호출하지_않는다() {
        stubOneOriginalPage();
        when(transactions.assess(eq(ORIGINAL), any())).thenReturn(MediaReconciliationDecision.DELETE);
        MediaReconciliationScanService scanner = scanner(Mode.DRY_RUN);

        MediaReconciliationScanResult result = scanner.scan();

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.inspected()).isEqualTo(1);
        assertThat(result.wouldDelete()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        verify(storage, never()).deleteObjectVersion(any());
    }

    @Test
    void 개별_삭제_실패는_다른_파일을_막지_않고_다음_scan에서_중복_안전하게_재시도한다() {
        MediaObjectVersion second = new MediaObjectVersion(
                MediaObjectLocation.ORIGINAL,
                "media/originals/" + UUID.fromString("f3c6347b-e39b-4e06-83a7-8c04e8768655") + "/original",
                "version-2", NOW.minus(Duration.ofDays(8)));
        when(storage.listObjectVersions(eq(MediaObjectLocation.ORIGINAL), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(ORIGINAL, second), null));
        when(storage.listObjectVersions(eq(MediaObjectLocation.RENDITION), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(), null));
        when(transactions.assess(any(), any())).thenReturn(MediaReconciliationDecision.DELETE);
        org.mockito.Mockito.doThrow(new MediaReconciliationStorageException(Reason.DELETE_FAILED))
                .doNothing().when(storage).deleteObjectVersion(ORIGINAL);
        MediaReconciliationScanService scanner = scanner(Mode.DELETE);

        MediaReconciliationScanResult first = scanner.scan();
        MediaReconciliationScanResult retried = scanner.scan();

        assertThat(first.status()).isEqualTo(Status.PARTIAL_FAILURE);
        assertThat(first.deleted()).isEqualTo(1);
        assertThat(first.failed()).isEqualTo(1);
        assertThat(retried.status()).isEqualTo(Status.COMPLETED);
        assertThat(retried.deleted()).isEqualTo(2);
        verify(storage, times(2)).deleteObjectVersion(ORIGINAL);
        verify(storage, times(2)).deleteObjectVersion(second);
    }

    @Test
    void 중단된_object는_다음_scan에서_같은_exact_version부터_재개한다() {
        MediaObjectVersionCursor next = new MediaObjectVersionCursor(ORIGINAL.objectKey(), ORIGINAL.versionId());
        when(storage.listObjectVersions(eq(MediaObjectLocation.ORIGINAL), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(ORIGINAL), next));
        when(transactions.assess(eq(ORIGINAL), any())).thenReturn(MediaReconciliationDecision.DELETE);
        org.mockito.Mockito.doThrow(new MediaReconciliationStorageException(Reason.INTERRUPTED))
                .when(storage).deleteObjectVersion(ORIGINAL);
        MediaReconciliationScanService scanner = scanner(Mode.DELETE);

        assertThat(scanner.scan().status()).isEqualTo(Status.INTERRUPTED);
        Thread.interrupted();
        reset(storage);
        when(storage.listObjectVersions(eq(MediaObjectLocation.RENDITION), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(), null));
        when(storage.listObjectVersions(eq(MediaObjectLocation.ORIGINAL), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(), null));

        scanner.scan();

        verify(storage, never()).listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100);
        verify(storage).deleteObjectVersion(ORIGINAL);
    }

    @Test
    void 시간_제한_뒤에는_같은_page의_다음_object부터_이어간다() {
        MediaObjectVersion second = new MediaObjectVersion(
                MediaObjectLocation.ORIGINAL,
                "media/originals/" + UUID.fromString("f3c6347b-e39b-4e06-83a7-8c04e8768655") + "/original",
                "version-2", NOW.minus(Duration.ofDays(8)));
        when(storage.listObjectVersions(eq(MediaObjectLocation.ORIGINAL), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(ORIGINAL, second), null));
        when(storage.listObjectVersions(eq(MediaObjectLocation.RENDITION), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(), null));
        when(transactions.assess(eq(ORIGINAL), any())).thenAnswer(invocation -> {
            nanoTime.set(Duration.ofMinutes(2).toNanos());
            return MediaReconciliationDecision.PROTECT;
        });
        when(transactions.assess(eq(second), any())).thenReturn(MediaReconciliationDecision.PROTECT);
        MediaReconciliationScanService scanner = scanner(Mode.DRY_RUN);

        MediaReconciliationScanResult first = scanner.scan();
        nanoTime.set(0);
        MediaReconciliationScanResult resumed = scanner.scan();

        assertThat(first.status()).isEqualTo(Status.TIME_LIMIT_REACHED);
        assertThat(first.inspected()).isEqualTo(1);
        assertThat(resumed.status()).isEqualTo(Status.COMPLETED);
        assertThat(resumed.inspected()).isEqualTo(1);
        verify(transactions).assess(eq(ORIGINAL), any());
        verify(transactions).assess(eq(second), any());
        verify(storage).listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100);
    }

    private void stubOneOriginalPage() {
        when(storage.listObjectVersions(eq(MediaObjectLocation.ORIGINAL), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(ORIGINAL), null));
        when(storage.listObjectVersions(eq(MediaObjectLocation.RENDITION), any(), eq(100)))
                .thenReturn(new MediaObjectVersionPage(List.of(), null));
    }

    private MediaReconciliationScanService scanner(Mode mode) {
        MediaReconciliationProperties properties = new MediaReconciliationProperties(
                true, mode, Duration.ofDays(7), Duration.ofHours(6), 100, 2,
                Duration.ofMinutes(2), Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(20));
        return new MediaReconciliationScanService(transactions, storage, properties, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC), nanoTime::get);
    }
}
