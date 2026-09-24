package org.sopt.hashi.media.internal.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaReconciliationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaReconciliationPropertiesConfig.class);

    @Test
    void 기본값은_비활성_DRY_RUN이고_7일_보존이다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MediaReconciliationProperties properties = context.getBean(MediaReconciliationProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.mode()).isEqualTo(MediaReconciliationProperties.Mode.DRY_RUN);
            assertThat(properties.orphanRetention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.canDelete()).isFalse();
        });
    }

    @Test
    void 일주일보다_짧은_보존은_애플리케이션_시작을_거부한다() {
        runner.withPropertyValues("hashi.media.reconciliation.orphan-retention=6d")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabled와_DELETE가_모두_있을_때만_삭제할_수_있다() {
        runner.withPropertyValues(
                        "hashi.media.reconciliation.enabled=true",
                        "hashi.media.reconciliation.mode=DELETE")
                .run(context -> assertThat(context.getBean(MediaReconciliationProperties.class).canDelete())
                        .isTrue());
    }
}
