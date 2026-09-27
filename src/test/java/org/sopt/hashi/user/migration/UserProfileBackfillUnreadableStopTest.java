package org.sopt.hashi.user.migration;

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
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillTarget;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Lease;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Status;

class UserProfileBackfillUnreadableStopTest {
    private static final String IDENTITY = "a".repeat(64);

    static Stream<Arguments> cases() {
        return Arrays.stream(new MediaBackfillTarget[]{MediaBackfillTarget.USER_PROFILE}).flatMap(target ->
                Arrays.stream(UserProfileBackfillMode.values()).flatMap(mode ->
                        mode == UserProfileBackfillMode.PREPARE
                                ? Stream.of(Arguments.of(target, mode, false), Arguments.of(target, mode, true))
                                : Stream.of(Arguments.of(target, mode, false))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void batch를_넘어_다섯번째_오류에서_멈추고_같은_실행을_재개한다(
            MediaBackfillTarget target, UserProfileBackfillMode mode, boolean copyFailure) {
        try (Harness h = new Harness(target, mode, copyFailure, "UUUUUU")) {
            UserProfileBackfillSummary stopped = h.runner.execute();
            assertThat(stopped.status()).isEqualTo(UserProfileBackfillSummary.Status.FAILED);
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
            UserProfileBackfillSummary resumed = h.runner.execute();
            assertThat(resumed.status()).isEqualTo(UserProfileBackfillSummary.Status.COMPLETED);
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
            MediaBackfillTarget target, UserProfileBackfillMode mode, boolean copyFailure) {
        for (String pattern : List.of("UUUU", "UUUUSUUUU", "UUUUBUUUU", "UUUUMUUUU")) {
            try (Harness h = new Harness(target, mode, copyFailure, pattern)) {
                UserProfileBackfillSummary result = h.runner.execute();
                assertThat(result.status()).as(pattern).isEqualTo(UserProfileBackfillSummary.Status.COMPLETED);
                assertThat(result.sourceFailuresThisExecution().get(Reason.SOURCE_UNREADABLE))
                        .isEqualTo(pattern.equals("UUUU") ? 4L : 8L);
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        private final MediaBackfillTarget target;
        private final UserProfileBackfillMode mode;
        private final boolean copyFailure;
        private final UserProfileBackfillCandidateReader reader = mock(UserProfileBackfillCandidateReader.class);
        private final UserProfileBackfillCheckpointStore checkpoint = mock(UserProfileBackfillCheckpointStore.class);
        private final UserProfileBackfillAttachmentService attachment = mock(UserProfileBackfillAttachmentService.class);
        private final MediaBackfillPort port = mock(MediaBackfillPort.class);
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final AtomicLong cursor = new AtomicLong();
        private final AtomicLong scanned = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final UUID runId = UUID.randomUUID();
        private final Lease lease;
        private final UserProfileBackfillRunner runner;
        private final List<UserProfileBackfillCandidate> candidates = new ArrayList<>();
        private String pattern;

        private Harness(MediaBackfillTarget target, UserProfileBackfillMode mode, boolean copyFailure, String pattern) {
            this.target = target;
            this.mode = mode;
            this.copyFailure = copyFailure;
            this.pattern = pattern;
            lease = mode.usesCheckpoint() ? new Lease(runId, UUID.randomUUID(), mode, pattern.length()) : null;
            UserProfileBackfillProperties properties = new UserProfileBackfillProperties(
                    true, runId.toString(), mode, 2, 10, Duration.ofMinutes(5), 3, Duration.ZERO);
            runner = new UserProfileBackfillRunner(properties, reader, checkpoint, attachment, port, registry);
            for (int i = 1; i <= pattern.length(); i++) {
                String key = pattern.charAt(i - 1) == 'B' ? " " : "fixture/" + i + ".jpg";
                candidates.add(new UserProfileBackfillCandidate(i, key));
            }
            given(reader.findUpperBound()).willReturn((long) pattern.length());
            given(reader.findBatch(anyLong(), anyLong(), anyInt()))
                    .willAnswer(call -> {
                        long from = call.getArgument(0);
                        long upper = call.getArgument(1);
                        int limit = call.getArgument(2);
                        return candidates.stream().filter(c -> c.userId() > from && c.userId() <= upper)
                                .limit(limit).toList();
                    });
            given(checkpoint.acquire(eq(runId), eq(mode), anyLong(), any()))
                    .willAnswer(call -> new Acquisition(AcquisitionState.ACQUIRED, lease, snapshot(Status.RUNNING)));
            given(checkpoint.pause(lease)).willReturn(true);
            given(checkpoint.find(runId)).willAnswer(call -> snapshot(Status.PAUSED));
            given(checkpoint.complete(lease)).willAnswer(call -> snapshot(Status.COMPLETED));
            doAnswer(call -> {
                cursor.set(call.getArgument(1));
                scanned.incrementAndGet();
                if (call.getArgument(2) == UserProfileBackfillOutcome.FAILED) {
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
                return new MediaBackfillInspectionInfo(IDENTITY, MediaAssetPurpose.PROFILE, Optional.empty());
            });
            given(port.prepare(any(), any())).willAnswer(call -> {
                MediaBackfillReference ref = call.getArgument(0);
                if (this.pattern.charAt((int) ref.associationId() - 1) == 'U' && copyFailure) {
                    throw new MediaBackfillSourceException(Reason.SOURCE_UNREADABLE);
                }
                return new MediaBackfillAssetInfo(UUID.randomUUID(), MediaAssetPurpose.PROFILE,
                        IDENTITY, MediaBackfillAssetInfo.State.PROCESSING);
            });
        }

        private MediaBackfillReference reference(long id) {
            return new MediaBackfillReference(target, id, "fixture/" + id + ".jpg");
        }

        private Snapshot snapshot(Status status) {
            return new Snapshot(runId, mode, status, candidates.size(), cursor.get(), null,
                    scanned.get(), 0, 0, 0, failed.get());
        }

        @Override
        public void close() {
            registry.close();
        }
    }
}
