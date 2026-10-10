package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

/** 인증 Redis 설정과 별개다. host를 생략해도 기존 Redis로 연결하지 않는다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hashi.restaurant.map.redis")
public class MapRedisProperties {
    private String host;
    private String port = "6379";
    private String ssl = "false";
    private String username;
    private String password;
    private String connectTimeout = "2s";
    private String timeout = "3s";

    /** 먼저 문자열로 바인딩해 지도 설정 오타가 다른 API의 시작을 막지 않게 한다. */
    ConnectionSettings requireConfiguration() {
        try {
            int parsedPort = Integer.parseInt(port.strip());
            Duration parsedConnectTimeout = DurationStyle.detectAndParse(connectTimeout.strip());
            Duration parsedTimeout = DurationStyle.detectAndParse(timeout.strip());
            if (host == null || host.isBlank() || parsedPort < 1 || parsedPort > 65535
                    || !validTimeout(parsedConnectTimeout) || !validTimeout(parsedTimeout)
                    || !("true".equalsIgnoreCase(ssl) || "false".equalsIgnoreCase(ssl))) {
                throw new IllegalArgumentException();
            }
            return new ConnectionSettings(parsedPort, Boolean.parseBoolean(ssl), parsedConnectTimeout, parsedTimeout);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        }
    }

    private boolean validTimeout(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0
                && duration.compareTo(Duration.ofSeconds(30)) <= 0;
    }

    record ConnectionSettings(int port, boolean ssl, Duration connectTimeout, Duration timeout) {
    }
}
