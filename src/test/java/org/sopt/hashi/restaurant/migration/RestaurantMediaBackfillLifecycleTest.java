package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaBackfillPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.DefaultLifecycleProcessor;

class RestaurantMediaBackfillLifecycleTest {

    private final RestaurantMediaBackfillCandidateReader reader = mock(RestaurantMediaBackfillCandidateReader.class);
    private final RestaurantMediaBackfillCheckpointStore checkpoint = mock(RestaurantMediaBackfillCheckpointStore.class);
    private final RestaurantMediaBackfillAttachmentService attachment = mock(RestaurantMediaBackfillAttachmentService.class);
    private final MediaBackfillPort port = mock(MediaBackfillPort.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RestaurantMediaBackfillConfiguration.class, RestaurantMediaBackfillRunner.class)
            .withBean(RestaurantMediaBackfillCandidateReader.class, () -> reader)
            .withBean(RestaurantMediaBackfillCheckpointStore.class, () -> checkpoint)
            .withBean(RestaurantMediaBackfillAttachmentService.class, () -> attachment)
            .withBean(MediaBackfillPort.class, () -> port)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues(
                    "hashi.restaurant.media-backfill.mode=DRY_RUN",
                    "hashi.restaurant.media-backfill.target=RESTAURANT_IMAGE",
                    "hashi.restaurant.media-backfill.batch-size=50",
                    "hashi.restaurant.media-backfill.max-batches=10",
                    "hashi.restaurant.media-backfill.lease-duration=5m",
                    "hashi.restaurant.media-backfill.max-attempts=3",
                    "hashi.restaurant.media-backfill.retry-initial-delay=200ms");

    @Test
    void 기본_비활성_상태에서는_runner와_executor가_없고_시작_이벤트로_실행되지_않는다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RestaurantMediaBackfillRunner.class);
            assertThat(context).doesNotHaveBean(RestaurantMediaBackfillConfiguration.EXECUTOR);
            context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO));
            verifyNoInteractions(reader, checkpoint, attachment, port);
        });
    }

    @Test
    void 시작_이벤트는_전용_executor를_사용하고_context_종료는_진행_중인_작업을_중단한다() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        given(reader.findUpperBound(RestaurantMediaBackfillTarget.RESTAURANT_IMAGE)).willReturn(1L);
        given(reader.findBatch(RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 0L, 1L, 50))
                .willReturn(List.of(new RestaurantMediaBackfillCandidate(
                        RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 1L, 100L, "restaurants/lifecycle.jpg")));
        given(port.inspect(any())).willAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                if (!new CountDownLatch(1).await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("worker was not interrupted before the test bound");
                }
                throw new AssertionError("unreachable latch completion");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test worker was interrupted");
            }
        });

        contextRunner.withPropertyValues("hashi.restaurant.media-backfill.enabled=true")
                .withBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
                    DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
                    // 테스트에서는 graceful-stop 대기만 단축하며 실제 executor의 interrupt 설정은 유지한다.
                    processor.setTimeoutPerShutdownPhase(100L);
                    return processor;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO));
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(workerThread.get()).startsWith("restaurant-media-backfill-");

                    context.getSourceApplicationContext().close();

                    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
                    verifyNoInteractions(checkpoint, attachment);
                });
    }
}
