package org.sopt.hashi.media.internal.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.media.internal.metrics.MediaAssetMetricsRefresher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hashi.media.queue", name = "enabled", havingValue = "true")
public class MediaStartupRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaStartupRecovery.class);

    private final MediaEventPublicationRecovery publications;
    private final MediaProcessingRecoveryScheduler processing;
    private final MediaAssetMetricsRefresher metrics;

    public MediaStartupRecovery(
            MediaEventPublicationRecovery publications,
            MediaProcessingRecoveryScheduler processing,
            MediaAssetMetricsRefresher metrics) {
        this.publications = publications;
        this.processing = processing;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        runSafely("publication", publications::resubmitOnStartup);
        runSafely("processing", processing::recoverOnStartup);
        runSafely("metrics", metrics::refreshOnStartup);
    }

    private void runSafely(String task, Runnable recovery) {
        try {
            // Catch outside the collaborator proxy, including transaction begin/commit failures.
            recovery.run();
        } catch (RuntimeException failure) {
            // Provider messages may contain object keys or credentials; record only the type.
            LOGGER.warn("Media startup task failed; scheduled execution will retry: task={}, errorType={}",
                    task, failure.getClass().getSimpleName());
        }
    }
}
