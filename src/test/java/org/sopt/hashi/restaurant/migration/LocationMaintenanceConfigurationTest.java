package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocationMaintenanceConfigurationTest {
    @Test
    void 기본설정은_확인모드이고_모든변경명령은_명시동의를_요구한다() {
        new ApplicationContextRunner().withUserConfiguration(LocationMaintenanceConfiguration.class)
                .run(context -> {
                    var options = context.getBean(LocationMaintenanceProperties.class);
                    assertThat(options.command()).isEqualTo(LocationMaintenanceProperties.Command.DRY_RUN);
                    assertThat(options.retentionEnabled()).isFalse();
                    assertThat(options.execute()).isFalse();
                    var runner = new LocationMaintenanceRunner(null, null, null);
                    for (var command : LocationMaintenanceProperties.Command.values()) {
                        if (command == LocationMaintenanceProperties.Command.DRY_RUN
                                || command == LocationMaintenanceProperties.Command.STATUS) { continue; }
                        assertThatThrownBy(() -> runner.execute(options(command, false, null, null, null)))
                                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("execute=true");
                    }
                });
    }

    @Test
    void 호출상한계산의_전제인_worker하드최대여덟시도를_검증한다() {
        assertThat(LocationMaintenanceProperties.CALLS_PER_JOB).isEqualTo(8);
        assertThatThrownBy(() -> new LocationJobProperties(false, null, null, null, null, null, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기한전여유시간과_한도와_고정범위를_검증한다() {
        assertThatThrownBy(() -> options(LocationMaintenanceProperties.Command.DRY_RUN, false,
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> options(LocationMaintenanceProperties.Command.DRY_RUN, false,
                Duration.ofDays(31), null, null)).isInstanceOf(IllegalArgumentException.class);
        var start = options(LocationMaintenanceProperties.Command.START, true, null, null, null);
        assertThatThrownBy(start::requiredUpperId).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정리scheduler는_다른스케줄러나_HTTPexecutor없이_전용thread에서_실행하고_종료된다() throws Exception {
        var service = mock(LocationRetentionService.class);
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        when(service.purge(any())).thenAnswer(invocation -> {
            name.set(Thread.currentThread().getName());
            called.countDown();
            return new LocationRetentionService.Report(Instant.now(), 0, 0, 0, 0);
        });
        var scheduler = new LocationRetentionScheduler(service,
                options(LocationMaintenanceProperties.Command.DRY_RUN, false, null, null, null));
        try {
            scheduler.start();
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(name.get()).isEqualTo("location-retention");
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    private static LocationMaintenanceProperties options(LocationMaintenanceProperties.Command command,
            boolean execute, Duration refresh, Duration purge, Duration poll) {
        return new LocationMaintenanceProperties(command, null, execute, UUID.randomUUID(), 0, null,
                null, null, null, null, refresh, purge, false, poll);
    }
}
