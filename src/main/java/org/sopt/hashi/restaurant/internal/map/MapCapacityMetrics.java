package org.sopt.hashi.restaurant.internal.map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 공개 오류는 유지하면서 고정된 내부 사유와 처리 단계만 기록한다. 요청값이나 식별자는 태그에 넣지 않는다. */
@Slf4j
@Component
public class MapCapacityMetrics {
    static final String REJECTION_METRIC = "hashi.restaurant.map.capacity.rejected";
    static final String STAGE_DURATION_METRIC = "hashi.restaurant.map.stage.duration";

    private final MeterRegistry registry;

    public MapCapacityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void rejected(Reason reason) {
        try {
            registry.counter(REJECTION_METRIC, "reason", reason.tag).increment();
        } catch (RuntimeException exception) {
            log.warn("Failed to record a map capacity metric");
        }
    }

    /** Timer.record가 성공과 예외 경로 모두 finally에서 시간을 기록하고 원래 결과나 예외를 그대로 전달한다. */
    public <T> T record(Operation operation, Stage stage, Supplier<T> action) {
        long startedAt = System.nanoTime();
        try {
            return action.get();
        } finally {
            try {
                Timer.builder(STAGE_DURATION_METRIC)
                        .description("Restaurant map request stage duration")
                        .tag("operation", operation.tag)
                        .tag("stage", stage.tag)
                        .register(registry)
                        .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            } catch (RuntimeException exception) {
                log.warn("Failed to record a map stage metric");
            }
        }
    }

    public enum Operation {
        NEW_QUERY("new_query"),
        SORT_CHANGE("sort_change"),
        NEXT_PAGE("next_page");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }
    }

    public enum Stage {
        ADMIT("admit"),
        CANDIDATES("candidates"),
        SAVE("save"),
        READ_PAGE("read_page"),
        TOUCH("touch");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }
    }

    public enum Reason {
        CONCURRENT_REQUESTS("concurrent_requests"),
        CANDIDATE_COUNT("candidate_count"),
        SNAPSHOT_BYTES("snapshot_bytes"),
        TOTAL_BYTES("total_bytes"),
        SESSION_COUNT("session_count"),
        CALLER_RATE("caller_rate"),
        CALLER_CARDINALITY("caller_cardinality"),
        GLOBAL_REQUESTS("global_requests"),
        REDIS_MEMORY_GUARD("redis_memory_guard");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }
    }
}
