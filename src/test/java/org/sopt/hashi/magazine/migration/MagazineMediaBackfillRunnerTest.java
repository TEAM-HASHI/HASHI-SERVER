package org.sopt.hashi.magazine.migration;

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
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillAssetInfo.State;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Status;

class MagazineMediaBackfillRunnerTest {

    private static final String IDENTITY = "a".repeat(64);

    private final MagazineMediaBackfillCandidateReader candidateReader =
            mock(MagazineMediaBackfillCandidateReader.class);
    private final MagazineMediaBackfillCheckpointStore checkpointStore =
            mock(MagazineMediaBackfillCheckpointStore.class);
    private final MagazineMediaBackfillAttachmentService attachmentService =
            mock(MagazineMediaBackfillAttachmentService.class);
    private final MediaBackfillPort mediaBackfillPort = mock(MediaBackfillPort.class);

    @Test
    void DRY_RUN은_inspect만_수행하고_DB나_asset을_변경하지_않는다() {
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.DRY_RUN, "", 2, 1, 3);
        MagazineMediaBackfillCandidate first = candidate(1L);
        MagazineMediaBackfillCandidate second = candidate(2L);
        given(candidateReader.findUpperBound(properties.target())).willReturn(2L);
        given(candidateReader.findBatch(properties.target(), 0L, 2L, 2))
                .willReturn(List.of(first, second));
        given(candidateReader.findBatch(properties.target(), 2L, 2L, 1))
                .willReturn(List.of());
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.COMPLETED);
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
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.PREPARE, runId.toString(), 2, 1, 3);
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

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.COMPLETED);
        assertThat(summary.preparedCount()).isEqualTo(1);
        verify(mediaBackfillPort).prepare(any(), eq(IDENTITY));
        verify(checkpointStore).recordProgress(
                lease, 1L, MagazineMediaBackfillOutcome.PREPARED, properties.leaseDuration());
    }

    @Test
    void ATTACH는_READY일_때만_aggregate_transaction에_연결과_cursor를_위임한다() {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.ATTACH, runId.toString(), 2, 1, 3);
        Lease lease = lease(runId, properties.mode(), 1L);
        MagazineMediaBackfillCandidate candidate = candidate(1L);
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
                .willReturn(MagazineMediaBackfillOutcome.ATTACHED);
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 0, 1, 0, 0));

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.attachedCount()).isEqualTo(1);
        verify(attachmentService).attachAndRecord(
                candidate, ready, lease, properties.leaseDuration());
        verify(checkpointStore, never()).recordProgress(
                eq(lease), eq(1L), any(), eq(properties.leaseDuration()));
    }

    @Test
    void 일시적인_storage_장애는_제한된_횟수만_재시도한다() {
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.DRY_RUN, "", 2, 1, 3);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1);
        verify(mediaBackfillPort, times(3)).inspect(any());
    }

    @Test
    void 다른_worker가_lease를_보유하면_후보를_처리하지_않는다() {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.ATTACH, runId.toString(), 2, 1, 3);
        Snapshot busy = snapshot(
                runId, properties.mode(), Status.RUNNING, 10L, 3L, 3, 0, 1, 2, 0);
        given(candidateReader.findUpperBound(properties.target())).willReturn(10L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.target()), eq(properties.mode()), eq(10L), any()))
                .willReturn(new Acquisition(AcquisitionState.BUSY, null, busy));

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.BUSY);
        verify(candidateReader, never()).findBatch(any(), anyLong(), anyLong(), anyInt());
        verify(mediaBackfillPort, never()).inspect(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void batch_상한에서_pause에_실패하면_lease_상실로_보고한다(boolean paused) {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.PREPARE, runId.toString(), 1, 1, 1);
        Lease lease = lease(runId, properties.mode(), 2L);
        given(candidateReader.findUpperBound(properties.target())).willReturn(2L);
        given(checkpointStore.acquire(eq(runId), eq(properties.target()), eq(properties.mode()), eq(2L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 2L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(properties.target(), 0L, 2L, 1)).willReturn(List.of(candidate(1L)));
        given(candidateReader.findBatch(properties.target(), 1L, 2L, 1)).willReturn(List.of(candidate(2L)));
        given(mediaBackfillPort.inspect(any())).willReturn(inspection(asset(State.PROCESSING)));
        given(checkpointStore.pause(lease)).willReturn(paused);
        given(checkpointStore.find(runId)).willReturn(snapshot(
                runId, properties.mode(), paused ? Status.PAUSED : Status.RUNNING, 2L, 1L, 1, 1, 0, 0, 0));

        assertThat(runner(properties).execute().status()).isEqualTo(paused
                ? MagazineMediaBackfillSummary.Status.PAUSED : MagazineMediaBackfillSummary.Status.LEASE_LOST);
        verify(checkpointStore, never()).complete(any());
    }

    @ParameterizedTest
    @EnumSource(value = State.class,
            names = {"PENDING_COPY", "PROCESSING", "FAILED", "EXPIRED", "BOUND", "RETIRED", "PURGING", "PURGED"})
    void READY가_아닌_사진은_ATTACH하지_않고_상태별_진행만_기록한다(State state) {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.ATTACH, runId.toString(), 2, 1, 1);
        Lease lease = lease(runId, properties.mode(), 1L);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(checkpointStore.acquire(eq(runId), eq(properties.target()), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2)).willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willReturn(inspection(asset(state)));
        boolean skipped = state == State.PENDING_COPY || state == State.PROCESSING;
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 0, 0, skipped ? 1 : 0, skipped ? 0 : 1));

        runner(properties).execute();

        MagazineMediaBackfillOutcome expected = skipped
                ? MagazineMediaBackfillOutcome.SKIPPED : MagazineMediaBackfillOutcome.FAILED;
        verify(checkpointStore).recordProgress(lease, 1L, expected, properties.leaseDuration());
        verify(attachmentService, never()).attachAndRecord(any(), any(), any(), any());
        verify(mediaBackfillPort, never()).prepare(any(), any());
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 각_target은_기존_media_marker로_조사하고_checkpoint는_변경하지_않는다(
            MagazineMediaBackfillTarget target
    ) {
        MagazineMediaBackfillProperties properties = new MagazineMediaBackfillProperties(
                true, "", target, MagazineMediaBackfillMode.DRY_RUN,
                2, 1, Duration.ofMinutes(5), 1, Duration.ZERO);
        given(candidateReader.findUpperBound(target)).willReturn(1L);
        given(candidateReader.findBatch(target, 0L, 1L, 2))
                .willReturn(List.of(new MagazineMediaBackfillCandidate(target, 1L, "magazines/shared.jpg")));
        MediaBackfillReference reference = new MediaBackfillReference(target.mediaTarget(), 1L, "magazines/shared.jpg");
        given(mediaBackfillPort.inspect(reference)).willReturn(new MediaBackfillInspectionInfo(
                IDENTITY, target.mediaTarget().purpose(), Optional.empty()));

        assertThat(runner(properties).execute().inspectedCount()).isEqualTo(1L);

        verify(mediaBackfillPort).inspect(reference);
        verifyNoInteractions(checkpointStore, attachmentService);
    }

    private MagazineMediaBackfillRunner runner(MagazineMediaBackfillProperties properties) {
        return new MagazineMediaBackfillRunner(
                properties, candidateReader, checkpointStore, attachmentService, mediaBackfillPort);
    }

    private MagazineMediaBackfillProperties properties(
            MagazineMediaBackfillMode mode,
            String runId,
            int batchSize,
            int maxBatches,
            int maxAttempts
    ) {
        return new MagazineMediaBackfillProperties(
                true, runId, MagazineMediaBackfillTarget.MAGAZINE_BANNER,
                mode, batchSize, maxBatches, Duration.ofMinutes(5), maxAttempts, Duration.ZERO);
    }

    private MagazineMediaBackfillCandidate candidate(long magazineId) {
        return new MagazineMediaBackfillCandidate(
                MagazineMediaBackfillTarget.MAGAZINE_BANNER,
                magazineId, "magazines/legacy.jpg");
    }

    private Lease lease(UUID runId, MagazineMediaBackfillMode mode, long upperBound) {
        return new Lease(
                runId, UUID.randomUUID(), MagazineMediaBackfillTarget.MAGAZINE_BANNER,
                mode, upperBound);
    }

    private Snapshot snapshot(
            UUID runId,
            MagazineMediaBackfillMode mode,
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
                runId, MagazineMediaBackfillTarget.MAGAZINE_BANNER, mode, status,
                upperBound, cursor, status == Status.RUNNING ? LocalDateTime.now().plusMinutes(1) : null,
                scanned, prepared, attached, skipped, failed);
    }

    private MediaBackfillInspectionInfo unpreparedInspection() {
        return new MediaBackfillInspectionInfo(
                IDENTITY, MediaAssetPurpose.MAGAZINE_BANNER, Optional.empty());
    }

    private MediaBackfillInspectionInfo inspection(MediaBackfillAssetInfo asset) {
        return new MediaBackfillInspectionInfo(
                IDENTITY, MediaAssetPurpose.MAGAZINE_BANNER, Optional.of(asset));
    }

    private MediaBackfillAssetInfo asset(State state) {
        return new MediaBackfillAssetInfo(
                UUID.randomUUID(), MediaAssetPurpose.MAGAZINE_BANNER, IDENTITY, state);
    }
}
