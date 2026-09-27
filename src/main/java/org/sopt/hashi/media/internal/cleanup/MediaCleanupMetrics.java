package org.sopt.hashi.media.internal.cleanup;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties.Mode;
import org.sopt.hashi.media.service.MediaCleanupOutcome;
import org.sopt.hashi.media.service.MediaCleanupScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MediaCleanupMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaCleanupMetrics.class);

    private final MeterRegistry registry;

    public MediaCleanupMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAttempt(Mode mode, Stage stage, MediaCleanupOutcome outcome) {
        recordSafely(() -> registry.counter("hashi.media.cleanup.attempt", "mode", tag(mode),
                "stage", tag(stage), "outcome", tag(outcome)).increment());
    }

    public void recordFailure(Mode mode, Stage stage, RuntimeException failure) {
        // 예외 원문은 SQL, S3 key, token 등을 포함할 수 있으므로 고정된 분류만 기록한다.
        String reason = failure instanceof MediaCleanupStorageException storage
                ? tag(storage.getReason()) : "internal_error";
        recordSafely(() -> registry.counter("hashi.media.cleanup.failure", "mode", tag(mode),
                "stage", tag(stage), "reason", reason).increment());
    }

    public void recordRun(Mode mode, MediaCleanupScanResult result, Duration elapsed) {
        recordSafely(() -> registry.timer("hashi.media.cleanup.scan.duration", "mode", tag(mode),
                "status", tag(result.status())).record(elapsed));
    }

    public void recordDispatchRejected() {
        recordSafely(() -> registry.counter("hashi.media.cleanup.dispatch", "outcome", "rejected").increment());
    }

    public enum Stage {
        INITIAL,
        RESUME,
        SCAN
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private void recordSafely(Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record a media cleanup metric");
        }
    }
}
