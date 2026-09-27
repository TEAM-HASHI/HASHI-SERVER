package org.sopt.hashi.media.internal.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@ConditionalOnProperty(prefix = "hashi.media.queue", name = "enabled", havingValue = "true")
public class SqsMediaTransformRequestPublisher implements MediaTransformRequestPublisher {

    private final SqsAsyncClient sqsAsyncClient;
    private final ObjectMapper objectMapper;
    private final MediaQueueProperties properties;

    public SqsMediaTransformRequestPublisher(SqsAsyncClient sqsAsyncClient,
                                             ObjectMapper objectMapper,
                                             MediaQueueProperties properties) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(MediaTransformRequest request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            sqsAsyncClient.sendMessage(SendMessageRequest.builder()
                            .queueUrl(properties.requestQueueUrl())
                            .messageBody(body)
                            .build())
                    .join();
        } catch (JsonProcessingException | CompletionException e) {
            throw new MediaQueuePublishException("failed to publish media transform request", e);
        }
    }
}
