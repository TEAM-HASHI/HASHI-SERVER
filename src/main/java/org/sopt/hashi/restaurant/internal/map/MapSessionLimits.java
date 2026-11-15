package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 초기 운영값이다. 공개 전에 요청량과 Redis 메모리를 측정해 조정한다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hashi.restaurant.map.session.limits")
public class MapSessionLimits {
    private int concurrentRequests = 4;
    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration maxLifetime = Duration.ofMinutes(30);
    private int snapshotBytes = 1_048_576;
    private long totalBytes = 16_777_216;
    private int sessions = 1024;
    private int callersPerMinute = 2048;
    private int requestsPerCaller = 120;
    private int newQueriesPerCaller = 12;
    private int requestsPerMinute = 600;
    private long redisMemoryCeiling = 134_217_728;
    private long redisHeadroom = 33_554_432;

    public void validate() {
        if (concurrentRequests < 1 || concurrentRequests > 16 || idleTimeout == null || idleTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || idleTimeout.compareTo(Duration.ofMinutes(10)) > 0 || maxLifetime == null
                || maxLifetime.compareTo(idleTimeout) < 0 || maxLifetime.compareTo(Duration.ofMinutes(30)) > 0
                || snapshotBytes < 65_536 || snapshotBytes > MapQuerySession.MAX_BYTES
                || totalBytes < snapshotBytes || totalBytes > 67_108_864 || sessions < 1 || sessions > 4096
                || callersPerMinute < 1 || callersPerMinute > 4096 || requestsPerCaller < 1
                || requestsPerCaller > 600 || newQueriesPerCaller < 1 || newQueriesPerCaller > requestsPerCaller
                || requestsPerMinute < 1 || requestsPerMinute > 6000
                || redisHeadroom < totalBytes || redisMemoryCeiling < redisHeadroom + totalBytes) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        }
    }

    /** DB 결과를 무제한 적재하지 않는 보수적 32-byte 계획 단위다. 최종 tuple JSON은 실제 byte로 다시 검사한다. */
    public int candidateCapacity() {
        validate();
        return snapshotBytes / 32;
    }
}
