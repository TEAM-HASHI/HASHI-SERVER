package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
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

class RestaurantMediaBackfillUnreadableStopTest {
    private static final String IDENTITY = "a".repeat(64);

    static Stream<Arguments> cases() {
        return Arrays.stream(RestaurantMediaBackfillTarget.values()).flatMap(target ->
                Arrays.stream(RestaurantMediaBackfillMode.values()).flatMap(mode ->
                        mode == RestaurantMediaBackfillMode.PREPARE
                                ? Stream.of(Arguments.of(target, mode, false), Arguments.of(target, mode, true))
                                : Stream.of(Arguments.of(target, mode, false))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void batch를_넘어_다섯번째_오류에서_멈추고_같은_실행을_재개한다(
            RestaurantMediaBackfillTarget target, RestaurantMediaBackfillMode mode, boolean copyFailure) {
        try (Harness h = new Harness(target, mode, copyFailure, "UUUUUU")) {
            RestaurantMediaBackfillSummary stopped = h.runner.execute();
            assertThat(stopped.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.FAILED);
            assertThat(stopped.failedCount()).isEqualTo(4);
            assertThat(stopped.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_UNREADABLE, 5L);
            verify(h.port, never()).inspect(h.reference(6));
            if (mode.usesCheckpoint()) {
                assertThat(h.cursor.get()).isEqualTo(4);
                verify(h.checkpoint, never()).recordProgress(eq(h.lease), eq(5L), any(), any());
                verify(h.checkpoint, never()).complete(any());
            } else {
                assertThat(stopped.scannedCount()).isEqualTo(5);
            }
            h.pattern = mode.usesCheckpoint() ? "SSSSUS" : "UUUUSS";
            RestaurantMediaBackfillSummary resumed = h.runner.execute();
            assertThat(resumed.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.COMPLETED);
            assertThat(resumed.sourceFailuresThisExecution()).containsEntry(Reason.SOURCE_UNREADABLE, mode.usesCheckpoint() ? 1L : 4L);
            if (mode.usesCheckpoint()) {
                assertThat(h.cursor.get()).isEqualTo(6);
                verify(h.port).inspect(h.reference(1));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void 네번의_오류는_중단하지_않고_다른_결과가_사이에_있으면_계수를_초기화한다(
            RestaurantMediaBackfillTarget target, RestaurantMediaBackfillMode mode, boolean copyFailure) {
        for (String pattern : List.of("UUUU", "UUUUSUUUU", "UUUUBUUUU", "UUUUMUUUU")) {
            try (Harness h = new Harness(target, mode, copyFailure, pattern)) {
                RestaurantMediaBackfillSummary result = h.runner.execute();
                assertThat(result.status()).as(pattern).isEqualTo(RestaurantMediaBackfillSummary.Status.COMPLETED);
                assertThat(result.sourceFailuresThisExecution().get(Reason.SOURCE_UNREADABLE))
                        .isEqualTo(pattern.equals("UUUU") ? 4L : 8L);
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        private final RestaurantMediaBackfillTarget target;
        private final RestaurantMediaBackfillMode mode;
        private final boolean copyFailure;
        private final RestaurantMediaBackfillCandidateReader reader = mock(RestaurantMediaBackfillCandidateReader.class);
        private final RestaurantMediaBackfillCheckpointStore checkpoint = mock(RestaurantMediaBackfillCheckpointStore.class);
        private final RestaurantMediaBackfillAttachmentService attachment = mock(RestaurantMediaBackfillAttachmentService.class);
        private final MediaBackfillPort port = mock(MediaBackfillPort.class);
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final AtomicLong cursor = new AtomicLong();
        private final AtomicLong scanned = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final UUID runId = UUID.randomUUID();
        private final Lease lease;
        private final RestaurantMediaBackfillRunner runner;
        private final List<RestaurantMediaBackfillCandidate> candidates = new ArrayList<>();
        private String pattern;

        private Harness(RestaurantMediaBackfillTarget target, RestaurantMediaBackfillMode mode, boolean copyFailure, String pattern) {
            this.target = target;
            this.mode = mode;
            this.copyFailure = copyFailure;
            this.pattern = pattern;
            lease = mode.usesCheckpoint() ? new Lease(runId, UUID.randomUUID(), target, mode, pattern.length()) : null;
            RestaurantMediaBackfillProperties properties = new RestaurantMediaBackfillProperties(
                    true, runId.toString(), target, mode, 2, 10, Duration.ofMinutes(5), 3, Duration.ZERO);
            runner = new RestaurantMediaBackfillRunner(properties, reader, checkpoint, attachment, port, registry);
            for (int i = 1; i <= pattern.length(); i++) {
                String key = pattern.charAt(i - 1) == 'B' ? " " : "fixture/" + i + ".jpg";
                candidates.add(new RestaurantMediaBackfillCandidate(target, i, 100L, key));
            }
            given(reader.findUpperBound(target)).willReturn((long) pattern.length());
            given(reader.findBatch(eq(target), anyLong(), anyLong(), anyInt()))
                    .willAnswer(call -> {
                        long from = call.getArgument(1);
                        long upper = call.getArgument(2);
                        int limit = call.getArgument(3);
                        return candidates.stream().filter(c -> c.associationId() > from && c.associationId() <= upper)
                                .limit(limit).toList();
                    });
            given(checkpoint.acquire(eq(runId), eq(target), eq(mode), anyLong(), any()))
                    .willAnswer(call -> new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(Status.RUNNING)));
            given(checkpoint.pause(lease)).willReturn(true);
            given(checkpoint.find(runId)).willAnswer(call -> snapshot(Status.PAUSED));
            given(checkpoint.complete(lease)).willAnswer(call -> snapshot(Status.COMPLETED));
            doAnswer(call -> {
                cursor.set(call.getArgument(1));
                scanned.incrementAndGet();
                if (call.getArgument(2) == RestaurantMediaBackfillOutcome.FAILED) {
                    failed.incrementAndGet();
                }
                return null;
            }).when(checkpoint).recordProgress(eq(lease), anyLong(), any(), any());
            given(port.inspect(any())).willAnswer(call -> {
                MediaBackfillReference ref = call.getArgument(0);
                char outcome = this.pattern.charAt((int) ref.associationId() - 1);
                if (outcome == 'M') {
                    throw new MediaBackfillSourceException(Reason.SOURCE_MISSING);
                }
                if (outcome == 'U' && !copyFailure) {
                    throw new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE);
                }
                return new MediaBackfillInspectionInfo(IDENTITY, target.mediaTarget().purpose(), Optional.empty());
            });
            given(port.prepare(any(), any())).willAnswer(call -> {
                MediaBackfillReference ref = call.getArgument(0);
                if (this.pattern.charAt((int) ref.associationId() - 1) == 'U' && copyFailure) {
                    throw new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE);
                }
                return new MediaBackfillAssetInfo(UUID.randomUUID(), target.mediaTarget().purpose(),
                        IDENTITY, MediaBackfillAssetInfo.State.PROCESSING);
            });
        }

        private MediaBackfillReference reference(long id) {
            return new MediaBackfillReference(target.mediaTarget(), id, "fixture/" + id + ".jpg");
        }

        private Snapshot snapshot(Status status) {
            return new Snapshot(runId, target, mode, status, candidates.size(), cursor.get(), null,
                    scanned.get(), 0, 0, 0, failed.get());
        }

        @Override
        public void close() {
            registry.close();
        }
    }
}
