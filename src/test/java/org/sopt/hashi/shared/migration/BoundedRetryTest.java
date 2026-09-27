package org.sopt.hashi.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BoundedRetryTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void 최초_성공이면_기다리지_않는다() {
        List<Duration> delays = new ArrayList<>();
        String value = BoundedRetry.execute(3, Duration.ofMillis(10), () -> "ready",
                failure -> true, delays::add);

        assertThat(value).isEqualTo("ready");
        assertThat(delays).isEmpty();
    }

    @Test
    void 일시오류만_지수간격으로_기다린_뒤_재시도한다() {
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> delays = new ArrayList<>();
        String value = BoundedRetry.execute(4, Duration.ofMillis(10), () -> {
            if (attempts.incrementAndGet() < 4) {
                throw new IllegalStateException("transient");
            }
            return "ready";
        }, failure -> failure instanceof IllegalStateException, delays::add);

        assertThat(value).isEqualTo("ready");
        assertThat(attempts).hasValue(4);
        assertThat(delays).containsExactly(
                Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(40));
    }

    @Test
    void 마지막_실패는_기다리지_않고_원래_예외를_전달한다() {
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> delays = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("transient");

        assertThatThrownBy(() -> BoundedRetry.execute(3, Duration.ZERO, () -> {
            attempts.incrementAndGet();
            throw failure;
        }, error -> true, delays::add)).isSameAs(failure);

        assertThat(attempts).hasValue(3);
        assertThat(delays).containsExactly(Duration.ZERO, Duration.ZERO);
    }

    @Test
    void 영구오류는_한번만_호출하고_그대로_전달한다() {
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> delays = new ArrayList<>();
        RuntimeException failure = new IllegalArgumentException("permanent");

        assertThatThrownBy(() -> BoundedRetry.execute(3, Duration.ZERO, () -> {
            attempts.incrementAndGet();
            throw failure;
        }, error -> false, delays::add)).isSameAs(failure);

        assertThat(attempts).hasValue(1);
        assertThat(delays).isEmpty();
    }

    @Test
    void Error는_재시도하지_않는다() {
        List<Duration> delays = new ArrayList<>();
        AssertionError failure = new AssertionError("fatal");
        assertThatThrownBy(() -> BoundedRetry.execute(3, Duration.ZERO,
                () -> { throw failure; }, error -> true, delays::add)).isSameAs(failure);
        assertThat(delays).isEmpty();
    }

    @Test
    void 대기중_interrupt는_플래그를_복원하고_재시도하지_않는다() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> BoundedRetry.execute(3, Duration.ZERO, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("transient");
        }, error -> true, delay -> { throw new InterruptedException(); }))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);

        assertThat(attempts).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 시작전_interrupt는_작업을_호출하지_않는다() {
        AtomicInteger attempts = new AtomicInteger();
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> BoundedRetry.execute(3, Duration.ZERO,
                attempts::incrementAndGet, error -> true, delay -> { }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(attempts).hasValue(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 최대한번이면_재시도_대기없이_실패한다() {
        List<Duration> delays = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("transient");
        assertThatThrownBy(() -> BoundedRetry.execute(1, Duration.ofSeconds(1),
                () -> { throw failure; }, error -> true, delays::add)).isSameAs(failure);
        assertThat(delays).isEmpty();
    }

    @Test
    void 잘못된_횟수와_음수_대기를_거부한다() {
        assertThatThrownBy(() -> BoundedRetry.execute(0, Duration.ZERO, () -> "ready", error -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedRetry.execute(1, Duration.ofMillis(-1), () -> "ready", error -> true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
