package org.sopt.hashi.restaurant.internal.map;

import java.time.Instant;
import java.util.List;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics.Reason;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** UUID별 payload와 TTL. ledger는 용량 회수만 담당하며 정렬이나 페이지 위치를 보관하지 않는다. */
@Component
public class RedisMapSessionStore {
    private static final String PREFIX = "hashi:restaurant:map:{sessions-v2}:";
    private static final String LEDGER = PREFIX + "admission";
    private static final DefaultRedisScript<Long> CREATE = script("create");
    private static final DefaultRedisScript<Long> TOUCH = script("touch");
    private static final DefaultRedisScript<Long> ADMIT = script("admit");
    private final ObjectProvider<StringRedisTemplate> templates;
    private final MapSessionSerializer serializer;
    private final MapSessionLimits limits;
    private final MapCapacityMetrics metrics;

    public RedisMapSessionStore(ObjectProvider<StringRedisTemplate> templates, MapSessionSerializer serializer,
                               MapSessionLimits limits, MapCapacityMetrics metrics) {
        this.templates = templates;
        this.serializer = serializer;
        this.limits = limits;
        this.metrics = metrics;
    }

    /** DB 조회와 JSON 생성 전에 호출한다. 원문 IP 대신 서명키로 HMAC한 caller만 Redis에 남긴다. */
    public Instant admit(String caller, boolean newQuery) {
        limits.validate();
        long result = execute(ADMIT, List.of(PREFIX + "requests"),
                Long.toString(limits.getRedisMemoryCeiling()), Long.toString(limits.getRedisHeadroom()), caller,
                Integer.toString(limits.getCallersPerMinute()), Integer.toString(limits.getRequestsPerMinute()),
                Integer.toString(limits.getRequestsPerCaller()), Integer.toString(limits.getNewQueriesPerCaller()),
                newQuery ? "1" : "0");
        if (result == -3) {
            metrics.rejected(Reason.CALLER_RATE);
            throw new BusinessException(RestaurantErrorCode.MAP_RATE_LIMITED);
        }
        checkAdmission(result);
        return Instant.ofEpochMilli(result);
    }

    public MapSessionId save(MapQuerySession session) {
        limits.validate();
        String payload;
        try {
            payload = serializer.serialize(session);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == RestaurantErrorCode.MAP_CAPACITY_EXCEEDED) {
                metrics.rejected(Reason.SNAPSHOT_BYTES);
            }
            throw exception;
        }
        var id = new MapSessionId(session.id());
        long result = execute(CREATE, List.of(key(id), LEDGER),
                Long.toString(limits.getRedisMemoryCeiling()), Long.toString(limits.getRedisHeadroom()), payload,
                Long.toString(session.expiresAt().toEpochMilli()), Long.toString(limits.getIdleTimeout().toMillis()),
                Integer.toString(limits.getSnapshotBytes()), Long.toString(limits.getTotalBytes()),
                Integer.toString(limits.getSessions()), id.value(), Long.toString(limits.getMaxLifetime().toMillis()));
        checkCreation(result);
        return id;
    }

    public MapQuerySession find(MapSessionId id) {
        String payload;
        try {
            payload = templates.getObject().opsForValue().get(key(id));
        } catch (DataAccessException | BeansException exception) {
            throw unavailable();
        }
        MapQuerySession session = serializer.deserialize(payload);
        if (!session.id().equals(id.id())) {
            throw expired();
        }
        return session;
    }

    /** 읽기/커서 검증/카드 구성이 성공한 뒤에만 호출한다. GET 뒤 만료된 키를 다시 생성하지 않는다. */
    public Instant touch(MapSessionId id, MapQuerySession session) {
        limits.validate();
        long expiry = execute(TOUCH, List.of(key(id), LEDGER), id.value(),
                Long.toString(limits.getIdleTimeout().toMillis()), Long.toString(session.expiresAt().toEpochMilli()));
        if (expiry < 0) {
            throw expired();
        }
        return Instant.ofEpochMilli(expiry);
    }

    private long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        try {
            Long result = templates.getObject().execute(script, keys, (Object[]) args);
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (DataAccessException | BeansException exception) {
            throw unavailable();
        }
    }

    private void checkAdmission(long result) {
        if (result == -1) {
            reject(Reason.CALLER_CARDINALITY);
        }
        if (result == -4) {
            reject(Reason.GLOBAL_REQUESTS);
        }
        if (result == -5) {
            metrics.rejected(Reason.REDIS_MEMORY_GUARD);
            throw unavailable();
        }
        if (result < 0) {
            throw unavailable();
        }
    }

    private void checkCreation(long result) {
        if (result == -1) {
            reject(Reason.SNAPSHOT_BYTES);
        }
        if (result == -3) {
            reject(Reason.TOTAL_BYTES);
        }
        if (result == -4) {
            reject(Reason.SESSION_COUNT);
        }
        if (result == -5) {
            metrics.rejected(Reason.REDIS_MEMORY_GUARD);
            throw unavailable();
        }
        if (result < 0) {
            throw unavailable();
        }
    }

    private void reject(Reason reason) {
        metrics.rejected(reason);
        throw new BusinessException(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
    }

    private static String key(MapSessionId id) {
        return PREFIX + "query:" + id.value();
    }

    private static DefaultRedisScript<Long> script(String name) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("map/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static BusinessException unavailable() {
        return new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
    }

    private static BusinessException expired() {
        return new BusinessException(RestaurantErrorCode.MAP_SESSION_EXPIRED);
    }
}
