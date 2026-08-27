package org.sopt.hashi.media.internal.event;

import java.util.Optional;
import org.sopt.hashi.media.internal.job.MediaProcessingRequestReader;
import org.sopt.hashi.media.internal.queue.MediaQueueExecutionConfig;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;
import org.sopt.hashi.media.internal.queue.MediaTransformRequestPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MediaProcessingRequestPublisher {

    public static final String LISTENER_ID = "media-processing-sqs-publisher-v1";

    private final MediaProcessingRequestReader requestReader;
    private final ObjectProvider<MediaTransformRequestPublisher> publisherProvider;

    public MediaProcessingRequestPublisher(
            MediaProcessingRequestReader requestReader,
            ObjectProvider<MediaTransformRequestPublisher> publisherProvider) {
        this.requestReader = requestReader;
        this.publisherProvider = publisherProvider;
    }

    @Async(MediaQueueExecutionConfig.PUBLISHER_EXECUTOR)
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = false,
            id = LISTENER_ID
    )
    public void publish(MediaProcessingRequestedEvent event) {
        Optional<MediaTransformRequest> request =
                requestReader.findCurrent(event.assetId(), event.jobId());
        if (request.isEmpty()) {
            return;
        }
        MediaTransformRequestPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            throw new IllegalStateException("media transform request publisher is unavailable");
        }
        publisher.publish(request.get());
    }
}
