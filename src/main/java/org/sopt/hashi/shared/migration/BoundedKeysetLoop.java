package org.sopt.hashi.shared.migration;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/** 고정된 ID 상한 안에서 제한된 batch만 순회한다. 영속 상태와 트랜잭션은 호출자가 관리한다. */
public final class BoundedKeysetLoop {

    private BoundedKeysetLoop() {
    }

    public enum Result {
        EXHAUSTED, BATCH_LIMIT
    }

    @FunctionalInterface
    public interface BatchReader<T> {
        List<T> read(long cursor, long upperBound, int limit);
    }

    public static <T> Result run(
            long startCursor,
            long upperBound,
            int batchSize,
            int maxBatches,
            BatchReader<T> reader,
            ToLongFunction<T> id,
            Consumer<T> processor
    ) {
        if (startCursor < 0 || upperBound < startCursor || batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException("invalid bounded keyset range or batch limit");
        }
        Objects.requireNonNull(reader, "reader is required");
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(processor, "processor is required");
        long cursor = startCursor;
        for (int batchNumber = 0; batchNumber < maxBatches; batchNumber++) {
            requireNotInterrupted();
            List<T> candidates = reader.read(cursor, upperBound, batchSize);
            if (candidates.isEmpty()) {
                return Result.EXHAUSTED;
            }
            for (T candidate : candidates) {
                requireNotInterrupted();
                long nextCursor = id.applyAsLong(candidate);
                if (nextCursor <= cursor || nextCursor > upperBound) {
                    throw new IllegalArgumentException("candidate is outside the keyset range");
                }
                processor.accept(candidate);
                // 처리와 호출자 측 영속 cursor 기록이 성공한 뒤에만 메모리 cursor를 전진한다.
                cursor = nextCursor;
            }
            if (candidates.size() < batchSize) {
                return Result.EXHAUSTED;
            }
        }
        requireNotInterrupted();
        return reader.read(cursor, upperBound, 1).isEmpty() ? Result.EXHAUSTED : Result.BATCH_LIMIT;
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("bounded keyset execution was interrupted");
        }
    }
}
