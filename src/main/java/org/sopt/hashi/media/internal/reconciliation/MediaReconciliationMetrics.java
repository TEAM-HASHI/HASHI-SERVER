package org.sopt.hashi.media.internal.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationProperties.Mode;
import org.sopt.hashi.media.service.MediaReconciliationDecision;
import org.sopt.hashi.media.service.MediaReconciliationScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class MediaReconciliationMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaReconciliationMetrics.class);

    private final MeterRegistry registry;

    public MediaReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDecision(MediaObjectLocation location, Mode mode, MediaReconciliationDecision decision) {
        recordSafely(() -> registry.counter("hashi.media.reconciliation.object",
                "location", tag(location), "mode", tag(mode), "outcome", tag(decision)).increment());
    }

    public void recordDeleted(MediaObjectLocation location) {
        recordSafely(() -> registry.counter("hashi.media.reconciliation.object",
                "location", tag(location), "mode", "delete", "outcome", "deleted").increment());
    }

    public void recordFailure(MediaObjectLocation location, RuntimeException failure) {
        String reason;
        if (failure instanceof MediaReconciliationStorageException storage) {
            reason = tag(storage.getReason());
        } else if (failure instanceof DataAccessException) {
            reason = "database_unavailable";
        } else {
            reason = "internal_error";
        }
        String finalReason = reason;
        recordSafely(() -> registry.counter("hashi.media.reconciliation.failure",
                "location", tag(location), "reason", finalReason).increment());
    }

    public void recordRun(Mode mode, MediaReconciliationScanResult result, Duration elapsed) {
        recordSafely(() -> registry.timer("hashi.media.reconciliation.scan.duration",
                "mode", tag(mode), "status", tag(result.status())).record(elapsed));
    }

    public void recordDispatchRejected() {
        recordSafely(() -> registry.counter("hashi.media.reconciliation.dispatch",
                "outcome", "rejected").increment());
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private void recordSafely(Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record a media reconciliation metric");
        }
    }
}
