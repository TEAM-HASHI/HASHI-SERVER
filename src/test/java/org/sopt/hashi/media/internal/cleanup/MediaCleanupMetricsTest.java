package org.sopt.hashi.media.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupMetrics.Stage;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties.Mode;
import org.sopt.hashi.media.service.MediaCleanupOutcome;
import org.sopt.hashi.media.service.MediaCleanupScanResult;

class MediaCleanupMetricsTest {

    @Test
    void 실제_삭제와_DRY_RUN을_구분하고_고정된_결과와_실패_분류만_기록한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            MediaCleanupMetrics metrics = new MediaCleanupMetrics(registry);
            metrics.recordAttempt(Mode.DRY_RUN, Stage.INITIAL, MediaCleanupOutcome.WOULD_PURGE);
            metrics.recordAttempt(Mode.DELETE, Stage.RESUME, MediaCleanupOutcome.PURGED);
            metrics.recordFailure(Mode.DELETE, Stage.RESUME,
                    new MediaCleanupStorageException(MediaCleanupStorageException.Reason.PARTIAL_DELETE));
            metrics.recordFailure(Mode.DELETE, Stage.SCAN, new IllegalStateException("fixture-private-payload"));
            metrics.recordRun(Mode.DELETE, new MediaCleanupScanResult(
                    MediaCleanupScanResult.Status.PARTIAL_FAILURE, 2, 1, 1), Duration.ofSeconds(3));
            metrics.recordDispatchRejected();

            assertThat(registry.get("hashi.media.cleanup.attempt")
                    .tags("mode", "dry_run", "outcome", "would_purge").counter().count()).isEqualTo(1);
            assertThat(registry.get("hashi.media.cleanup.attempt")
                    .tags("mode", "delete", "stage", "resume", "outcome", "purged").counter().count()).isEqualTo(1);
            assertThat(registry.get("hashi.media.cleanup.failure")
                    .tag("reason", "partial_delete").counter().count()).isEqualTo(1);
            assertThat(registry.get("hashi.media.cleanup.failure")
                    .tag("reason", "internal_error").counter().count()).isEqualTo(1);
            assertThat(registry.get("hashi.media.cleanup.scan.duration").timer().totalTime(TimeUnit.SECONDS))
                    .isEqualTo(3);
            assertThat(registry.get("hashi.media.cleanup.dispatch").counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString()).doesNotContain("fixture-private-payload"));
        } finally {
            registry.close();
        }
    }

    @Test
    void 지표_장애는_호출자를_실패시키거나_예외_원문을_로그에_남기지_않는다() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("fixture-private-counter"));
        when(registry.timer(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("fixture-private-timer"));
        MediaCleanupMetrics metrics = new MediaCleanupMetrics(registry);
        Logger logger = (Logger) LoggerFactory.getLogger(MediaCleanupMetrics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            metrics.recordAttempt(Mode.DELETE, Stage.INITIAL, MediaCleanupOutcome.INCOMPLETE);
            metrics.recordFailure(Mode.DELETE, Stage.SCAN, new IllegalStateException("fixture-private-cause"));
            metrics.recordRun(Mode.DELETE, new MediaCleanupScanResult(
                    MediaCleanupScanResult.Status.RETRY_PENDING, 1, 1, 0), Duration.ofSeconds(1));
            metrics.recordDispatchRejected();

            assertThat(appender.list).hasSize(4).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Failed to record a media cleanup metric");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
