package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillAssetInfo.State;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Status;

class RestaurantMediaBackfillRunnerTest {

    private static final String IDENTITY = "a".repeat(64);

    private final RestaurantMediaBackfillCandidateReader candidateReader =
            mock(RestaurantMediaBackfillCandidateReader.class);
    private final RestaurantMediaBackfillCheckpointStore checkpointStore =
            mock(RestaurantMediaBackfillCheckpointStore.class);
    private final RestaurantMediaBackfillAttachmentService attachmentService =
            mock(RestaurantMediaBackfillAttachmentService.class);
    private final MediaBackfillPort mediaBackfillPort = mock(MediaBackfillPort.class);

    @Test
    void DRY_RUN은_inspect만_수행하고_DB나_asset을_변경하지_않는다() {
        RestaurantMediaBackfillProperties properties = properties(
                RestaurantMediaBackfillMode.DRY_RUN, "", 2, 1, 3);
        RestaurantMediaBackfillCandidate first = candidate(1L);
        RestaurantMediaBackfillCandidate second = candidate(2L);
        given(candidateReader.findUpperBound(properties.target())).willReturn(2L);
        given(candidateReader.findBatch(properties.target(), 0L, 2L, 2))
                .willReturn(List.of(first, second));
        given(candidateReader.findBatch(properties.target(), 2L, 2L, 1))
                .willReturn(List.of());
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.COMPLETED);
        assertThat(summary.scannedCount()).isEqualTo(2);
        assertThat(summary.inspectedCount()).isEqualTo(2);
        verify(mediaBackfillPort, times(2)).inspect(any());
        verify(mediaBackfillPort, never()).prepare(any(), any());
        verify(mediaBackfillPort, never()).claimReady(any());
        verify(checkpointStore, never()).acquire(any(), any(), any(), anyLong(), any());
    }

    @Test
    void PREPARE는_같은_identity를_준비하고_항목별_cursor를_기록한다() {
        UUID runId = UUID.randomUUID();
        RestaurantMediaBackfillProperties properties = properties(
                RestaurantMediaBackfillMode.PREPARE, runId.toString(), 2, 1, 3);
        Lease lease = lease(runId, properties.mode(), 1L);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.target()), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());
        given(mediaBackfillPort.prepare(any(), eq(IDENTITY)))
                .willReturn(asset(State.PROCESSING));
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 1, 0, 0, 0));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.COMPLETED);
        assertThat(summary.preparedCount()).isEqualTo(1);
        verify(mediaBackfillPort).prepare(any(), eq(IDENTITY));
        verify(checkpointStore).recordProgress(
                lease, 1L, RestaurantMediaBackfillOutcome.PREPARED, properties.leaseDuration());
    }

    @Test
    void ATTACH는_READY일_때만_aggregate_transaction에_연결과_cursor를_위임한다() {
        UUID runId = UUID.randomUUID();
        RestaurantMediaBackfillProperties properties = properties(
                RestaurantMediaBackfillMode.ATTACH, runId.toString(), 2, 1, 3);
        Lease lease = lease(runId, properties.mode(), 1L);
        RestaurantMediaBackfillCandidate candidate = candidate(1L);
        MediaBackfillAssetInfo ready = asset(State.READY);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.target()), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2))
                .willReturn(List.of(candidate));
        given(mediaBackfillPort.inspect(any())).willReturn(inspection(ready));
        given(attachmentService.attachAndRecord(
                candidate, ready, lease, properties.leaseDuration()))
                .willReturn(RestaurantMediaBackfillOutcome.ATTACHED);
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 0, 1, 0, 0));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.attachedCount()).isEqualTo(1);
        verify(attachmentService).attachAndRecord(
                candidate, ready, lease, properties.leaseDuration());
        verify(checkpointStore, never()).recordProgress(
                eq(lease), eq(1L), any(), eq(properties.leaseDuration()));
    }

    @Test
    void 일시적인_storage_장애는_제한된_횟수만_재시도한다() {
        RestaurantMediaBackfillProperties properties = properties(
                RestaurantMediaBackfillMode.DRY_RUN, "", 2, 1, 3);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1);
        verify(mediaBackfillPort, times(3)).inspect(any());
    }

    @Test
    void 다른_worker가_lease를_보유하면_후보를_처리하지_않는다() {
        UUID runId = UUID.randomUUID();
        RestaurantMediaBackfillProperties properties = properties(
                RestaurantMediaBackfillMode.ATTACH, runId.toString(), 2, 1, 3);
        Snapshot busy = snapshot(
                runId, properties.mode(), Status.RUNNING, 10L, 3L, 3, 0, 1, 2, 0);
        given(candidateReader.findUpperBound(properties.target())).willReturn(10L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.target()), eq(properties.mode()), eq(10L), any()))
                .willReturn(new Acquisition(AcquisitionState.BUSY, null, busy));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.BUSY);
        verify(candidateReader, never()).findBatch(any(), anyLong(), anyLong(), anyInt());
        verify(mediaBackfillPort, never()).inspect(any());
    }

    private RestaurantMediaBackfillRunner runner(RestaurantMediaBackfillProperties properties) {
        return new RestaurantMediaBackfillRunner(
                properties, candidateReader, checkpointStore, attachmentService, mediaBackfillPort);
    }

    private RestaurantMediaBackfillProperties properties(
            RestaurantMediaBackfillMode mode,
            String runId,
            int batchSize,
            int maxBatches,
            int maxAttempts
    ) {
        return new RestaurantMediaBackfillProperties(
                true, runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                mode, batchSize, maxBatches, Duration.ofMinutes(5), maxAttempts, Duration.ZERO);
    }

    private RestaurantMediaBackfillCandidate candidate(long associationId) {
        return new RestaurantMediaBackfillCandidate(
                RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                associationId, 100L, "restaurants/legacy.jpg");
    }

    private Lease lease(UUID runId, RestaurantMediaBackfillMode mode, long upperBound) {
        return new Lease(
                runId, UUID.randomUUID(), RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                mode, upperBound);
    }

    private Snapshot snapshot(
            UUID runId,
            RestaurantMediaBackfillMode mode,
            Status status,
            long upperBound,
            long cursor,
            long scanned,
            long prepared,
            long attached,
            long skipped,
            long failed
    ) {
        return new Snapshot(
                runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, mode, status,
                upperBound, cursor, status == Status.RUNNING ? LocalDateTime.now().plusMinutes(1) : null,
                scanned, prepared, attached, skipped, failed);
    }

    private MediaBackfillInspectionInfo unpreparedInspection() {
        return new MediaBackfillInspectionInfo(
                IDENTITY, MediaAssetPurpose.RESTAURANT, Optional.empty());
    }

    private MediaBackfillInspectionInfo inspection(MediaBackfillAssetInfo asset) {
        return new MediaBackfillInspectionInfo(
                IDENTITY, MediaAssetPurpose.RESTAURANT, Optional.of(asset));
    }

    private MediaBackfillAssetInfo asset(State state) {
        return new MediaBackfillAssetInfo(
                UUID.randomUUID(), MediaAssetPurpose.RESTAURANT, IDENTITY, state);
    }
}
