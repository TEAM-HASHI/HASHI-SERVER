package org.sopt.hashi.restaurant.internal.map;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 공개 오류는 유지하면서 고정된 내부 사유만 집계한다. 요청값이나 식별자는 태그에 넣지 않는다. */
@Slf4j
@Component
public class MapCapacityMetrics {
    static final String REJECTION_METRIC = "hashi.restaurant.map.capacity.rejected";

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
