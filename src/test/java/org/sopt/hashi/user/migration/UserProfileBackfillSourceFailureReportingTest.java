package org.sopt.hashi.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillAssetInfo.State;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillTarget;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Lease;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Status;

class UserProfileBackfillSourceFailureReportingTest {

    private static final String IDENTITY = "b".repeat(64);
    private static final MediaBackfillTarget TARGET = MediaBackfillTarget.USER_PROFILE;

    private final UserProfileBackfillCandidateReader reader = mock(UserProfileBackfillCandidateReader.class);
    private final UserProfileBackfillCheckpointStore checkpoint = mock(UserProfileBackfillCheckpointStore.class);
    private final UserProfileBackfillAttachmentService attachment =
            mock(UserProfileBackfillAttachmentService.class);
    private final MediaBackfillPort port = mock(MediaBackfillPort.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void 지표_레지스트리를_종료한다() {
        registry.close();
    }

    @ParameterizedTest
    @MethodSource("sourceCases")
    void 모든_mode에서_실패_원인을_보존하고_다음_후보를_계속_처리한다(
            UserProfileBackfillMode mode, Reason reason
    ) {
        UserProfileBackfillProperties properties = properties(mode);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-private-source.jpg");
        UserProfileBackfillCandidate next = candidate(2L, "fixture-next-source.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 1L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(reason));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1L);
        assertThat(summary.sourceFailuresThisExecution()).containsExactlyEntriesOf(Map.of(reason, 1L));
        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.COMPLETED);
        assertThat(counter(mode, reason)).isEqualTo(1);
        verify(port, times(reason == Reason.STORAGE_UNAVAILABLE ? 3 : 1)).inspect(reference(first));
        verify(port).inspect(reference(next));
        verify(attachment, never()).attachAndRecord(any(), any(), any(), any());
        if (mode == UserProfileBackfillMode.DRY_RUN) {
            verifyNoInteractions(checkpoint);
            verify(port, never()).prepare(any(), any());
        } else {
            verify(checkpoint).recordProgress(
                    lease, 1L, UserProfileBackfillOutcome.FAILED, properties.leaseDuration());
        }
    }

    @ParameterizedTest
    @EnumSource(UserProfileBackfillMode.class)
    void 재시도_도중_복구된_항목은_실패_지표에_포함하지_않는다(UserProfileBackfillMode mode) {
        UserProfileBackfillProperties properties = properties(mode);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-retry.jpg");
        stubRun(properties, List.of(first), 0L);
        given(port.inspect(reference(first)))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE))
                .willReturn(inspection());

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isZero();
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
        verify(port, times(2)).inspect(reference(first));
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void PREPARE에서_발생한_오류도_최종_항목_실패를_한_번만_보고한다(Reason reason) {
        UserProfileBackfillProperties properties = properties(UserProfileBackfillMode.PREPARE);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-copy.jpg");
        stubRun(properties, List.of(first), 1L);
        given(port.prepare(reference(first), IDENTITY)).willThrow(new MediaBackfillSourceException(reason));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.sourceFailuresThisExecution()).containsExactlyEntriesOf(Map.of(reason, 1L));
        assertThat(counter(properties.mode(), reason)).isEqualTo(1);
        verify(port, times(reason == Reason.STORAGE_UNAVAILABLE ? 3 : 1)).prepare(reference(first), IDENTITY);
    }

    @ParameterizedTest
    @EnumSource(UserProfileBackfillMode.class)
    void 빈_key는_INVALID_SOURCE로_보고하고_storage를_호출하지_않는다(UserProfileBackfillMode mode) {
        UserProfileBackfillProperties properties = properties(mode);
        stubRun(properties, List.of(candidate(1L, "   ")), 1L);

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.sourceFailuresThisExecution()).containsExactlyEntriesOf(Map.of(Reason.INVALID_SOURCE, 1L));
        assertThat(counter(mode, Reason.INVALID_SOURCE)).isEqualTo(1);
        verifyNoInteractions(port, attachment);
    }

    @ParameterizedTest
    @EnumSource(value = UserProfileBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void FAILED_cursor가_저장되지_않으면_원인_지표를_확정하지_않는다(UserProfileBackfillMode mode) {
        UserProfileBackfillProperties properties = properties(mode);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-fenced.jpg");
        UserProfileBackfillCandidate next = candidate(2L, "fixture-not-processed.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 0L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING));
        doThrow(new UserProfileBackfillLeaseLostException()).when(checkpoint)
                .recordProgress(lease, 1L, UserProfileBackfillOutcome.FAILED, properties.leaseDuration());
        given(checkpoint.find(lease.runId())).willReturn(snapshot(properties, 2, 0, 0, Status.RUNNING));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.LEASE_LOST);
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
        verify(port, never()).inspect(reference(next));
        verify(checkpoint, never()).complete(any());
    }

    @ParameterizedTest
    @EnumSource(value = UserProfileBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void 지표_장애에도_저장된_실패와_다음_후보_처리를_유지한다(UserProfileBackfillMode mode) {
        UserProfileBackfillProperties properties = properties(mode);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-metric.jpg");
        UserProfileBackfillCandidate next = candidate(2L, "fixture-next.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 1L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE));
        MeterRegistry unavailable = mock(MeterRegistry.class);
        given(unavailable.counter(eq(UserProfileBackfillSourceFailures.METRIC_NAME), any(String[].class)))
                .willThrow(new IllegalStateException("fixture-private-provider-payload"));

        UserProfileBackfillSummary summary = new UserProfileBackfillRunner(
                properties, reader, checkpoint, attachment, port, unavailable).execute();

        assertThat(summary.status()).isEqualTo(UserProfileBackfillSummary.Status.COMPLETED);
        assertThat(summary.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_UNREADABLE, 1L);
        verify(checkpoint).recordProgress(
                lease, 1L, UserProfileBackfillOutcome.FAILED, properties.leaseDuration());
        verify(port).inspect(reference(next));
    }

    @ParameterizedTest
    @EnumSource(value = UserProfileBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void media_terminal_상태_실패를_source_오류로_섞지_않는다(UserProfileBackfillMode mode) {
        UserProfileBackfillProperties properties = properties(mode);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-terminal.jpg");
        stubRun(properties, List.of(first), 1L);
        given(port.inspect(reference(first))).willReturn(new MediaBackfillInspectionInfo(
                IDENTITY, TARGET.purpose(), Optional.of(asset(State.FAILED))));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void 다음_실행의_집계는_이전_실행과_분리되고_결과_Map은_불변이다() {
        UserProfileBackfillProperties properties = properties(UserProfileBackfillMode.DRY_RUN);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-repeat.jpg");
        stubRun(properties, List.of(first), 0);
        given(port.inspect(reference(first)))
                .willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING))
                .willReturn(inspection());
        UserProfileBackfillRunner runner = runner(properties);

        UserProfileBackfillSummary failed = runner.execute();
        UserProfileBackfillSummary recovered = runner.execute();

        assertThat(failed.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_MISSING, 1L);
        assertThat(recovered.sourceFailuresThisExecution()).isEmpty();
        Map<Reason, Long> mutable = new EnumMap<>(Reason.class);
        mutable.put(Reason.SOURCE_CHANGED, 1L);
        UserProfileBackfillSummary copied = recovered.withSourceFailures(mutable);
        mutable.put(Reason.SOURCE_CHANGED, 9L);
        assertThat(copied.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_CHANGED, 1L);
        assertThatThrownBy(() -> copied.sourceFailuresThisExecution().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 최대_batch_후_pause_거절을_PAUSED로_잘못_보고하지_않는다(boolean paused) {
        UserProfileBackfillProperties properties = new UserProfileBackfillProperties(
                true, UUID.randomUUID().toString(), UserProfileBackfillMode.ATTACH,
                1, 1, Duration.ofMinutes(5), 3, Duration.ZERO);
        UserProfileBackfillCandidate first = candidate(1L, "fixture-pause.jpg");
        UserProfileBackfillCandidate next = candidate(2L, "fixture-next-pause.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 0);
        given(reader.findBatch(0L, 2L, 1)).willReturn(List.of(first));
        given(reader.findBatch(1L, 2L, 1)).willReturn(List.of(next));
        given(checkpoint.pause(lease)).willReturn(paused);
        given(checkpoint.find(lease.runId())).willReturn(new Snapshot(
                properties.requiredRunId(), properties.mode(), paused ? Status.PAUSED : Status.RUNNING,
                2L, 1L, null, 1L, 0L, 0L, 1L, 0L));

        UserProfileBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(paused
                ? UserProfileBackfillSummary.Status.PAUSED : UserProfileBackfillSummary.Status.LEASE_LOST);
        verify(checkpoint).pause(lease);
        verify(checkpoint, never()).complete(any());
    }

    @Test
    void 종료_로그는_고정_실패_코드만_포함하고_원시_후보와_예외_payload를_노출하지_않는다() {
        UserProfileBackfillProperties properties = properties(UserProfileBackfillMode.DRY_RUN);
        String key = "fixture-private-key-never-log.jpg";
        String payload = "fixture-provider-payload-never-log";
        UserProfileBackfillCandidate first = candidate(918273L, key);
        stubRun(properties, List.of(first), 1L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING) {
            @Override
            public String getMessage() {
                return payload;
            }
        });
        Logger logger = (Logger) LoggerFactory.getLogger(UserProfileBackfillRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner(properties).runOnStartup();

            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("sourceFailuresThisExecution={SOURCE_MISSING=1}")
                        .doesNotContain(key, payload, "918273", IDENTITY);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Stream<Arguments> sourceCases() {
        return Arrays.stream(UserProfileBackfillMode.values()).flatMap(mode ->
                Arrays.stream(Reason.values()).map(reason -> Arguments.of(mode, reason)));
    }

    private UserProfileBackfillProperties properties(
            UserProfileBackfillMode mode
    ) {
        return new UserProfileBackfillProperties(true, UUID.randomUUID().toString(), mode,
                5, 1, Duration.ofMinutes(5), 3, Duration.ZERO);
    }

    private UserProfileBackfillRunner runner(UserProfileBackfillProperties properties) {
        return new UserProfileBackfillRunner(properties, reader, checkpoint, attachment, port, registry);
    }

    private Lease stubRun(
            UserProfileBackfillProperties properties,
            List<UserProfileBackfillCandidate> candidates,
            long failed
    ) {
        long upper = candidates.getLast().userId();
        given(reader.findUpperBound()).willReturn(upper);
        given(reader.findBatch(0L, upper, properties.batchSize())).willReturn(candidates);
        given(port.inspect(any())).willReturn(inspection());
        given(port.prepare(any(), any())).willReturn(asset(State.PROCESSING));
        if (properties.mode() == UserProfileBackfillMode.DRY_RUN) {
            return null;
        }
        Lease lease = new Lease(properties.requiredRunId(), UUID.randomUUID(),
                properties.mode(), upper);
        given(checkpoint.acquire(properties.requiredRunId(), properties.mode(),
                upper, properties.leaseDuration())).willReturn(new Acquisition(
                AcquisitionState.ACQUIRED, lease, snapshot(properties, upper, 0, 0, Status.RUNNING)));
        given(checkpoint.complete(lease)).willReturn(snapshot(properties, upper, candidates.size(), failed,
                Status.COMPLETED));
        return lease;
    }

    private Snapshot snapshot(UserProfileBackfillProperties properties, long upper, long scanned,
                              long failed, Status status) {
        long prepared = properties.mode() == UserProfileBackfillMode.PREPARE ? scanned - failed : 0;
        long skipped = properties.mode() == UserProfileBackfillMode.ATTACH ? scanned - failed : 0;
        return new Snapshot(properties.requiredRunId(), properties.mode(), status,
                upper, scanned == 0 ? 0 : upper, null, scanned, prepared, 0, skipped, failed);
    }

    private UserProfileBackfillCandidate candidate(long id, String key) {
        return new UserProfileBackfillCandidate(id, key);
    }

    private MediaBackfillReference reference(UserProfileBackfillCandidate candidate) {
        return new MediaBackfillReference(
                TARGET, candidate.userId(), candidate.legacyKey());
    }

    private MediaBackfillInspectionInfo inspection() {
        return new MediaBackfillInspectionInfo(IDENTITY, TARGET.purpose(), Optional.empty());
    }

    private MediaBackfillAssetInfo asset(State state) {
        return new MediaBackfillAssetInfo(UUID.randomUUID(), TARGET.purpose(), IDENTITY, state);
    }

    private double counter(UserProfileBackfillMode mode, Reason reason) {
        return registry.get(UserProfileBackfillSourceFailures.METRIC_NAME)
                .tags("target", TARGET.name(), "mode", mode.name(), "reason", reason.name()).counter().count();
    }
}
