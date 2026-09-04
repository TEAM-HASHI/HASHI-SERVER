package org.sopt.hashi.magazine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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

class MagazineMediaBackfillLifecycleTest {

    private final MagazineMediaBackfillCandidateReader reader = mock(MagazineMediaBackfillCandidateReader.class);
    private final MagazineMediaBackfillCheckpointStore checkpoint = mock(MagazineMediaBackfillCheckpointStore.class);
    private final MagazineMediaBackfillAttachmentService attachment =
            mock(MagazineMediaBackfillAttachmentService.class);
    private final MediaBackfillPort port = mock(MediaBackfillPort.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MagazineMediaBackfillConfiguration.class, MagazineMediaBackfillRunner.class)
            .withBean(MagazineMediaBackfillCandidateReader.class, () -> reader)
            .withBean(MagazineMediaBackfillCheckpointStore.class, () -> checkpoint)
            .withBean(MagazineMediaBackfillAttachmentService.class, () -> attachment)
            .withBean(MediaBackfillPort.class, () -> port)
            .withPropertyValues(
                    "hashi.magazine.media-backfill.target=MAGAZINE_BANNER",
                    "hashi.magazine.media-backfill.mode=DRY_RUN",
                    "hashi.magazine.media-backfill.batch-size=50",
                    "hashi.magazine.media-backfill.max-batches=10",
                    "hashi.magazine.media-backfill.lease-duration=5m",
                    "hashi.magazine.media-backfill.max-attempts=3",
                    "hashi.magazine.media-backfill.retry-initial-delay=200ms");

    @Test
    void 기본_비활성_상태에서는_runner와_executor가_없고_시작_이벤트로_실행되지_않는다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MagazineMediaBackfillRunner.class);
            assertThat(context).doesNotHaveBean(MagazineMediaBackfillConfiguration.EXECUTOR);
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
        given(reader.findUpperBound(MagazineMediaBackfillTarget.MAGAZINE_BANNER)).willReturn(1L);
        given(reader.findBatch(MagazineMediaBackfillTarget.MAGAZINE_BANNER, 0L, 1L, 50))
                .willReturn(List.of(new MagazineMediaBackfillCandidate(
                        MagazineMediaBackfillTarget.MAGAZINE_BANNER, 1L, "magazines/lifecycle.jpg")));
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

        contextRunner.withPropertyValues("hashi.magazine.media-backfill.enabled=true")
                .withBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
                    DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
                    // 테스트에서는 graceful-stop 대기만 단축하며 실제 executor의 interrupt 설정은 유지한다.
                    processor.setTimeoutPerShutdownPhase(100L);
                    return processor;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(workerThread.get()).startsWith("magazine-media-backfill-");

                    context.getSourceApplicationContext().close();

                    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
                    verifyNoInteractions(checkpoint, attachment);
                });
    }
}
