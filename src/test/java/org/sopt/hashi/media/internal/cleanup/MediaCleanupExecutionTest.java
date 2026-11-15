package org.sopt.hashi.media.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.service.MediaCleanupScanResult;
import org.sopt.hashi.media.service.MediaCleanupScanService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class MediaCleanupExecutionTest {

    private final MediaCleanupScanService scanner = mock(MediaCleanupScanService.class);
    private final MediaCleanupMetrics metrics = mock(MediaCleanupMetrics.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaCleanupPropertiesConfig.class, MediaCleanupExecutionConfig.class,
                    MediaCleanupScheduler.class)
            .withBean(MediaCleanupScanService.class, () -> scanner)
            .withBean(MediaCleanupMetrics.class, () -> metrics);

    @Test
    void 기본_비활성_상태에서는_스케줄러와_전용_실행기가_없다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MediaCleanupScheduler.class)
                    .doesNotHaveBean(MediaCleanupExecutionConfig.EXECUTOR);
            verifyNoInteractions(scanner);
        });
    }

    @Test
    void 전용_스레드에서_실행하고_이미_실행중이면_추가_작업을_쌓지_않는다() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        when(scanner.scan()).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new MediaCleanupScanResult(MediaCleanupScanResult.Status.COMPLETED, 0, 0, 0);
        });
        enabled().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(TaskScheduler.class);
            MediaCleanupScheduler scheduler = context.getBean(MediaCleanupScheduler.class);
            ThreadPoolTaskExecutor executor = context.getBean(
                    MediaCleanupExecutionConfig.EXECUTOR, ThreadPoolTaskExecutor.class);
            try {
                scheduler.scheduleCleanup();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                scheduler.scheduleCleanup();
                verify(scanner).scan();
                verify(metrics).recordDispatchRejected();
                assertThat(threadName.get()).startsWith("media-cleanup-");
                assertThat(executor.getCorePoolSize()).isEqualTo(1);
                assertThat(executor.getMaxPoolSize()).isEqualTo(1);
                assertThat(executor.getQueueSize()).isZero();
                assertThat(executor.getQueueCapacity()).isZero();
            } finally {
                release.countDown();
            }
            await().atMost(Duration.ofSeconds(3)).until(() -> executor.getActiveCount() == 0);
        });
    }

    @Test
    void context_종료가_진행중인_작업을_중단하고_이후_제출을_거부한다() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(scanner.scan()).thenAnswer(invocation -> {
            entered.countDown();
            try {
                if (!new CountDownLatch(1).await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("cleanup executor was not interrupted");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
            return new MediaCleanupScanResult(MediaCleanupScanResult.Status.INTERRUPTED, 0, 0, 0);
        });
        enabled().withBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
            DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
            // 테스트의 graceful-stop 대기만 단축하며 실제 shutdown/interrupt 설정을 사용한다.
            processor.setTimeoutPerShutdownPhase(100L);
            return processor;
        }).run(context -> {
            MediaCleanupScheduler scheduler = context.getBean(MediaCleanupScheduler.class);
            scheduler.scheduleCleanup();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            context.getSourceApplicationContext().close();

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.scheduleCleanup();
            verify(metrics).recordDispatchRejected();
            verify(scanner).scan();
        });
    }

    @Test
    void 등록된_주기_스케줄이_실제로_전용_실행기를_호출한다() {
        CountDownLatch invoked = new CountDownLatch(1);
        when(scanner.scan()).thenAnswer(invocation -> {
            invoked.countDown();
            return new MediaCleanupScanResult(MediaCleanupScanResult.Status.COMPLETED, 0, 0, 0);
        });
        enabled().withUserConfiguration(Scheduling.class)
                .withPropertyValues("hashi.media.cleanup.scan-interval=100ms").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
                });
    }

    @Test
    void 기동_즉시가_아니라_설정된_첫_주기부터_시작한다() throws Exception {
        Scheduled schedule = MediaCleanupScheduler.class.getMethod("scheduleCleanup").getAnnotation(Scheduled.class);
        assertThat(schedule.initialDelayString()).isEqualTo("${hashi.media.cleanup.scan-interval:30m}");
        assertThat(schedule.fixedDelayString()).isEqualTo(schedule.initialDelayString());
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("hashi.media.cleanup.enabled=true",
                "hashi.media.cleanup.upload-safety-window=30m");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }
}
