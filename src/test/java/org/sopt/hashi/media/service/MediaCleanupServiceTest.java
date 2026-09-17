package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties.Mode;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorage;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException;
import org.sopt.hashi.media.internal.cleanup.MediaObjectPurgeResult;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidate;

class MediaCleanupServiceTest {

    private final UUID publicId = UUID.randomUUID();
    private final MediaPurgeWork work = new MediaPurgeWork(1L, publicId, UUID.randomUUID());
    private final MediaCleanupCandidate candidate = new MediaCleanupCandidate(1L, publicId,
            ImageProcessingStatus.READY, MediaCreationOrigin.DIRECT_UPLOAD, LocalDateTime.of(2026, 8, 1, 0, 0));
    private final MediaCleanupTransactionService transactions = mock(MediaCleanupTransactionService.class);
    private final MediaCleanupStorage storage = mock(MediaCleanupStorage.class);

    @AfterEach
    void 중단_플래그를_복원한다() {
        Thread.interrupted();
    }

    @Test
    void 정리를_시작한_경우에만_파일을_삭제하고_완료한다() {
        when(transactions.begin(1L, publicId)).thenReturn(Optional.of(work));
        when(storage.purgeAssetObjects(publicId)).thenReturn(new MediaObjectPurgeResult(true, 2));
        when(transactions.finish(work)).thenReturn(MediaCleanupOutcome.PURGED);

        assertThat(service(true, Mode.DELETE).clean(candidate)).isEqualTo(MediaCleanupOutcome.PURGED);

        var order = org.mockito.Mockito.inOrder(transactions, storage);
        order.verify(transactions).begin(1L, publicId);
        order.verify(storage).purgeAssetObjects(publicId);
        order.verify(transactions).finish(work);
    }

    @Test
    void 상태가_달라져_정리를_시작하지_못하면_S3를_호출하지_않는다() {
        when(transactions.begin(1L, publicId)).thenReturn(Optional.empty());

        assertThat(service(true, Mode.DELETE).clean(candidate)).isEqualTo(MediaCleanupOutcome.SKIPPED);
        verifyNoInteractions(storage);
        verify(transactions, never()).finish(any());
    }

    @Test
    void 일부_파일만_삭제했거나_실패하면_DB_완료를_호출하지_않는다() {
        when(transactions.begin(1L, publicId)).thenReturn(Optional.of(work));
        when(storage.purgeAssetObjects(publicId)).thenReturn(new MediaObjectPurgeResult(false, 2));

        assertThat(service(true, Mode.DELETE).clean(candidate)).isEqualTo(MediaCleanupOutcome.INCOMPLETE);

        when(storage.purgeAssetObjects(publicId)).thenThrow(
                new MediaCleanupStorageException(MediaCleanupStorageException.Reason.PARTIAL_DELETE));
        assertThatThrownBy(() -> service(true, Mode.DELETE).clean(candidate))
                .isInstanceOf(MediaCleanupStorageException.class);
        verify(transactions, never()).finish(any());
    }

    @Test
    void 중단된_작업은_같은_식별자로_재개한_경우만_삭제한다() {
        when(transactions.resume(work)).thenReturn(Optional.of(work));
        when(storage.purgeAssetObjects(publicId)).thenReturn(new MediaObjectPurgeResult(true, 0));
        when(transactions.finish(work)).thenReturn(MediaCleanupOutcome.ALREADY_PURGED);

        assertThat(service(true, Mode.DELETE).resume(work)).isEqualTo(MediaCleanupOutcome.ALREADY_PURGED);
        verify(transactions, never()).begin(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void DRY_RUN은_적격_여부만_확인하고_DB_전이나_S3를_호출하지_않는다() {
        when(transactions.isEligible(1L, publicId)).thenReturn(true);
        when(transactions.isResumable(work)).thenReturn(true);

        MediaCleanupService service = service(true, Mode.DRY_RUN);
        assertThat(service.clean(candidate)).isEqualTo(MediaCleanupOutcome.WOULD_PURGE);
        assertThat(service.resume(work)).isEqualTo(MediaCleanupOutcome.WOULD_PURGE);
        verify(transactions, never()).begin(org.mockito.ArgumentMatchers.anyLong(), any());
        verify(transactions, never()).resume(any());
        verify(transactions, never()).finish(any());
        verifyNoInteractions(storage);
    }

    @Test
    void 비활성일_때는_조회조차_시작하지_않는다() {
        MediaCleanupService service = service(false, Mode.DELETE);

        assertThat(service.clean(candidate)).isEqualTo(MediaCleanupOutcome.SKIPPED);
        assertThat(service.resume(work)).isEqualTo(MediaCleanupOutcome.SKIPPED);
        verifyNoInteractions(transactions, storage);
    }

    @Test
    void 이미_중단됐다면_DB_정리_기록을_만들지_않는다() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> service(true, Mode.DELETE).clean(candidate))
                .isInstanceOf(MediaCleanupStorageException.class);
        verifyNoInteractions(transactions, storage);
    }

    @Test
    void 파일_삭제_직후_중단됐어도_DB_완료를_호출하지_않는다() {
        when(transactions.begin(1L, publicId)).thenReturn(Optional.of(work));
        when(storage.purgeAssetObjects(publicId)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new MediaObjectPurgeResult(true, 2);
        });

        assertThatThrownBy(() -> service(true, Mode.DELETE).clean(candidate))
                .isInstanceOf(MediaCleanupStorageException.class);
        verify(transactions, never()).finish(any());
    }

    private MediaCleanupService service(boolean enabled, Mode mode) {
        return new MediaCleanupService(transactions, storage, new MediaCleanupProperties(enabled, mode,
                Duration.ofMinutes(30), null, null, 0, 0, 0, 0, null, null, null, null, null));
    }
}
