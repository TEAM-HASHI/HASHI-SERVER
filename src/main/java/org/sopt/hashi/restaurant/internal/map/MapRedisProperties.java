package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 인증 Redis 설정과 별개다. host를 생략해도 기존 Redis로 연결하지 않는다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hashi.restaurant.map.redis")
public class MapRedisProperties {
    private String host;
    private int port = 6379;
    private boolean ssl;
    private String username;
    private String password;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration timeout = Duration.ofSeconds(3);

    void validate() {
        if (host == null || host.isBlank() || port < 1 || port > 65535
                || !validTimeout(connectTimeout) || !validTimeout(timeout)) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        }
    }

    private boolean validTimeout(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0
                && duration.compareTo(Duration.ofSeconds(30)) <= 0;
    }
}
