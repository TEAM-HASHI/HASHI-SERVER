package org.sopt.hashi.media.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaCleanupPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaCleanupPropertiesConfig.class, MediaCleanupStorageConfig.class)
            .withPropertyValues("hashi.storage.cloudfront-domain=https://cdn.hashi.test");

    @Test
    void 기본값은_비활성과_DRY_RUN이고_삭제_스토리지를_만들지_않는다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MediaCleanupStorage.class);
            MediaCleanupProperties properties = context.getBean(MediaCleanupProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.canDelete()).isFalse();
            assertThat(properties.mode()).isEqualTo(MediaCleanupProperties.Mode.DRY_RUN);
            assertThat(properties.uploadSafetyWindow()).isNull();
            assertThat(properties.scanBatchSize()).isEqualTo(25);
            assertThat(properties.storagePageSize()).isEqualTo(1000);
        });
    }

    @Test
    void 비활성이면_빈_유예시간_환경값도_허용한다() {
        runner.withPropertyValues("hashi.media.cleanup.upload-safety-window=")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MediaCleanupStorage.class));
    }

    @Test
    void 명시적인_양수_유예시간이_없으면_활성화를_거부한다() {
        runner.withPropertyValues("hashi.media.cleanup.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 활성화해도_모드를_지정하지_않으면_삭제하지_않는다() {
        runner.withPropertyValues("hashi.media.cleanup.enabled=true", "hashi.media.cleanup.upload-safety-window=1h")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MediaCleanupStorage.class);
                    MediaCleanupProperties properties = context.getBean(MediaCleanupProperties.class);
                    assertThat(properties.mode()).isEqualTo(MediaCleanupProperties.Mode.DRY_RUN);
                    assertThat(properties.canDelete()).isFalse();
                    assertThat(properties.uploadSafetyWindow()).isEqualTo(Duration.ofHours(1));
                });
    }

    @Test
    void DELETE를_명시하고_필수_설정을_갖춘_경우만_삭제_모드가_된다() {
        runner.withPropertyValues("hashi.media.cleanup.enabled=true", "hashi.media.cleanup.mode=DELETE",
                        "hashi.media.cleanup.upload-safety-window=30m")
                .run(context -> assertThat(context.getBean(MediaCleanupProperties.class).canDelete()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"upload-safety-window=0s", "upload-safety-window=-1s", "retry-interval=0s",
            "scan-interval=-1s", "scan-batch-size=-1", "scan-batch-size=1001", "scan-max-batches=101",
            "storage-page-size=1001", "storage-max-pages=101", "storage-api-timeout=0s",
            "storage-attempt-timeout=20s", "mode=UNKNOWN"})
    void 잘못된_실행_설정은_시작할_수_없다(String property) {
        runner.withPropertyValues("hashi.media.cleanup." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 같은_bucket이나_다른_region이면_실행_준비를_거부한다() {
        ApplicationContextRunner enabled = runner.withPropertyValues("hashi.media.cleanup.enabled=true",
                "hashi.media.cleanup.upload-safety-window=1h");
        enabled.withPropertyValues("hashi.storage.bucket=test-same", "hashi.media.original-storage.bucket=test-same")
                .run(context -> assertThat(context).hasFailed());
        enabled.withPropertyValues("hashi.storage.region=ap-northeast-1",
                        "hashi.media.original-storage.region=ap-northeast-2")
                .run(context -> assertThat(context).hasFailed());
    }
}
