package org.sopt.hashi.restaurant.internal.map;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 조회가 없어도 메모리 증가를 관측한다. 실패 시 이전 값으로 정상처럼 보이지 않도록 probe 상태를 내린다. */
@Component
public class MapRedisMetrics {
    private static final DefaultRedisScript<List> PROBE = new DefaultRedisScript<>("""
            local memory = redis.call('INFO', 'memory')
            local stats = redis.call('INFO', 'stats')
            return {string.match(memory, 'used_memory:(%d+)'), string.match(memory, 'maxmemory:(%d+)'),
                string.match(stats, 'evicted_keys:(%d+)')}
            """, List.class);
    private final MapRedisConnection redis;
    private final MapSessionProperties sessions;
    private final MapSessionLimits limits;
    private volatile double usedBytes = Double.NaN;
    private volatile double admissionLimitBytes = Double.NaN;
    private volatile double warningLimitBytes = Double.NaN;
    private volatile double evictions = Double.NaN;
    private volatile double probeSuccess;

    public MapRedisMetrics(MapRedisConnection redis, MapSessionProperties sessions,
                           MapSessionLimits limits, MeterRegistry registry) {
        this.redis = redis;
        this.sessions = sessions;
        this.limits = limits;
        Gauge.builder("hashi.restaurant.map.redis.enabled", sessions,
                value -> value.isEnabled() ? 1 : 0).register(registry);
        Gauge.builder("hashi.restaurant.map.redis.used.bytes", this, value -> value.usedBytes).register(registry);
        Gauge.builder("hashi.restaurant.map.redis.admission.limit.bytes", this,
                value -> value.admissionLimitBytes).register(registry);
        Gauge.builder("hashi.restaurant.map.redis.warning.limit.bytes", this,
                value -> value.warningLimitBytes).register(registry);
        Gauge.builder("hashi.restaurant.map.redis.evicted.keys", this, value -> value.evictions).register(registry);
        Gauge.builder("hashi.restaurant.map.redis.probe.success", this, value -> value.probeSuccess).register(registry);
    }

    /** 명령 하나로 집계만 읽으며 서버 설정·사용자 키·좌표는 조회하지 않는다. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void refresh() {
        if (!sessions.isEnabled()) {
            invalidate();
            return;
        }
        try {
            limits.validate();
            List<?> values = redis.template().execute(PROBE, List.of());
            if (values == null || values.size() != 3) {
                throw new IllegalStateException("Map Redis metrics are unavailable");
            }
            long used = Long.parseLong(values.get(0).toString());
            long maximum = Long.parseLong(values.get(1).toString());
            long evicted = Long.parseLong(values.get(2).toString());
            long ceiling = maximum > 0 ? Math.min(maximum, limits.getRedisMemoryCeiling())
                    : limits.getRedisMemoryCeiling();
            if (used < 0 || maximum < 0 || evicted < 0 || ceiling <= limits.getRedisHeadroom()) {
                throw new IllegalStateException("Invalid map Redis metrics");
            }
            usedBytes = used;
            admissionLimitBytes = ceiling - limits.getRedisHeadroom();
            warningLimitBytes = admissionLimitBytes * 0.75;
            evictions = evicted;
            probeSuccess = 1;
        } catch (RuntimeException exception) {
            // 드라이버 오류는 접속 정보를 포함할 수 있어 원문을 기록하지 않는다.
            invalidate();
        }
    }

    private void invalidate() {
        probeSuccess = 0;
        usedBytes = Double.NaN;
        admissionLimitBytes = Double.NaN;
        warningLimitBytes = Double.NaN;
        evictions = Double.NaN;
    }
}
