package org.sopt.hashi.user.migration;

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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillAssetInfo.State;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Lease;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Status;

class UserProfileBackfillRunnerTest {

    private static final String IDENTITY = "a".repeat(64);

    private final UserProfileBackfillCandidateReader candidateReader =
            mock(UserProfileBackfillCandidateReader.class);
    private final UserProfileBackfillCheckpointStore checkpointStore =
            mock(UserProfileBackfillCheckpointStore.class);
    private final UserProfileBackfillAttachmentService attachmentService =
            mock(UserProfileBackfillAttachmentService.class);
    private final MediaBackfillPort mediaBackfillPort = mock(MediaBackfillPort.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @AfterEach
    void 지표_레지스트리를_종료한다() {
        meterRegistry.close();
    }

    @Test
    void DRY_RUN은_inspect만_수행하고_DB나_asset을_변경하지_않는다() {
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.DRY_RUN, "", 2, 1, 3);
        UserProfileBackfillCandidate first = candidate(1L);
        UserProfileBackfillCandidate second = candidate(2L);
        given(candidateReader.findUpperBound()).willReturn(2L);
        given(candidateReader.findBatch(0L, 2L, 2))
                .willReturn(List.of(first, second));
        given(candidateReader.findBatch(2L, 2L, 1))
                .willReturn(List.of());
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.COMPLETED);
        assertThat(summary.scannedCount()).isEqualTo(2);
        assertThat(summary.inspectedCount()).isEqualTo(2);
        verify(mediaBackfillPort, times(2)).inspect(any());
        verify(mediaBackfillPort, never()).prepare(any(), any());
        verify(mediaBackfillPort, never()).claimReady(any());
        verify(checkpointStore, never()).acquire(any(), any(), anyLong(), any());
        verifyNoInteractions(checkpointStore, attachmentService);
    }

    @Test
    void PREPARE는_같은_identity를_준비하고_항목별_cursor를_기록한다() {
        UUID runId = UUID.randomUUID();
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.PREPARE, runId.toString(), 2, 1, 3);
        Lease lease = lease(runId, properties.mode(), 1L);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(0L, 1L, 2))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());
        given(mediaBackfillPort.prepare(any(), eq(IDENTITY)))
                .willReturn(asset(State.PROCESSING));
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 1, 0, 0, 0));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.COMPLETED);
        assertThat(summary.preparedCount()).isEqualTo(1);
        verify(mediaBackfillPort).prepare(any(), eq(IDENTITY));
        verify(checkpointStore).recordProgress(
                lease, 1L, UserProfileBackfillOutcome.PREPARED, properties.leaseDuration());
    }

    @Test
    void ATTACH는_READY일_때만_aggregate_transaction에_연결과_cursor를_위임한다() {
        UUID runId = UUID.randomUUID();
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.ATTACH, runId.toString(), 2, 1, 3);
        Lease lease = lease(runId, properties.mode(), 1L);
        UserProfileBackfillCandidate candidate = candidate(1L);
        MediaBackfillAssetInfo ready = asset(State.READY);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(0L, 1L, 2))
                .willReturn(List.of(candidate));
        given(mediaBackfillPort.inspect(any())).willReturn(inspection(ready));
        given(attachmentService.attachAndRecord(
                candidate, ready, lease, properties.leaseDuration()))
                .willReturn(UserProfileBackfillOutcome.ATTACHED);
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 0, 1, 0, 0));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.attachedCount()).isEqualTo(1);
        verify(attachmentService).attachAndRecord(
                candidate, ready, lease, properties.leaseDuration());
        verify(checkpointStore, never()).recordProgress(
                eq(lease), eq(1L), any(), eq(properties.leaseDuration()));
    }

    @Test
    void DRY_RUN의_storage_장애는_제한된_횟수_후_실행을_중단한다() {
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.DRY_RUN, "", 2, 1, 3);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(candidateReader.findBatch(0L, 1L, 2))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.FAILED);
        assertThat(summary.sourceFailuresThisExecution())
                .containsEntry(Reason.STORAGE_UNAVAILABLE, 1L);
        verify(mediaBackfillPort, times(3)).inspect(any());
    }

    @Test
    void PREPARE의_storage_장애는_cursor를_전진시키지_않고_실행을_중단한다() {
        UUID runId = UUID.randomUUID();
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.PREPARE, runId.toString(), 1, 1, 3);
        Lease lease = lease(runId, properties.mode(), 1L);
        Snapshot running = snapshot(
                runId, properties.mode(), Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0);
        Snapshot paused = snapshot(
                runId, properties.mode(), Status.PAUSED, 1L, 0L, 0, 0, 0, 0, 0);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(checkpointStore.acquire(eq(runId), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, running));
        given(candidateReader.findBatch(0L, 1L, 1))
                .willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));
        given(checkpointStore.pause(lease)).willReturn(true);
        given(checkpointStore.find(runId)).willReturn(paused);

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.FAILED);
        assertThat(summary.scannedCount()).isZero();
        verify(mediaBackfillPort, times(3)).inspect(any());
        verify(checkpointStore, never()).recordProgress(any(), anyLong(), any(), any());
        verify(checkpointStore).pause(lease);
    }

    @Test
    void 다른_worker가_lease를_보유하면_후보를_처리하지_않는다() {
        UUID runId = UUID.randomUUID();
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.ATTACH, runId.toString(), 2, 1, 3);
        Snapshot busy = snapshot(
                runId, properties.mode(), Status.RUNNING, 10L, 3L, 3, 0, 1, 2, 0);
        given(candidateReader.findUpperBound()).willReturn(10L);
        given(checkpointStore.acquire(
                eq(runId), eq(properties.mode()), eq(10L), any()))
                .willReturn(new Acquisition(AcquisitionState.BUSY, null, busy));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.BUSY);
        verify(candidateReader, never()).findBatch(anyLong(), anyLong(), anyInt());
        verify(mediaBackfillPort, never()).inspect(any());
    }


    @Test
    void ATTACH는_PROCESSING을_기다리지_않고_다음_실행에서_재검사하도록_건너뛴다() {
        UUID runId = UUID.randomUUID();
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.ATTACH, runId.toString(), 2, 1, 3);
        Lease lease = lease(runId, properties.mode(), 1L);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(checkpointStore.acquire(eq(runId), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 1L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(0L, 1L, 2)).willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willReturn(inspection(asset(State.PROCESSING)));
        given(checkpointStore.complete(lease)).willReturn(snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 0, 0, 1, 0));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.skippedCount()).isEqualTo(1);
        verify(attachmentService, never()).attachAndRecord(any(), any(), any(), any());
        verify(mediaBackfillPort, never()).prepare(any(), any());
        verify(checkpointStore).recordProgress(
                lease, 1L, UserProfileBackfillOutcome.SKIPPED, properties.leaseDuration());
    }

    @Test
    void 영구적인_source_오류는_재시도하지_않고_민감한_후보를_문자열로_노출하지_않는다() {
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.DRY_RUN, "", 2, 1, 3);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(candidateReader.findBatch(0L, 1L, 2)).willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any()))
                .willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING));

        assertThat(runner(properties).execute().failedCount()).isEqualTo(1);

        verify(mediaBackfillPort).inspect(any());
        assertThat(candidate(1L).toString()).doesNotContain("profiles/legacy.jpg", "userId=1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 최대_batch_후_pause가_거부되면_PAUSED가_아닌_LEASE_LOST를_보고한다(boolean paused) {
        UUID runId = UUID.randomUUID();
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.ATTACH, runId.toString(), 1, 1, 3);
        Lease lease = lease(runId, properties.mode(), 2L);
        given(candidateReader.findUpperBound()).willReturn(2L);
        given(checkpointStore.acquire(eq(runId), eq(properties.mode()), eq(2L), any()))
                .willReturn(new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(
                        runId, properties.mode(), Status.RUNNING, 2L, 0L, 0, 0, 0, 0, 0)));
        given(candidateReader.findBatch(0L, 2L, 1)).willReturn(List.of(candidate(1L)));
        given(candidateReader.findBatch(1L, 2L, 1)).willReturn(List.of(candidate(2L)));
        given(mediaBackfillPort.inspect(any())).willReturn(inspection(asset(State.PROCESSING)));
        given(checkpointStore.pause(lease)).willReturn(paused);
        given(checkpointStore.find(runId)).willReturn(snapshot(
                runId, properties.mode(), paused ? Status.PAUSED : Status.RUNNING,
                2L, 1L, 1, 0, 0, 1, 0));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(paused
                ? UserProfileBackfillSummary.Status.PAUSED : UserProfileBackfillSummary.Status.LEASE_LOST);
        assertThat(summary.scannedCount()).isEqualTo(1);
        verify(checkpointStore).pause(lease);
        verify(checkpointStore, never()).complete(any());
        verify(mediaBackfillPort).inspect(any());
    }

    @Test
    void DRY_RUN_종료_로그는_실제_inspected_집계를_포함한다() {
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.DRY_RUN, "", 1, 1, 1);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(candidateReader.findBatch(0L, 1L, 1)).willReturn(List.of(candidate(1L)));
        given(mediaBackfillPort.inspect(any())).willReturn(unpreparedInspection());

        ILoggingEvent event = runAndCapture(properties);

        assertThat(event.getFormattedMessage())
                .contains("mode=DRY_RUN", "scanned=1", "inspected=1", "failed=0");
    }

    @Test
    void 영속_실행_종료_로그는_복구할_수_없는_inspected_집계를_출력하지_않는다() {
        UUID runId = UUID.randomUUID();
        UserProfileBackfillProperties properties = properties(
                UserProfileBackfillMode.PREPARE, runId.toString(), 1, 1, 1);
        Snapshot completed = snapshot(
                runId, properties.mode(), Status.COMPLETED, 1L, 1L, 1, 1, 0, 0, 0);
        given(candidateReader.findUpperBound()).willReturn(1L);
        given(checkpointStore.acquire(eq(runId), eq(properties.mode()), eq(1L), any()))
                .willReturn(new Acquisition(AcquisitionState.COMPLETED, null, completed));

        ILoggingEvent event = runAndCapture(properties);

        assertThat(event.getFormattedMessage())
                .contains("mode=PREPARE", "scanned=1", "prepared=1", "failed=0")
                .doesNotContain("inspected=");
    }

    private ILoggingEvent runAndCapture(UserProfileBackfillProperties properties) {
        Logger logger = (Logger) LoggerFactory.getLogger(UserProfileBackfillRunner.class);
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

    private UserProfileBackfillRunner runner(UserProfileBackfillProperties properties) {
        return new UserProfileBackfillRunner(
                properties, candidateReader, checkpointStore, attachmentService, mediaBackfillPort, meterRegistry);
    }

    private UserProfileBackfillProperties properties(
            UserProfileBackfillMode mode,
            String runId,
            int batchSize,
            int maxBatches,
            int maxAttempts
    ) {
        return new UserProfileBackfillProperties(
                true, runId,
                mode, batchSize, maxBatches, Duration.ofMinutes(5), maxAttempts, Duration.ZERO);
    }

    private UserProfileBackfillCandidate candidate(long userId) {
        return new UserProfileBackfillCandidate(userId, "profiles/legacy.jpg");
    }

    private Lease lease(UUID runId, UserProfileBackfillMode mode, long upperBound) {
        return new Lease(
                runId, UUID.randomUUID(),
                mode, upperBound);
    }

    private Snapshot snapshot(
            UUID runId,
            UserProfileBackfillMode mode,
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
                runId, mode, status,
                upperBound, cursor, status == Status.RUNNING ? LocalDateTime.now().plusMinutes(1) : null,
                scanned, prepared, attached, skipped, failed);
    }

    private MediaBackfillInspectionInfo unpreparedInspection() {
        return new MediaBackfillInspectionInfo(
                IDENTITY, MediaAssetPurpose.PROFILE, Optional.empty());
    }

    private MediaBackfillInspectionInfo inspection(MediaBackfillAssetInfo asset) {
        return new MediaBackfillInspectionInfo(
                IDENTITY, MediaAssetPurpose.PROFILE, Optional.of(asset));
    }

    private MediaBackfillAssetInfo asset(State state) {
        return new MediaBackfillAssetInfo(
                UUID.randomUUID(), MediaAssetPurpose.PROFILE, IDENTITY, state);
    }
}
