package org.sopt.hashi.media.internal.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaBackfillStorageConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MediaBackfillConfig.class, MediaBackfillStorageConfig.class);

    @Test
    void 기본_비활성_환경에서는_S3_adapter나_storage_설정을_요구하지_않는다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MediaBackfillStorage.class);
            assertThat(context).hasSingleBean(MediaBackfillIdentityFactory.class);
        });
    }

    @Test
    void 명시적으로_활성화한_환경에서만_S3_adapter를_생성한다() {
        enabledContext().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MediaBackfillStorage.class);
            assertThat(context.getBean(MediaBackfillStorage.class)).isInstanceOf(S3MediaBackfillStorage.class);
        });
    }

    @Test
    void 원본과_delivery가_같은_bucket이면_기동을_거부한다() {
        enabledContext().withPropertyValues("hashi.media.original-storage.bucket=test-delivery")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("backfill requires separate delivery and original buckets"));
    }

    @Test
    void 단일_S3_client로_처리할_수_없는_다른_region은_기동을_거부한다() {
        enabledContext().withPropertyValues("hashi.media.original-storage.region=us-east-1")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "backfill requires delivery and original buckets in the same region"));
    }

    private ApplicationContextRunner enabledContext() {
        return contextRunner.withPropertyValues(
                "hashi.media.backfill.enabled=true",
                "hashi.storage.bucket=test-delivery",
                "hashi.storage.region=ap-northeast-2",
                "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
                "hashi.media.original-storage.bucket=test-originals",
                "hashi.media.original-storage.region=ap-northeast-2");
    }
}
