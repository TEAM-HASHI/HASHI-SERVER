package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class LocationJobConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LocationJobConfiguration.class);

    @Test
    void 기본_비활성은_키와_운영_지도설정_없이_부팅한다() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(LocationJobProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void worker를_켜도_지도_설정이_부족하면_기존_애플리케이션_부팅을_막지_않는다() {
        context.withPropertyValues("hashi.map.location-job.enabled=true").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(LocationJobProperties.class).isConfigured()).isFalse();
        });
    }

    @Test
    void 위치_HTTP_대기_중에도_기본_스케줄러의_다른_작업은_실행된다() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RestaurantLocationWorker worker = mock(RestaurantLocationWorker.class);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(worker).runOnce();
        context.withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(LocationJobScheduler.class)
                .withBean(RestaurantLocationWorker.class, () -> worker)
                .withPropertyValues("hashi.map.location-job.enabled=true", "hashi.map.location-job.poll-delay=60000")
                .run(result -> {
                    try {
                        assertThat(result).hasNotFailed();
                        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                        CountDownLatch otherTask = new CountDownLatch(1);
                        result.getBean("taskScheduler", TaskScheduler.class)
                                .schedule(otherTask::countDown, Instant.now());
                        assertThat(otherTask.await(2, TimeUnit.SECONDS)).isTrue();
                    } finally {
                        release.countDown();
                    }
                });
    }
}
