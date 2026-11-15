package org.sopt.hashi.media.internal.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaBackfillPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MediaBackfillConfig.class);

    @Test
    void 기본값은_backfill을_비활성화한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MediaBackfillProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void 명시한_실행_환경에서만_backfill을_활성화한다() {
        contextRunner.withPropertyValues("hashi.media.backfill.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MediaBackfillProperties.class).enabled()).isTrue();
        });
    }
}
