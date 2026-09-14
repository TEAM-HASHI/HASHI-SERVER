package org.sopt.hashi.media.internal.queue;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class MediaQueueExecutionConfig {

    public static final String PUBLISHER_EXECUTOR = "mediaQueuePublisherExecutor";

    @Bean(name = PUBLISHER_EXECUTOR)
    public Executor mediaQueuePublisherExecutor(MediaQueueProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.publisherCorePoolSize());
        executor.setMaxPoolSize(properties.publisherMaxPoolSize());
        executor.setQueueCapacity(properties.publisherQueueCapacity());
        executor.setThreadNamePrefix("media-sqs-publisher-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(
                Math.toIntExact(properties.publisherShutdownAwait().toSeconds()));
        executor.setStrictEarlyShutdown(true);
        return executor;
    }
}
