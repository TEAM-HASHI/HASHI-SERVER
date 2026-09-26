package org.sopt.hashi.media.internal.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hashi.media.reconciliation", name = "enabled", havingValue = "true")
public class MediaReconciliationExecutionConfig {

    public static final String EXECUTOR = "mediaReconciliationExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor mediaReconciliationExecutor(MediaReconciliationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("media-reconciliation-");
        executor.setStrictEarlyShutdown(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return executor;
    }
}
