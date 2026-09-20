package org.sopt.hashi.media.internal.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaQueuePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MediaQueuePropertiesConfig.class);

    @Test
    void 기본값은_queue를_비활성화하고_URL을_요구하지_않는다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MediaQueueProperties.class)).satisfies(properties -> {
                assertThat(properties.enabled()).isFalse();
                assertThat(properties.requestQueueUrl()).isNull();
                assertThat(properties.resultQueueUrl()).isNull();
                assertThat(properties.publisherCorePoolSize()).isEqualTo(2);
                assertThat(properties.publisherMaxPoolSize()).isEqualTo(4);
                assertThat(properties.publisherQueueCapacity()).isEqualTo(100);
                assertThat(properties.publisherShutdownAwait()).hasSeconds(20);
            });
        });
    }

    @Test
    void queue를_활성화하면_두_URL이_모두_필수다() {
        contextRunner
                .withPropertyValues(
                        "hashi.media.queue.enabled=true",
                        "hashi.media.queue.request-queue-url=https://sqs.ap-northeast-2.amazonaws.com/1/request"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void queue_URL의_앞뒤_공백을_제거한다() {
        contextRunner
                .withPropertyValues(
                        "hashi.media.queue.enabled=true",
                        "hashi.media.queue.request-queue-url= https://sqs.example.com/request ",
                        "hashi.media.queue.result-queue-url= https://sqs.example.com/result "
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MediaQueueProperties.class)).satisfies(properties -> {
                        assertThat(properties.requestQueueUrl()).isEqualTo("https://sqs.example.com/request");
                        assertThat(properties.resultQueueUrl()).isEqualTo("https://sqs.example.com/result");
                    });
                });
    }

    @Test
    void publisher_pool_설정이_잘못되면_기동을_거부한다() {
        contextRunner
                .withPropertyValues(
                        "hashi.media.queue.publisher-core-pool-size=4",
                        "hashi.media.queue.publisher-max-pool-size=2"
                )
                .run(context -> assertThat(context).hasFailed());
    }
}
