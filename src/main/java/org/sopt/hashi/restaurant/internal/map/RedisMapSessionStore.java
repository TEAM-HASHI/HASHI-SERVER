package org.sopt.hashi.restaurant.internal.map;

import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 슬롯 자체가 admission 상태다. 별도 index/counter 유실로 한도를 우회할 수 없다. */
@Component
public class RedisMapSessionStore {
    private static final String PREFIX = "hashi:restaurant:map:{sessions-v1}:slot:";
    private static final List<String> SLOT_KEYS = IntStream.range(0, MapQuerySession.MAX_SESSIONS)
            .mapToObj(slot -> PREFIX + slot).toList();
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            if #KEYS ~= 128 or #ARGV[1] > 65536 then return -1 end
            local deadline = tonumber(ARGV[2])
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            if not deadline or deadline <= now or deadline > now + 905000 then return -2 end
            for index, key in ipairs(KEYS) do
                if redis.call('EXISTS', key) == 0 then
                    redis.call('SET', key, ARGV[1], 'NX', 'PXAT', ARGV[2])
                    return index
                end
            end
            return -1
            """, Long.class);

    private final ObjectProvider<StringRedisTemplate> templates;
    private final MapSessionSerializer serializer;
    private final Clock clock;

    public RedisMapSessionStore(ObjectProvider<StringRedisTemplate> templates, MapSessionSerializer serializer,
                               @Qualifier("japanClock") Clock clock) {
        this.templates = templates;
        this.serializer = serializer;
        this.clock = clock;
    }

    /** 모든 키를 KEYS로 전달하고 한 hash slot을 사용한다. 쓰기는 마지막 SET 한 번뿐이다. */
    public MapSessionId save(MapQuerySession session) {
        String payload = serializer.serialize(session);
        Long result;
        try {
            result = templates.getObject().execute(CREATE, SLOT_KEYS, payload,
                    Long.toString(session.expiresAt().toEpochMilli()));
        } catch (DataAccessException | BeansException exception) {
            throw unavailable();
        }
        if (result != null && result == -1) {
            throw new BusinessException(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        }
        if (result == null || result < 1 || result > SLOT_KEYS.size()) {
            throw unavailable();
        }
        return new MapSessionId(result.intValue() - 1, session.id());
    }

    public MapQuerySession find(MapSessionId id) {
        String payload;
        try {
            payload = templates.getObject().opsForValue().get(SLOT_KEYS.get(id.slot()));
        } catch (DataAccessException | BeansException exception) {
            throw unavailable();
        }
        MapQuerySession session = serializer.deserialize(payload);
        if (!session.id().equals(id.id()) || !clock.instant().isBefore(session.expiresAt())) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_EXPIRED);
        }
        return session;
    }

    private static BusinessException unavailable() {
        // Redis exception은 command/payload를 포함할 수 있어 로그나 응답의 cause로 전달하지 않는다.
        return new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
    }
}
