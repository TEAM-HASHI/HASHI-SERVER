package org.sopt.hashi.shared.migration;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 호출자가 재시도 가능한 오류를 결정하며, 이 도구는 횟수와 지수 대기만 관리한다. */
public final class BoundedRetry {

    private BoundedRetry() {
    }

    public static <T> T execute(
            int maxAttempts,
            Duration initialDelay,
            Supplier<T> operation,
            Predicate<RuntimeException> retryable
    ) {
        return execute(maxAttempts, initialDelay, operation, retryable, Thread::sleep);
    }

    static <T> T execute(
            int maxAttempts,
            Duration initialDelay,
            Supplier<T> operation,
            Predicate<RuntimeException> retryable,
            Sleeper sleeper
    ) {
        Objects.requireNonNull(initialDelay, "initialDelay is required");
        Objects.requireNonNull(operation, "operation is required");
        Objects.requireNonNull(retryable, "retryable is required");
        Objects.requireNonNull(sleeper, "sleeper is required");
        if (maxAttempts < 1 || initialDelay.isNegative()) {
            throw new IllegalArgumentException("attempts must be positive and delay must not be negative");
        }
        Duration delay = initialDelay;
        for (int attempt = 1; ; attempt++) {
            requireNotInterrupted();
            try {
                return operation.get();
            } catch (RuntimeException failure) {
                if (attempt >= maxAttempts || !retryable.test(failure)) {
                    throw failure;
                }
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("bounded retry was interrupted", interrupted);
                }
                if (attempt < maxAttempts - 1) {
                    delay = delay.multipliedBy(2);
                }
            }
        }
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("bounded retry was interrupted");
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
