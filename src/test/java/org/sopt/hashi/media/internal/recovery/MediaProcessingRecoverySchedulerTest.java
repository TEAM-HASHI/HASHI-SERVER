package org.sopt.hashi.media.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;

class MediaProcessingRecoverySchedulerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T09:00:00Z"),
            ZoneId.of("Asia/Tokyo")
    );

    private final MediaProcessingRecoveryReader reader =
            mock(MediaProcessingRecoveryReader.class);
    private final MediaProcessingRecoveryTransactionService transactionService =
            mock(MediaProcessingRecoveryTransactionService.class);
    private final MediaPipelineMetrics metrics = mock(MediaPipelineMetrics.class);

    @Test
    void keyset_batch별로_정체_job을_최대_횟수_정책으로_재발행한다() {
        MediaRecoveryProperties properties = properties(true);
        MediaProcessingRecoveryCandidate first = candidate(1, 30);
        MediaProcessingRecoveryCandidate second = candidate(2, 20);
        MediaProcessingRecoveryCandidate third = candidate(3, 10);
        given(reader.findBatch(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(3),
                any(MediaProcessingRecoveryCursor.class),
                eq(2)))
                .willReturn(List.of(first, second))
                .willReturn(List.of(third));
        given(transactionService.requestRetryIfStillStalled(
                any(MediaProcessingRecoveryCandidate.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt()))
                .willReturn(true, false, true);
        MediaProcessingRecoveryScheduler scheduler = new MediaProcessingRecoveryScheduler(
                properties, reader, transactionService, metrics, CLOCK);

        int requested = scheduler.recoverStalledProcessing();

        assertThat(requested).isEqualTo(2);
        verify(reader, times(2)).findBatch(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(3),
                any(MediaProcessingRecoveryCursor.class),
                eq(2));
        verify(metrics, times(2)).recordRecovery("requested");
        verify(metrics).recordRecovery("stale");
    }

    @Test
    void 복구가_중지되면_DB를_조회하지_않는다() {
        MediaProcessingRecoveryScheduler scheduler = new MediaProcessingRecoveryScheduler(
                properties(false), reader, transactionService, metrics, CLOCK);

        assertThat(scheduler.recoverStalledProcessing()).isZero();

        verify(reader, never()).findBatch(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(MediaProcessingRecoveryCursor.class),
                anyInt());
    }

    @Test
    void 애플리케이션_시작_직후에도_이미_정체된_job을_조회한다() {
        MediaProcessingRecoveryCandidate stalled = candidate(1, 30);
        given(reader.findBatch(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(3),
                any(MediaProcessingRecoveryCursor.class),
                eq(2)))
                .willReturn(List.of(stalled));
        given(transactionService.requestRetryIfStillStalled(
                eq(stalled),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(3)))
                .willReturn(true);
        MediaProcessingRecoveryScheduler scheduler = new MediaProcessingRecoveryScheduler(
                properties(true), reader, transactionService, metrics, CLOCK);

        scheduler.recoverOnStartup();

        verify(transactionService).requestRetryIfStillStalled(
                eq(stalled),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(3));
        verify(metrics).recordRecovery("requested");
    }

    private MediaProcessingRecoveryCandidate candidate(long id, int minutesAgo) {
        return new MediaProcessingRecoveryCandidate(
                id,
                UUID.randomUUID(),
                LocalDateTime.now(CLOCK).minusMinutes(minutesAgo)
        );
    }

    private MediaRecoveryProperties properties(boolean enabled) {
        return new MediaRecoveryProperties(
                enabled,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                3,
                2,
                2,
                Duration.ofSeconds(30),
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofDays(7),
                Duration.ofDays(7)
        );
    }
}
