package org.sopt.hashi.media.internal.queue;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.sopt.hashi.media.service.MediaTransformResultApplication;
import org.sopt.hashi.media.service.MediaTransformResultService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hashi.media.queue", name = "enabled", havingValue = "true")
public class MediaTransformResultListener {

    private final MediaTransformResultParser parser;
    private final MediaTransformResultService resultService;
    private final MediaPipelineMetrics metrics;

    public MediaTransformResultListener(MediaTransformResultParser parser,
                                        MediaTransformResultService resultService,
                                        MediaPipelineMetrics metrics) {
        this.parser = parser;
        this.resultService = resultService;
        this.metrics = metrics;
    }

    @SqsListener(
            value = "${hashi.media.queue.result-queue-url}",
            factory = MediaResultSqsConfig.RESULT_LISTENER_FACTORY,
            acknowledgementMode = "MANUAL",
            maxMessagesPerPoll = "1"
    )
    public void consume(String body, Acknowledgement acknowledgement) {
        MediaTransformResult result;
        try {
            result = parser.parse(body);
        } catch (MediaTransformContractException e) {
            metrics.recordResultContractError();
            throw e;
        }
        MediaTransformResultApplication application;
        try {
            application = resultService.apply(result);
        } catch (MediaTransformContractException e) {
            metrics.recordResultContractError(result);
            throw e;
        } catch (RuntimeException e) {
            metrics.recordResultInternalError(result);
            throw e;
        }
        metrics.recordResult(result, application);
        acknowledgement.acknowledge();
    }
}
