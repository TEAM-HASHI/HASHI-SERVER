package org.sopt.hashi.restaurant.internal.map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "hashi.map.location-job", name = "enabled", havingValue = "true")
public class LocationJobScheduler {
    private final RestaurantLocationWorker worker;

    public LocationJobScheduler(RestaurantLocationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${hashi.map.location-job.poll-delay:5000}")
    public void poll() {
        try {
            worker.runOnce();
        } catch (RuntimeException exception) {
            // Lease expiry recovers failed DB completions. Exception text can contain SQL/input values.
            log.warn("Location worker database cycle failed; durable jobs will be retried");
        }
    }
}
