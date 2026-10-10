package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class MapRedisPropertiesTest {
    @Test
    void 운영환경변수_이름으로_지도전용_연결설정을_바인딩한다() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "HASHI_RESTAURANT_MAP_REDIS_HOST", "map-redis.synthetic",
                                "HASHI_RESTAURANT_MAP_REDIS_PORT", "16379",
                                "HASHI_RESTAURANT_MAP_REDIS_SSL", "true",
                                "HASHI_RESTAURANT_MAP_REDIS_USERNAME", "synthetic-user",
                                "HASHI_RESTAURANT_MAP_REDIS_PASSWORD", "synthetic-password",
                                "HASHI_RESTAURANT_MAP_REDIS_CONNECTTIMEOUT", "500ms",
                                "HASHI_RESTAURANT_MAP_REDIS_TIMEOUT", "1s"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(MapRedisProperties.class);
                    assertThatCode(properties::requireConfiguration).doesNotThrowAnyException();
                    var settings = properties.requireConfiguration();
                    assertThat(properties.getHost()).isEqualTo("map-redis.synthetic");
                    assertThat(settings.port()).isEqualTo(16379);
                    assertThat(settings.ssl()).isTrue();
                    assertThat(settings.connectTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getUsername()).isEqualTo("synthetic-user");
                    assertThat(properties.getPassword()).isEqualTo("synthetic-password");
                    assertThat(properties.toString()).doesNotContain("synthetic-user", "synthetic-password");
                });
    }

    @ParameterizedTest
    @MethodSource("invalidConfiguration")
    void 잘못된_연결설정은_503이고_접속정보를_예외에_포함하지_않는다(Consumer<MapRedisProperties> mutate) {
        var properties = new MapRedisProperties();
        properties.setHost("map-redis.synthetic");
        properties.setPassword("synthetic-password");
        mutate.accept(properties);
        assertThatThrownBy(properties::requireConfiguration).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).doesNotContain("synthetic-password", "map-redis.synthetic");
        });
    }

    private static Stream<Consumer<MapRedisProperties>> invalidConfiguration() {
        return Stream.of(properties -> properties.setHost(null), properties -> properties.setHost(" "),
                properties -> properties.setPort("0"), properties -> properties.setPort("65536"),
                properties -> properties.setConnectTimeout(null), properties -> properties.setTimeout("0ms"),
                properties -> properties.setTimeout("31s"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MapRedisProperties.class)
    static class PropertiesConfiguration {
    }
}
