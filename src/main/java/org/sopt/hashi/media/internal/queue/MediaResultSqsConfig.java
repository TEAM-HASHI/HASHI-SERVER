package org.sopt.hashi.media.internal.queue;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hashi.media.queue", name = "enabled", havingValue = "true")
public class MediaResultSqsConfig {

    public static final String RESULT_LISTENER_FACTORY =
            "mediaResultSqsListenerContainerFactory";

    @Bean(name = RESULT_LISTENER_FACTORY)
    SqsMessageListenerContainerFactory<Object> mediaResultSqsListenerContainerFactory(
            SqsAsyncClient sqsAsyncClient) {
        return SqsMessageListenerContainerFactory.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .configure(options -> options
                        .acknowledgementMode(AcknowledgementMode.MANUAL)
                        .acknowledgementInterval(Duration.ZERO)
                        .acknowledgementThreshold(0)
                        .maxMessagesPerPoll(1)
                        .queueNotFoundStrategy(QueueNotFoundStrategy.FAIL))
                .build();
    }
}
