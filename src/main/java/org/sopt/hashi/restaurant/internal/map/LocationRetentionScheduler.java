package org.sopt.hashi.restaurant.internal.map;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Private executor avoids changing Spring's default scheduler used by unrelated media work. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hashi.map.maintenance", name = "retention-enabled", havingValue = "true")
public class LocationRetentionScheduler implements SmartLifecycle {
    private final LocationRetentionService service;
    private final LocationRetentionProperties options;
    private volatile ScheduledExecutorService executor;
    private final ObjectProvider<LocationMaintenanceMetrics> metrics;

    public LocationRetentionScheduler(LocationRetentionService service, LocationRetentionProperties options,
                                      ObjectProvider<LocationMaintenanceMetrics> metrics) {
        this.service = service;
        this.options = options;
        this.metrics = metrics;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "location-retention");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::purge, 0, options.pollDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    private void purge() {
        try {
            try {
                int registered = service.refresh(options);
                if (registered > 0) {
                    log.info("Location refresh jobs registered: count={}", registered);
                }
            } catch (RuntimeException exception) {
                log.warn("Location refresh registration failed: type={}", exception.getClass().getSimpleName());
            }
            var report = service.purge(options);
            log.info("Location retention: {}", report);
            if (report.dueRemaining() > 0) {
                log.warn("Location retention backlog requires operator action: due={}, overdue={}",
                        report.dueRemaining(), report.overdueRemaining());
            }
        } catch (RuntimeException exception) {
            // No exception messages: SQL/connection exceptions can carry private values.
            log.error("Location retention failed; check scheduler and database health before the retention deadline");
        } finally {
            try {
                metrics.ifAvailable(LocationMaintenanceMetrics::refresh);
            } catch (RuntimeException exception) {
                log.warn("Location maintenance metrics failed: type={}", exception.getClass().getSimpleName());
            }
        }
    }
}
