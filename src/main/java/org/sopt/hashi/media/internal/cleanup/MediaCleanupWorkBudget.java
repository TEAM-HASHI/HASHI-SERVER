package org.sopt.hashi.media.internal.cleanup;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 실행 중인 I/O를 취소하지 않고, 단조 시계로 다음 작업을 시작할 수 있는지만 판단한다. */
public final class MediaCleanupWorkBudget {

    private final LongSupplier nanoTime;
    private final long startedAt;
    private final long budgetNanos;

    public MediaCleanupWorkBudget(Duration duration, LongSupplier nanoTime) {
        Objects.requireNonNull(duration);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("cleanup work budget must be positive");
        }
        this.budgetNanos = duration.toNanos();
        this.startedAt = nanoTime.getAsLong();
    }

    public boolean hasTimeLeft() {
        long elapsed = nanoTime.getAsLong() - startedAt;
        return elapsed >= 0 && elapsed < budgetNanos;
    }

    public Duration elapsed() {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startedAt));
    }
}
