package org.sopt.hashi.magazine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 각_target은_저장된_상한에서_재개하고_full_batch후_완료와_실패_집계를_유지한다(
            MagazineMediaBackfillTarget target
    ) {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillMode mode = MagazineMediaBackfillMode.PREPARE;
        MagazineMediaBackfillProperties properties = new MagazineMediaBackfillProperties(
                true, runId.toString(), target, mode, 2, 1, Duration.ofMinutes(5), 3, Duration.ZERO);
        Lease lease = new Lease(runId, UUID.randomUUID(), target, mode, 4L);
        Snapshot running = new Snapshot(runId, target, mode, Status.RUNNING,
                4L, 2L, LocalDateTime.now().plusMinutes(5), 2, 2, 0, 0, 0);
        Snapshot completed = new Snapshot(runId, target, mode, Status.COMPLETED,
                4L, 4L, null, 4, 3, 0, 0, 1);
        MagazineMediaBackfillCandidate first = new MagazineMediaBackfillCandidate(target, 3L, "magazines/a.jpg");
        MagazineMediaBackfillCandidate second = new MagazineMediaBackfillCandidate(target, 4L, "magazines/b.jpg");
        MediaBackfillReference firstReference = new MediaBackfillReference(target.mediaTarget(), 3L, first.legacyKey());
        MediaBackfillReference secondReference = new MediaBackfillReference(target.mediaTarget(), 4L, second.legacyKey());
        MediaBackfillAssetInfo ready = new MediaBackfillAssetInfo(
                UUID.randomUUID(), target.mediaTarget().purpose(), IDENTITY, State.READY);
        given(candidateReader.findUpperBound(target)).willReturn(9L);
        given(checkpointStore.acquire(eq(runId), eq(target), eq(mode), eq(9L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, running));
        given(candidateReader.findBatch(target, 2L, 4L, 2)).willReturn(List.of(first, second));
        given(candidateReader.findBatch(target, 4L, 4L, 1)).willReturn(List.of());
        given(mediaBackfillPort.inspect(firstReference)).willReturn(new MediaBackfillInspectionInfo(
                IDENTITY, target.mediaTarget().purpose(), Optional.of(ready)));
        given(mediaBackfillPort.inspect(secondReference))
                .willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING));
        given(checkpointStore.complete(lease)).willReturn(completed);

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.COMPLETED);
        assertThat(summary.target()).isEqualTo(target);
        assertThat(summary.preparedCount()).isEqualTo(3);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.sourceFailuresThisExecution()).hasSize(1).containsEntry(Reason.SOURCE_MISSING, 1L);
        assertThat(meterRegistry.get(MagazineMediaBackfillSourceFailures.METRIC_NAME)
                .tags("target", target.name(), "mode", mode.name(), "reason", Reason.SOURCE_MISSING.name())
                .counter().count()).isEqualTo(1);
        InOrder ordered = inOrder(candidateReader, checkpointStore);
        ordered.verify(candidateReader).findBatch(target, 2L, 4L, 2);
        ordered.verify(checkpointStore).recordProgress(
                lease, 3L, MagazineMediaBackfillOutcome.PREPARED, properties.leaseDuration());
        ordered.verify(checkpointStore).recordProgress(
                lease, 4L, MagazineMediaBackfillOutcome.FAILED, properties.leaseDuration());
        ordered.verify(candidateReader).findBatch(target, 4L, 4L, 1);
        ordered.verify(checkpointStore).complete(lease);
        verify(mediaBackfillPort).inspect(secondReference);
        verify(mediaBackfillPort, never()).prepare(any(), any());
        verify(checkpointStore, never()).pause(any());
    }

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
    void DRY_RUN의_storage_장애는_제한된_횟수_후_실행을_중단한다() {
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.DRY_RUN, "", 2, 1, 3);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.FAILED);
        assertThat(summary.sourceFailuresThisExecution())
                .containsEntry(Reason.STORAGE_UNAVAILABLE, 1L);
        assertSourceFailureMetric(properties.mode(), Reason.STORAGE_UNAVAILABLE, 1);
        verify(mediaBackfillPort, times(3)).inspect(any());
    }

    @ParameterizedTest
    @EnumSource(value = MagazineMediaBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void persistent_storage_장애는_cursor를_전진시키지_않고_실행을_중단한다(
            MagazineMediaBackfillMode mode
    ) {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(mode, runId.toString(), 1, 1, 3);
        Lease lease = lease(runId, mode, 1L);
        Snapshot running = snapshot(runId, mode, Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0);
        Snapshot paused = snapshot(runId, mode, Status.PAUSED, 1L, 0L, 0, 0, 0, 0, 0);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.target()), eq(mode), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, running));
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 1))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));
        given(checkpointStore.pause(lease)).willReturn(true);
        given(checkpointStore.find(runId)).willReturn(paused);

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.FAILED);
        assertThat(summary.scannedCount()).isZero();
        assertThat(summary.sourceFailuresThisExecution())
                .containsOnlyKeys(Reason.STORAGE_UNAVAILABLE)
                .containsEntry(Reason.STORAGE_UNAVAILABLE, 1L);
        assertSourceFailureMetric(mode, Reason.STORAGE_UNAVAILABLE, 1);
        verify(mediaBackfillPort, times(3)).inspect(any());
        verify(checkpointStore, never()).recordProgress(any(), anyLong(), any(), any());
        verify(checkpointStore).pause(lease);
    }

    @ParameterizedTest
    @EnumSource(value = MagazineMediaBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void persistent_source_실패의_cursor_기록에서_lease를_잃으면_실패_원인을_집계하지_않는다(
            MagazineMediaBackfillMode mode
    ) {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(mode, runId.toString(), 1, 1, 1);
        Lease lease = lease(runId, mode, 1L);
        Snapshot running = snapshot(runId, mode, Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.target()), eq(mode), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, running));
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 1))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE));
        doThrow(new MagazineMediaBackfillLeaseLostException())
                .when(checkpointStore).recordProgress(
                        lease, 1L, MagazineMediaBackfillOutcome.FAILED, properties.leaseDuration());
        given(checkpointStore.find(runId)).willReturn(running);

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.LEASE_LOST);
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(meterRegistry.find(MagazineMediaBackfillSourceFailures.METRIC_NAME)
                .tags("target", properties.target().name(), "mode", mode.name(),
                        "reason", Reason.SOURCE_UNREADABLE.name())
                .counter()).isNull();
        verify(checkpointStore).recordProgress(
                lease, 1L, MagazineMediaBackfillOutcome.FAILED, properties.leaseDuration());
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

    @ParameterizedTest
    @EnumSource(
            value = Reason.class,
            names = "STORAGE_UNAVAILABLE",
            mode = EnumSource.Mode.EXCLUDE
    )
    void DRY_RUN은_source_실패_원인을_구분하고_다음_항목을_계속_조사한다(Reason reason) {
        MagazineMediaBackfillProperties properties = properties(MagazineMediaBackfillMode.DRY_RUN, "", 3, 1, 1);
        given(candidateReader.findUpperBound(properties.target())).willReturn(2L);
        given(candidateReader.findBatch(properties.target(), 0L, 2L, 3))
                .willReturn(List.of(candidate(1L), candidate(2L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(reason)).willReturn(unpreparedInspection());

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.inspectedCount()).isEqualTo(1);
        assertThat(summary.sourceFailuresThisExecution()).hasSize(1).containsEntry(reason, 1L);
        assertSourceFailureMetric(properties.mode(), reason, 1);
        verifyNoInteractions(checkpointStore, attachmentService);
        verify(mediaBackfillPort, times(2)).inspect(any());
    }

    @ParameterizedTest
    @EnumSource(value = MagazineMediaBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void persistent_실행도_source_실패_cursor와_원인을_기록하고_다음_항목을_계속한다(
            MagazineMediaBackfillMode mode
    ) {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(mode, runId.toString(), 3, 1, 1);
        Lease lease = lease(runId, mode, 2L);
        given(candidateReader.findUpperBound(properties.target())).willReturn(2L);
        given(candidateReader.findBatch(properties.target(), 0L, 2L, 3))
                .willReturn(List.of(candidate(1L), candidate(2L)));
        given(checkpointStore.acquire(eq(runId), eq(properties.target()), eq(mode), eq(2L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease,
                        snapshot(runId, mode, Status.RUNNING, 2L, 0L, 0, 0, 0, 0, 0)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE))
                .willReturn(unpreparedInspection());
        boolean prepare = mode == MagazineMediaBackfillMode.PREPARE;
        if (prepare) {
            given(mediaBackfillPort.prepare(any(), any())).willReturn(asset(State.PROCESSING));
        }
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, mode, Status.COMPLETED, 2L, 2L, 2, prepare ? 1 : 0, 0, prepare ? 0 : 1, 1));

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(MagazineMediaBackfillSummary.Status.COMPLETED);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.sourceFailuresThisExecution()).containsOnlyKeys(Reason.SOURCE_UNREADABLE)
                .containsEntry(Reason.SOURCE_UNREADABLE, 1L);
        assertSourceFailureMetric(mode, Reason.SOURCE_UNREADABLE, 1);
        verify(checkpointStore).recordProgress(lease, 1L, MagazineMediaBackfillOutcome.FAILED,
                properties.leaseDuration());
        verify(checkpointStore).recordProgress(lease, 2L, prepare
                ? MagazineMediaBackfillOutcome.PREPARED : MagazineMediaBackfillOutcome.SKIPPED,
                properties.leaseDuration());
        verifyNoInteractions(attachmentService);
    }

    @Test
    void PREPARE_copy_실패도_source_조사_실패와_구분해_기록한다() {
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.PREPARE, UUID.randomUUID().toString(), 2, 1, 3);
        Lease lease = givenSinglePersistentFailure(properties, candidate(1L));
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());
        given(mediaBackfillPort.prepare(any(), any()))
                .willThrow(new MediaBackfillSourceException(Reason.COPY_CONFLICT));

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.sourceFailuresThisExecution()).hasSize(1).containsEntry(Reason.COPY_CONFLICT, 1L);
        assertSourceFailureMetric(properties.mode(), Reason.COPY_CONFLICT, 1);
        verify(mediaBackfillPort).prepare(any(), any());
        verify(checkpointStore).recordProgress(lease, 1L, MagazineMediaBackfillOutcome.FAILED,
                properties.leaseDuration());
    }

    @Test
    void 재시도로_복구된_storage_장애는_최종_실패_지표에_넣지_않는다() {
        MagazineMediaBackfillProperties properties = properties(MagazineMediaBackfillMode.DRY_RUN, "", 2, 1, 3);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2)).willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE))
                .willReturn(unpreparedInspection());

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isZero();
        assertThat(summary.inspectedCount()).isEqualTo(1);
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(meterRegistry.getMeters()).isEmpty();
        verify(mediaBackfillPort, times(2)).inspect(any());
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillMode.class)
    void 빈_key는_스토리지를_호출하지_않고_INVALID_SOURCE로_집계한다(MagazineMediaBackfillMode mode) {
        MagazineMediaBackfillProperties properties = properties(mode, UUID.randomUUID().toString(), 2, 1, 1);
        MagazineMediaBackfillCandidate blank = new MagazineMediaBackfillCandidate(properties.target(), 1L, " ");
        if (mode.usesCheckpoint()) {
            givenSinglePersistentFailure(properties, blank);
        } else {
            given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
            given(candidateReader.findBatch(properties.target(), 0L, 1L, 2)).willReturn(List.of(blank));
        }

        MagazineMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.sourceFailuresThisExecution()).hasSize(1).containsEntry(Reason.INVALID_SOURCE, 1L);
        assertSourceFailureMetric(mode, Reason.INVALID_SOURCE, 1);
        verifyNoInteractions(mediaBackfillPort, attachmentService);
    }

    @Test
    void 같은_runner를_다시_실행해도_직전_실행의_원인_집계를_재사용하지_않는다() {
        MagazineMediaBackfillProperties properties = properties(MagazineMediaBackfillMode.DRY_RUN, "", 2, 1, 1);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 2)).willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING))
                .willReturn(unpreparedInspection());
        MagazineMediaBackfillRunner runner = runner(properties);

        MagazineMediaBackfillSummary first = runner.execute();
        MagazineMediaBackfillSummary second = runner.execute();

        assertThat(first.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_MISSING, 1L);
        assertThat(second.sourceFailuresThisExecution()).isEmpty();
        assertSourceFailureMetric(properties.mode(), Reason.SOURCE_MISSING, 1);
    }

    @Test
    void 종료_로그에는_고정_실패_원인만_남기고_원시_후보나_예외_payload를_남기지_않는다() {
        MagazineMediaBackfillProperties properties = properties(MagazineMediaBackfillMode.DRY_RUN, "", 2, 1, 1);
        String privateKey = "magazines/private-fixture-do-not-log.jpg";
        String privatePayload = "fixture-error-payload-do-not-log";
        MagazineMediaBackfillCandidate candidate = new MagazineMediaBackfillCandidate(
                properties.target(), 246801357L, privateKey);
        given(candidateReader.findUpperBound(properties.target())).willReturn(candidate.magazineId());
        given(candidateReader.findBatch(properties.target(), 0L, candidate.magazineId(), 2))
                .willReturn(List.of(candidate));
        MediaBackfillSourceException failure = new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE);
        failure.initCause(new IllegalStateException(privatePayload));
        given(mediaBackfillPort.inspect(any())).willThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(MagazineMediaBackfillRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner(properties).runOnStartup();

            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("sourceFailuresThisExecution={SOURCE_UNREADABLE=1}")
                        .doesNotContain(privateKey, privatePayload, "246801357", IDENTITY);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void DRY_RUN_storage_중단_로그는_예외_클래스가_아닌_고정_원인을_사용한다() {
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.DRY_RUN, "", 1, 1, 1);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 1))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));

        Logger logger = (Logger) LoggerFactory.getLogger(MagazineMediaBackfillRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner(properties).runOnStartup();

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("errorType=STORAGE_UNAVAILABLE")
                    .doesNotContain("errorType=MediaBackfillSourceException");
            assertThat(appender.list.getLast().getFormattedMessage())
                    .contains("status=FAILED", "sourceFailuresThisExecution={STORAGE_UNAVAILABLE=1}");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            assertSourceFailureMetric(properties.mode(), Reason.STORAGE_UNAVAILABLE, 1);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void DRY_RUN_종료_로그는_실제_inspected_집계를_포함한다() {
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.DRY_RUN, "", 1, 1, 1);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(candidateReader.findBatch(properties.target(), 0L, 1L, 1))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());

        ILoggingEvent event = runAndCapture(properties);

        assertThat(event.getFormattedMessage())
                .contains("mode=DRY_RUN", "scanned=1", "inspected=1", "failed=0",
                        "sourceFailuresThisExecution={}");
    }

    @Test
    void 영속_실행_종료_로그는_복구할_수_없는_inspected_집계를_출력하지_않는다() {
        UUID runId = UUID.randomUUID();
        MagazineMediaBackfillProperties properties = properties(
                MagazineMediaBackfillMode.PREPARE, runId.toString(), 1, 1, 1);
        Snapshot completed = snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 1, 0, 0, 0);
        given(candidateReader.findUpperBound(properties.target())).willReturn(1L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.target()), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.COMPLETED, null, completed));

        ILoggingEvent event = runAndCapture(properties);

        assertThat(event.getFormattedMessage())
                .contains("mode=PREPARE", "scanned=1", "prepared=1", "failed=0",
                        "sourceFailuresThisExecution={}")
                .doesNotContain("inspected=");
    }

    private ILoggingEvent runAndCapture(MagazineMediaBackfillProperties properties) {
        Logger logger = (Logger) LoggerFactory.getLogger(MagazineMediaBackfillRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner(properties).runOnStartup();
            assertThat(appender.list).hasSize(1);
            return appender.list.getFirst();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private Lease givenSinglePersistentFailure(
            MagazineMediaBackfillProperties properties,
            MagazineMediaBackfillCandidate candidate
    ) {
        UUID runId = properties.requiredRunId();
        MagazineMediaBackfillMode mode = properties.mode();
        Lease lease = lease(runId, mode, candidate.magazineId());
        given(candidateReader.findUpperBound(properties.target())).willReturn(candidate.magazineId());
        given(candidateReader.findBatch(properties.target(), 0L, candidate.magazineId(), properties.batchSize()))
                .willReturn(List.of(candidate));
        given(checkpointStore.acquire(eq(runId), eq(properties.target()), eq(mode), eq(candidate.magazineId()), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease,
                        snapshot(runId, mode, Status.RUNNING, candidate.magazineId(), 0L, 0, 0, 0, 0, 0)));
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, mode, Status.COMPLETED, candidate.magazineId(), candidate.magazineId(), 1, 0, 0, 0, 1));
        return lease;
    }

    private MagazineMediaBackfillRunner runner(MagazineMediaBackfillProperties properties) {
        return new MagazineMediaBackfillRunner(
                properties, candidateReader, checkpointStore, attachmentService, mediaBackfillPort, meterRegistry);
    }

    private void assertSourceFailureMetric(MagazineMediaBackfillMode mode, Reason reason, double count) {
        assertThat(meterRegistry.get(MagazineMediaBackfillSourceFailures.METRIC_NAME)
                .tags("target", MagazineMediaBackfillTarget.MAGAZINE_BANNER.name(),
                        "mode", mode.name(), "reason", reason.name()).counter().count()).isEqualTo(count);
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
