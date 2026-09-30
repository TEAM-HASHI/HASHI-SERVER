package org.sopt.hashi.restaurant.internal.map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocationJobProperties.class)
public class LocationJobConfiguration {
    public static final String SCHEDULER = "restaurantLocationTaskScheduler";

    @Bean(name = SCHEDULER, defaultCandidate = false)
    @ConditionalOnProperty(prefix = "hashi.map.location-job", name = "enabled", havingValue = "true")
    ThreadPoolTaskScheduler restaurantLocationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("restaurant-location-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
