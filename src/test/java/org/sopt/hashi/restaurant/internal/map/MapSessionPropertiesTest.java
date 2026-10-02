package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class MapSessionPropertiesTest {
    private static final String KEY = Base64.getEncoder().encodeToString(
            "synthetic-map-test-key-32-bytes-only".getBytes(StandardCharsets.UTF_8));
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean("unrelatedApiMarker", Object.class, Object::new);

    @Test
    void 활성화_설정을_생략하면_유효한_키가_있어도_기본_차단한다() {
        withLogs(events -> withEnvironment(null, KEY).run(context -> {
            assertThat(context).hasNotFailed().hasBean("unrelatedApiMarker");
            assertUnavailable(context.getBean(MapSessionProperties.class));
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).isEqualTo("Map query sessions are disabled.");
            });
        }));
    }

    @Test
    void 기존_SIGNINGKEY_환경변수와_명시적_enabled가_실제_설정빈에_바인딩된다() {
        withLogs(events -> withEnvironment("true", KEY).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(MapSessionProperties.class);
            assertThatCode(properties::requireConfigured).doesNotThrowAnyException();
            assertThat(properties.requireSigningKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(KEY));
            assertThat(properties.toString()).doesNotContain(KEY);
            assertThat(events.list).isEmpty();
        }));
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void 비활성화는_키가_없거나_무효여도_경고없이_부팅한다(String key) {
        withLogs(events -> withEnvironment("false", key).run(context -> {
            assertThat(context).hasNotFailed().hasBean("unrelatedApiMarker");
            assertUnavailable(context.getBean(MapSessionProperties.class));
            assertThat(events.list).noneMatch(event -> Level.WARN.equals(event.getLevel()));
            assertSafe(events, key);
        }));
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void 활성화됐지만_키가_없거나_무효이면_안전한_경고_한번과_503만_남긴다(String key) {
        withLogs(events -> withEnvironment("true", key).run(context -> {
            assertThat(context).hasNotFailed().hasBean("unrelatedApiMarker");
            var properties = context.getBean(MapSessionProperties.class);
            assertUnavailable(properties);
            assertUnavailable(properties);
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Map query sessions are enabled but the signing key is missing or invalid.");
                assertThat(event.getArgumentArray()).isNull();
            });
            assertSafe(events, key);
        }));
    }

    private ApplicationContextRunner withEnvironment(String enabled, String key) {
        Map<String, Object> values = new HashMap<>();
        if (enabled != null) values.put("HASHI_RESTAURANT_MAP_SESSION_ENABLED", enabled);
        if (key != null) values.put("HASHI_RESTAURANT_MAP_SESSION_SIGNINGKEY", key);
        // Boot는 실제 systemEnvironment 이름을 통해 환경변수 전용 relaxed binding을 선택한다.
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", values)));
    }

    private static Stream<String> invalidKeys() {
        return Stream.of(null, "", "synthetic-private-key!", "c2hvcnQ=", "x".repeat(129));
    }

    private static void assertUnavailable(MapSessionProperties properties) {
        assertThatThrownBy(properties::requireConfigured).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
            assertThat(failure.getCause()).isNull();
        });
    }

    private static void assertSafe(ListAppender<ILoggingEvent> events, String key) {
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getThrowableProxy()).isNull();
            if (key != null && !key.isEmpty()) assertThat(event.getFormattedMessage()).doesNotContain(key);
        });
    }

    private static void withLogs(Consumer<ListAppender<ILoggingEvent>> assertion) {
        Logger logger = (Logger) LoggerFactory.getLogger(MapSessionProperties.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertion.accept(appender);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MapSessionProperties.class)
    static class PropertiesConfiguration {
    }
}
