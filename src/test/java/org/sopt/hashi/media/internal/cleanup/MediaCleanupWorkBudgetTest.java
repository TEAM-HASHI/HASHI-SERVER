package org.sopt.hashi.media.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MediaCleanupWorkBudgetTest {

    @Test
    void 경계에_도달하면_다음_작업을_시작하지_않는다() {
        AtomicLong clock = new AtomicLong(100);
        MediaCleanupWorkBudget budget = new MediaCleanupWorkBudget(Duration.ofNanos(10), clock::get);
        assertThat(budget.hasTimeLeft()).isTrue();
        clock.set(109);
        assertThat(budget.hasTimeLeft()).isTrue();
        clock.set(110);
        assertThat(budget.hasTimeLeft()).isFalse();
        assertThat(budget.elapsed()).isEqualTo(Duration.ofNanos(10));
    }

    @Test
    void nanoTime의_long_wrap에도_경과시간을_계산한다() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 5);
        MediaCleanupWorkBudget budget = new MediaCleanupWorkBudget(Duration.ofNanos(10), clock::get);
        clock.addAndGet(9);
        assertThat(budget.hasTimeLeft()).isTrue();
        clock.incrementAndGet();
        assertThat(budget.hasTimeLeft()).isFalse();
        assertThat(budget.elapsed()).isEqualTo(Duration.ofNanos(10));
    }

    @Test
    void 시계가_역행하면_추가_작업을_거부하고_음수_측정을_막는다() {
        AtomicLong clock = new AtomicLong(100);
        MediaCleanupWorkBudget budget = new MediaCleanupWorkBudget(Duration.ofSeconds(1), clock::get);
        clock.set(99);
        assertThat(budget.hasTimeLeft()).isFalse();
        assertThat(budget.elapsed()).isEqualTo(Duration.ZERO);
    }

    @Test
    void 양수가_아니거나_나노초로_표현할_수_없는_기간은_거부한다() {
        assertThatThrownBy(() -> new MediaCleanupWorkBudget(Duration.ZERO, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaCleanupWorkBudget(Duration.ofSeconds(-1), System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaCleanupWorkBudget(Duration.ofSeconds(Long.MAX_VALUE), System::nanoTime))
                .isInstanceOf(ArithmeticException.class);
    }
}
