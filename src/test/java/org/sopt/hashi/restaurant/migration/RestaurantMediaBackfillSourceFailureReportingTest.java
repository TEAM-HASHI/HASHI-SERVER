package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Status;

class RestaurantMediaBackfillSourceFailureReportingTest {

    private static final String IDENTITY = "b".repeat(64);
    private static final RestaurantMediaBackfillTarget TARGET = RestaurantMediaBackfillTarget.RESTAURANT_IMAGE;

    private final RestaurantMediaBackfillCandidateReader reader = mock(RestaurantMediaBackfillCandidateReader.class);
    private final RestaurantMediaBackfillCheckpointStore checkpoint = mock(RestaurantMediaBackfillCheckpointStore.class);
    private final RestaurantMediaBackfillAttachmentService attachment =
            mock(RestaurantMediaBackfillAttachmentService.class);
    private final MediaBackfillPort port = mock(MediaBackfillPort.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void 지표_레지스트리를_종료한다() {
        registry.close();
    }

    @ParameterizedTest
    @MethodSource("sourceCases")
    void 개별_source_실패는_원인을_보존하고_다음_후보를_계속_처리한다(
            RestaurantMediaBackfillTarget target, RestaurantMediaBackfillMode mode, Reason reason
    ) {
        RestaurantMediaBackfillProperties properties = properties(target, mode);
        RestaurantMediaBackfillCandidate first = candidate(target, 1L, "fixture-private-source.jpg");
        RestaurantMediaBackfillCandidate next = candidate(target, 2L, "fixture-next-source.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 1L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(reason));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1L);
        assertThat(summary.sourceFailuresThisExecution()).containsExactlyEntriesOf(Map.of(reason, 1L));
        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.COMPLETED);
        assertThat(counter(target, mode, reason)).isEqualTo(1);
        verify(port).inspect(reference(first));
        verify(port).inspect(reference(next));
        verify(attachment, never()).attachAndRecord(any(), any(), any(), any());
        if (mode == RestaurantMediaBackfillMode.DRY_RUN) {
            verifyNoInteractions(checkpoint);
            verify(port, never()).prepare(any(), any());
        } else {
            verify(checkpoint).recordProgress(
                    lease, 1L, RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
        }
    }

    @ParameterizedTest
    @EnumSource(RestaurantMediaBackfillMode.class)
    void 재시도_도중_복구된_항목은_실패_지표에_포함하지_않는다(RestaurantMediaBackfillMode mode) {
        RestaurantMediaBackfillProperties properties = properties(TARGET, mode);
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 1L, "fixture-retry.jpg");
        stubRun(properties, List.of(first), 0L);
        given(port.inspect(reference(first)))
                .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE))
                .willReturn(inspection(TARGET));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isZero();
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
        verify(port, times(2)).inspect(reference(first));
    }

    @ParameterizedTest
    @EnumSource(value = Reason.class, names = "STORAGE_UNAVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void PREPARE에서_발생한_오류도_최종_항목_실패를_한_번만_보고한다(Reason reason) {
        RestaurantMediaBackfillProperties properties = properties(TARGET, RestaurantMediaBackfillMode.PREPARE);
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 1L, "fixture-copy.jpg");
        stubRun(properties, List.of(first), 1L);
        given(port.prepare(reference(first), IDENTITY)).willThrow(new MediaBackfillSourceException(reason));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.sourceFailuresThisExecution()).containsExactlyEntriesOf(Map.of(reason, 1L));
        assertThat(counter(TARGET, properties.mode(), reason)).isEqualTo(1);
        verify(port).prepare(reference(first), IDENTITY);
    }

    @ParameterizedTest
    @MethodSource("storageFailureCases")
    void storage_장애는_이전_집계를_보존하고_현재_cursor와_다음_후보를_건드리지_않는다(
            RestaurantMediaBackfillTarget target,
            RestaurantMediaBackfillMode mode,
            boolean failureDuringPrepare
    ) {
        RestaurantMediaBackfillProperties properties = properties(target, mode);
        RestaurantMediaBackfillCandidate first = candidate(target, 1L, "fixture-missing.jpg");
        RestaurantMediaBackfillCandidate unavailable = candidate(target, 2L, "fixture-storage-outage.jpg");
        RestaurantMediaBackfillCandidate next = candidate(target, 3L, "fixture-not-processed.jpg");
        Lease lease = stubRun(properties, List.of(first, unavailable, next), 1L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING));
        if (failureDuringPrepare) {
            given(port.prepare(reference(unavailable), IDENTITY))
                    .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));
        } else {
            given(port.inspect(reference(unavailable)))
                    .willThrow(new MediaBackfillSourceException(Reason.STORAGE_UNAVAILABLE));
        }
        if (mode.usesCheckpoint()) {
            given(checkpoint.pause(lease)).willReturn(true);
            given(checkpoint.find(lease.runId())).willReturn(new Snapshot(
                    lease.runId(), target, mode, Status.PAUSED, 3L, 1L, null,
                    1L, 0L, 0L, 0L, 1L));
        }

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.FAILED);
        assertThat(summary.scannedCount()).isEqualTo(mode.usesCheckpoint() ? 1L : 2L);
        assertThat(summary.failedCount()).isEqualTo(1L);
        assertThat(summary.sourceFailuresThisExecution()).containsExactlyInAnyOrderEntriesOf(Map.of(
                Reason.SOURCE_MISSING, 1L, Reason.STORAGE_UNAVAILABLE, 1L));
        assertThat(counter(target, mode, Reason.SOURCE_MISSING)).isEqualTo(1);
        assertThat(counter(target, mode, Reason.STORAGE_UNAVAILABLE)).isEqualTo(1);
        if (failureDuringPrepare) {
            verify(port).inspect(reference(unavailable));
            verify(port, times(properties.maxAttempts())).prepare(reference(unavailable), IDENTITY);
        } else {
            verify(port, times(properties.maxAttempts())).inspect(reference(unavailable));
            verify(port, never()).prepare(any(), any());
        }
        verify(port, never()).inspect(reference(next));
        verifyNoInteractions(attachment);
        if (mode.usesCheckpoint()) {
            verify(checkpoint).recordProgress(
                    lease, 1L, RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
            verify(checkpoint).recordProgress(any(), anyLong(), any(), any());
            verify(checkpoint).pause(lease);
            verify(checkpoint, never()).complete(any());
        } else {
            verifyNoInteractions(checkpoint);
        }
    }

    @ParameterizedTest
    @EnumSource(RestaurantMediaBackfillMode.class)
    void 빈_key는_INVALID_SOURCE로_보고하고_storage를_호출하지_않는다(RestaurantMediaBackfillMode mode) {
        RestaurantMediaBackfillProperties properties = properties(TARGET, mode);
        stubRun(properties, List.of(candidate(TARGET, 1L, "   ")), 1L);

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.sourceFailuresThisExecution()).containsExactlyEntriesOf(Map.of(Reason.INVALID_SOURCE, 1L));
        assertThat(counter(TARGET, mode, Reason.INVALID_SOURCE)).isEqualTo(1);
        verifyNoInteractions(port, attachment);
    }

    @ParameterizedTest
    @EnumSource(value = RestaurantMediaBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void FAILED_cursor가_저장되지_않으면_원인_지표를_확정하지_않는다(RestaurantMediaBackfillMode mode) {
        RestaurantMediaBackfillProperties properties = properties(TARGET, mode);
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 1L, "fixture-fenced.jpg");
        RestaurantMediaBackfillCandidate next = candidate(TARGET, 2L, "fixture-not-processed.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 0L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING));
        doThrow(new RestaurantMediaBackfillLeaseLostException()).when(checkpoint)
                .recordProgress(lease, 1L, RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
        given(checkpoint.find(lease.runId())).willReturn(snapshot(properties, 2, 0, 0, Status.RUNNING));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.LEASE_LOST);
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
        verify(port, never()).inspect(reference(next));
        verify(checkpoint, never()).complete(any());
    }

    @ParameterizedTest
    @EnumSource(value = RestaurantMediaBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void 지표_장애에도_저장된_실패와_다음_후보_처리를_유지한다(RestaurantMediaBackfillMode mode) {
        RestaurantMediaBackfillProperties properties = properties(TARGET, mode);
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 1L, "fixture-metric.jpg");
        RestaurantMediaBackfillCandidate next = candidate(TARGET, 2L, "fixture-next.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 1L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE));
        MeterRegistry unavailable = mock(MeterRegistry.class);
        given(unavailable.counter(eq(RestaurantMediaBackfillSourceFailures.METRIC_NAME), any(String[].class)))
                .willThrow(new IllegalStateException("fixture-private-provider-payload"));

        RestaurantMediaBackfillSummary summary = new RestaurantMediaBackfillRunner(
                properties, reader, checkpoint, attachment, port, unavailable).execute();

        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.COMPLETED);
        assertThat(summary.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_UNREADABLE, 1L);
        verify(checkpoint).recordProgress(
                lease, 1L, RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
        verify(port).inspect(reference(next));
    }

    @ParameterizedTest
    @EnumSource(value = RestaurantMediaBackfillMode.class, names = {"PREPARE", "ATTACH"})
    void media_terminal_상태_실패를_source_오류로_섞지_않는다(RestaurantMediaBackfillMode mode) {
        RestaurantMediaBackfillProperties properties = properties(TARGET, mode);
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 1L, "fixture-terminal.jpg");
        stubRun(properties, List.of(first), 1L);
        given(port.inspect(reference(first))).willReturn(new MediaBackfillInspectionInfo(
                IDENTITY, TARGET.mediaTarget().purpose(), Optional.of(asset(TARGET, State.FAILED))));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void 다음_실행의_집계는_이전_실행과_분리되고_결과_Map은_불변이다() {
        RestaurantMediaBackfillProperties properties = properties(TARGET, RestaurantMediaBackfillMode.DRY_RUN);
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 1L, "fixture-repeat.jpg");
        stubRun(properties, List.of(first), 0);
        given(port.inspect(reference(first)))
                .willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING))
                .willReturn(inspection(TARGET));
        RestaurantMediaBackfillRunner runner = runner(properties);

        RestaurantMediaBackfillSummary failed = runner.execute();
        RestaurantMediaBackfillSummary recovered = runner.execute();

        assertThat(failed.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_MISSING, 1L);
        assertThat(recovered.sourceFailuresThisExecution()).isEmpty();
        Map<Reason, Long> mutable = new EnumMap<>(Reason.class);
        mutable.put(Reason.SOURCE_CHANGED, 1L);
        RestaurantMediaBackfillSummary copied = recovered.withSourceFailures(mutable);
        mutable.put(Reason.SOURCE_CHANGED, 9L);
        assertThat(copied.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_CHANGED, 1L);
        assertThatThrownBy(() -> copied.sourceFailuresThisExecution().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 최대_batch_후_pause_거절을_PAUSED로_잘못_보고하지_않는다(boolean paused) {
        RestaurantMediaBackfillProperties properties = new RestaurantMediaBackfillProperties(
                true, UUID.randomUUID().toString(), TARGET, RestaurantMediaBackfillMode.ATTACH,
                1, 1, Duration.ofMinutes(5), 3, Duration.ZERO);
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 1L, "fixture-pause.jpg");
        RestaurantMediaBackfillCandidate next = candidate(TARGET, 2L, "fixture-next-pause.jpg");
        Lease lease = stubRun(properties, List.of(first, next), 0);
        given(reader.findBatch(TARGET, 0L, 2L, 1)).willReturn(List.of(first));
        given(reader.findBatch(TARGET, 1L, 2L, 1)).willReturn(List.of(next));
        given(checkpoint.pause(lease)).willReturn(paused);
        given(checkpoint.find(lease.runId())).willReturn(new Snapshot(
                properties.requiredRunId(), TARGET, properties.mode(), paused ? Status.PAUSED : Status.RUNNING,
                2L, 1L, null, 1L, 0L, 0L, 1L, 0L));

        RestaurantMediaBackfillSummary summary = runner(properties).execute();

        assertThat(summary.status()).isEqualTo(paused
                ? RestaurantMediaBackfillSummary.Status.PAUSED : RestaurantMediaBackfillSummary.Status.LEASE_LOST);
        verify(checkpoint).pause(lease);
        verify(checkpoint, never()).complete(any());
    }

    @Test
    void 종료_로그는_고정_실패_코드만_포함하고_원시_후보와_예외_payload를_노출하지_않는다() {
        RestaurantMediaBackfillProperties properties = properties(TARGET, RestaurantMediaBackfillMode.DRY_RUN);
        String key = "fixture-private-key-never-log.jpg";
        String payload = "fixture-provider-payload-never-log";
        RestaurantMediaBackfillCandidate first = candidate(TARGET, 918273L, key);
        stubRun(properties, List.of(first), 1L);
        given(port.inspect(reference(first))).willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING) {
            @Override
            public String getMessage() {
                return payload;
            }
        });
        Logger logger = (Logger) LoggerFactory.getLogger(RestaurantMediaBackfillRunner.class);
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
        return Arrays.stream(RestaurantMediaBackfillTarget.values()).flatMap(target ->
                Arrays.stream(RestaurantMediaBackfillMode.values()).flatMap(mode ->
                        Arrays.stream(Reason.values())
                                .filter(reason -> reason != Reason.STORAGE_UNAVAILABLE)
                                .map(reason -> Arguments.of(target, mode, reason))));
    }

    private static Stream<Arguments> storageFailureCases() {
        return Arrays.stream(RestaurantMediaBackfillTarget.values()).flatMap(target -> Stream.concat(
                Arrays.stream(RestaurantMediaBackfillMode.values()).map(mode -> Arguments.of(target, mode, false)),
                Stream.of(Arguments.of(target, RestaurantMediaBackfillMode.PREPARE, true))));
    }

    private RestaurantMediaBackfillProperties properties(
            RestaurantMediaBackfillTarget target, RestaurantMediaBackfillMode mode
    ) {
        return new RestaurantMediaBackfillProperties(true, UUID.randomUUID().toString(), target, mode,
                5, 1, Duration.ofMinutes(5), 3, Duration.ZERO);
    }

    private RestaurantMediaBackfillRunner runner(RestaurantMediaBackfillProperties properties) {
        return new RestaurantMediaBackfillRunner(properties, reader, checkpoint, attachment, port, registry);
    }

    private Lease stubRun(
            RestaurantMediaBackfillProperties properties,
            List<RestaurantMediaBackfillCandidate> candidates,
            long failed
    ) {
        long upper = candidates.getLast().associationId();
        given(reader.findUpperBound(properties.target())).willReturn(upper);
        given(reader.findBatch(properties.target(), 0L, upper, properties.batchSize())).willReturn(candidates);
        given(port.inspect(any())).willReturn(inspection(properties.target()));
        given(port.prepare(any(), any())).willReturn(asset(properties.target(), State.PROCESSING));
        if (properties.mode() == RestaurantMediaBackfillMode.DRY_RUN) {
            return null;
        }
        Lease lease = new Lease(properties.requiredRunId(), UUID.randomUUID(), properties.target(),
                properties.mode(), upper);
        given(checkpoint.acquire(properties.requiredRunId(), properties.target(), properties.mode(),
                upper, properties.leaseDuration())).willReturn(new Acquisition(
                AcquisitionState.ACQUIRED, lease, snapshot(properties, upper, 0, 0, Status.RUNNING)));
        given(checkpoint.complete(lease)).willReturn(snapshot(properties, upper, candidates.size(), failed,
                Status.COMPLETED));
        return lease;
    }

    private Snapshot snapshot(RestaurantMediaBackfillProperties properties, long upper, long scanned,
                              long failed, Status status) {
        long prepared = properties.mode() == RestaurantMediaBackfillMode.PREPARE ? scanned - failed : 0;
        long skipped = properties.mode() == RestaurantMediaBackfillMode.ATTACH ? scanned - failed : 0;
        return new Snapshot(properties.requiredRunId(), properties.target(), properties.mode(), status,
                upper, scanned == 0 ? 0 : upper, null, scanned, prepared, 0, skipped, failed);
    }

    private RestaurantMediaBackfillCandidate candidate(RestaurantMediaBackfillTarget target, long id, String key) {
        return new RestaurantMediaBackfillCandidate(target, id, 100L, key);
    }

    private MediaBackfillReference reference(RestaurantMediaBackfillCandidate candidate) {
        return new MediaBackfillReference(
                candidate.target().mediaTarget(), candidate.associationId(), candidate.legacyKey());
    }

    private MediaBackfillInspectionInfo inspection(RestaurantMediaBackfillTarget target) {
        return new MediaBackfillInspectionInfo(IDENTITY, target.mediaTarget().purpose(), Optional.empty());
    }

    private MediaBackfillAssetInfo asset(RestaurantMediaBackfillTarget target, State state) {
        return new MediaBackfillAssetInfo(UUID.randomUUID(), target.mediaTarget().purpose(), IDENTITY, state);
    }

    private double counter(RestaurantMediaBackfillTarget target, RestaurantMediaBackfillMode mode, Reason reason) {
        return registry.get(RestaurantMediaBackfillSourceFailures.METRIC_NAME)
                .tags("target", target.name(), "mode", mode.name(), "reason", reason.name()).counter().count();
    }
}
