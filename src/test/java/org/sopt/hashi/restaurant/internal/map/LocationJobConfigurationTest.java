package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
