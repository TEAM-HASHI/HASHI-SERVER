package org.sopt.hashi.media.internal.recovery;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hashi.media.queue", name = "enabled", havingValue = "true")
public class MediaEventPublicationRecovery {

    private final IncompleteEventPublications incompletePublications;
    private final MediaRecoveryProperties properties;
    private final MediaPipelineMetrics metrics;
    private final Clock clock;

    public MediaEventPublicationRecovery(
            IncompleteEventPublications incompletePublications,
            MediaRecoveryProperties properties,
            MediaPipelineMetrics metrics,
            @Qualifier("japanClock") Clock clock) {
        this.incompletePublications = incompletePublications;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resubmitOnStartup() {
        if (properties.enabled()) {
            incompletePublications.resubmitIncompletePublications(
                    limited(publication -> publication.getEvent()
                            instanceof MediaProcessingRequestedEvent));
            metrics.recordEprResubmission("startup");
        }
    }

    @Scheduled(
            fixedDelayString = "${hashi.media.recovery.epr-resubmit-interval:1m}",
            initialDelayString = "${hashi.media.recovery.epr-resubmit-interval:1m}"
    )
    public void resubmitOldPublications() {
        if (properties.enabled()) {
            Instant publishedBefore = Instant.now(clock).minus(properties.eprResubmitAge());
            incompletePublications.resubmitIncompletePublications(limited(publication ->
                    publication.getEvent() instanceof MediaProcessingRequestedEvent
                            && !publication.getPublicationDate().isAfter(publishedBefore)));
            metrics.recordEprResubmission("scheduled");
        }
    }

    private Predicate<org.springframework.modulith.events.EventPublication> limited(
            Predicate<org.springframework.modulith.events.EventPublication> eligible) {
        AtomicInteger selected = new AtomicInteger();
        return publication -> eligible.test(publication)
                && selected.getAndIncrement() < properties.eprResubmitBatchSize();
    }
}
