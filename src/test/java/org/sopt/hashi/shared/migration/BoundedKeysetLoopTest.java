package org.sopt.hashi.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BoundedKeysetLoopTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void 기존_cursor부터_순회하고_성공한_항목_다음에서_조회한다() {
        List<String> calls = new ArrayList<>();
        BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                5, 10, 2, 3,
                (cursor, upper, limit) -> {
                    calls.add("read:" + cursor + ":" + upper + ":" + limit);
                    return cursor == 5 ? List.of(6L, 8L) : List.of(10L);
                }, Long::longValue,
                id -> calls.add("process:" + id));

        assertThat(result).isEqualTo(BoundedKeysetLoop.Result.EXHAUSTED);
        assertThat(calls).containsExactly(
                "read:5:10:2", "process:6", "process:8", "read:8:10:2", "process:10");
    }

    @Test
    void 빈_batch는_항목_처리없이_완료한다() {
        List<Long> processed = new ArrayList<>();
        BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                0, 0, 2, 2, (cursor, upper, limit) -> List.<Long>of(),
                Long::longValue, processed::add);

        assertThat(result).isEqualTo(BoundedKeysetLoop.Result.EXHAUSTED);
        assertThat(processed).isEmpty();
    }

    @Test
    void 마지막_batch가_가득차도_한건_추가조회가_비었으면_완료한다() {
        List<String> reads = new ArrayList<>();
        List<Long> processed = new ArrayList<>();
        BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                0, 2, 2, 1,
                (cursor, upper, limit) -> {
                    reads.add(cursor + ":" + limit);
                    return cursor == 0 ? List.of(1L, 2L) : List.of();
                }, Long::longValue, processed::add);

        assertThat(result).isEqualTo(BoundedKeysetLoop.Result.EXHAUSTED);
        assertThat(reads).containsExactly("0:2", "2:1");
        assertThat(processed).containsExactly(1L, 2L);
    }

    @Test
    void 최대_batch_후_남은_항목은_처리하지_않고_한도도달로_반환한다() {
        List<Long> processed = new ArrayList<>();
        BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                0, 3, 2, 1,
                (cursor, upper, limit) -> cursor == 0 ? List.of(1L, 2L) : List.of(3L),
                Long::longValue, processed::add);

        assertThat(result).isEqualTo(BoundedKeysetLoop.Result.BATCH_LIMIT);
        assertThat(processed).containsExactly(1L, 2L);
    }

    @Test
    void 처리_예외는_그대로_전달하고_후속_항목이나_batch를_진행하지_않는다() {
        RuntimeException failure = new IllegalStateException("transaction failed");
        List<Long> processed = new ArrayList<>();
        List<Long> cursors = new ArrayList<>();

        assertThatThrownBy(() -> BoundedKeysetLoop.run(
                0, 3, 3, 2,
                (cursor, upper, limit) -> {
                    cursors.add(cursor);
                    return List.of(1L, 2L, 3L);
                }, Long::longValue, id -> {
                    processed.add(id);
                    if (id == 2) {
                        throw failure;
                    }
                })).isSameAs(failure);

        assertThat(processed).containsExactly(1L, 2L);
        assertThat(cursors).containsExactly(0L);
    }

    @Test
    void 조회_예외도_변환하지_않는다() {
        RuntimeException failure = new IllegalArgumentException("reader failed");
        assertThatThrownBy(() -> BoundedKeysetLoop.<Long>run(
                0, 3, 2, 1, (cursor, upper, limit) -> { throw failure; },
                Long::longValue, id -> { })).isSameAs(failure);
    }

    @Test
    void 시작전_interrupt는_조회없이_중단하고_플래그를_유지한다() {
        List<Long> cursors = new ArrayList<>();
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> BoundedKeysetLoop.run(
                0, 1, 1, 1, (cursor, upper, limit) -> {
                    cursors.add(cursor);
                    return List.of(1L);
                }, Long::longValue, id -> { })).isInstanceOf(IllegalStateException.class);

        assertThat(cursors).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 항목_처리중_interrupt는_다음_항목을_처리하지_않는다() {
        List<Long> processed = new ArrayList<>();
        assertThatThrownBy(() -> BoundedKeysetLoop.run(
                0, 2, 2, 1, (cursor, upper, limit) -> List.of(1L, 2L),
                Long::longValue, id -> {
                    processed.add(id);
                    Thread.currentThread().interrupt();
                })).isInstanceOf(IllegalStateException.class);

        assertThat(processed).containsExactly(1L);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 마지막_항목후_interrupt는_추가조회를_중단한다() {
        List<Long> cursors = new ArrayList<>();
        assertThatThrownBy(() -> BoundedKeysetLoop.run(
                0, 1, 1, 1, (cursor, upper, limit) -> {
                    cursors.add(cursor);
                    return List.of(1L);
                }, Long::longValue, id -> Thread.currentThread().interrupt()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cursors).containsExactly(0L);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 잘못된_범위와_횟수는_거부한다() {
        assertThatThrownBy(() -> runWithRange(-1, 2, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runWithRange(3, 2, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runWithRange(0, 2, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runWithRange(0, 2, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursor_이하나_상한밖_항목은_처리하지_않는다() {
        List<Long> processed = new ArrayList<>();
        for (long invalidId : List.of(1L, 4L)) {
            assertThatThrownBy(() -> BoundedKeysetLoop.run(
                    1, 3, 1, 1, (cursor, upper, limit) -> List.of(invalidId),
                    Long::longValue, processed::add)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(processed).isEmpty();
    }

    private void runWithRange(long cursor, long upper, int batchSize, int maxBatches) {
        BoundedKeysetLoop.run(cursor, upper, batchSize, maxBatches,
                (from, to, limit) -> List.<Long>of(), Long::longValue, id -> { });
    }
}
