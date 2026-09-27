package org.sopt.hashi.media.internal.cleanup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hashi.media.cleanup", name = "enabled", havingValue = "true")
public class MediaCleanupExecutionConfig {

    public static final String EXECUTOR = "mediaCleanupExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor mediaCleanupExecutor(MediaCleanupProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("media-cleanup-");
        executor.setStrictEarlyShutdown(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return executor;
    }
}
