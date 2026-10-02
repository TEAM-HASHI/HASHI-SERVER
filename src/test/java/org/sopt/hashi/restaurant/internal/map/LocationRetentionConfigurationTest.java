package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocationRetentionConfigurationTest {
    @Test
    void 영구정리의_기본한도를_유지하고_scheduler는_기본비활성이다() {
        new ApplicationContextRunner()
                .withUserConfiguration(LocationRetentionConfiguration.class, LocationRetentionScheduler.class)
                .withBean(LocationRetentionService.class, () -> mock(LocationRetentionService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LocationRetentionScheduler.class);
                    assertThat(context.getBean(LocationRetentionProperties.class))
                            .isEqualTo(new LocationRetentionProperties(50, 1, Duration.ofDays(1),
                                    Duration.ofHours(1), false, Duration.ofMinutes(1)));
                });
    }

    @Test
    void 기존_외부설정이름을_영구정리설정으로_바인딩한다() {
        new ApplicationContextRunner().withUserConfiguration(LocationRetentionConfiguration.class)
                .withPropertyValues("hashi.map.maintenance.batch-size=25", "hashi.map.maintenance.max-batches=3",
                        "hashi.map.maintenance.refresh-ahead=2d", "hashi.map.maintenance.purge-ahead=2h",
                        "hashi.map.maintenance.retention-enabled=true", "hashi.map.maintenance.poll-delay=2m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LocationRetentionProperties.class))
                            .isEqualTo(new LocationRetentionProperties(25, 3, Duration.ofDays(2),
                                    Duration.ofHours(2), true, Duration.ofMinutes(2)));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"batch-size=0", "batch-size=101", "max-batches=0", "max-batches=101",
            "poll-delay=999ms", "poll-delay=61m", "purge-ahead=119s", "refresh-ahead=1h",
            "refresh-ahead=31d", "refresh-ahead=PT3600.000000001S"})
    void 영구정리설정도_기존한도와_기한전여유시간을_검증한다(String property) {
        new ApplicationContextRunner().withUserConfiguration(LocationRetentionConfiguration.class)
                .withPropertyValues("hashi.map.maintenance." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 활성정리scheduler는_전용thread에서_실행하고_context와_함께_종료된다() {
        var service = mock(LocationRetentionService.class);
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        AtomicReference<LocationRetentionScheduler> scheduler = new AtomicReference<>();
        when(service.purge(any())).thenAnswer(invocation -> {
            name.set(Thread.currentThread().getName());
            called.countDown();
            return new LocationRetentionService.Report(Instant.now(), 0, 0, 0, 0);
        });
        new ApplicationContextRunner()
                .withUserConfiguration(LocationRetentionConfiguration.class, LocationRetentionScheduler.class)
                .withBean(LocationRetentionService.class, () -> service)
                .withPropertyValues("hashi.map.maintenance.retention-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    scheduler.set(context.getBean(LocationRetentionScheduler.class));
                    assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(name.get()).isEqualTo("location-retention");
                    assertThat(scheduler.get().isRunning()).isTrue();
                });
        assertThat(scheduler.get().isRunning()).isFalse();
    }
}
