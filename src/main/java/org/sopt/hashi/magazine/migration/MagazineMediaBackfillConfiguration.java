package org.sopt.hashi.magazine.migration;

import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@EnableAsync
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MagazineMediaBackfillProperties.class)
public class MagazineMediaBackfillConfiguration {

    static final String EXECUTOR = "magazineMediaBackfillExecutor";

    @Bean(name = EXECUTOR)
    @ConditionalOnProperty(
            prefix = "hashi.magazine.media-backfill",
            name = "enabled",
            havingValue = "true"
    )
    public Executor magazineMediaBackfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("magazine-media-backfill-");
        // 종료 시 현재 작업을 중단해도 lease와 멱등 prepare를 통해 같은 run-id로 재개한다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(30);
        executor.setStrictEarlyShutdown(true);
        return executor;
    }
}
