package org.sopt.hashi.restaurant.internal.map.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class GoogleGeocodingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GoogleGeocodingConfig.class);

    @Test
    void 기본_비활성에서는_키없이_부팅하고_호출을_거부한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GeocodingProvider.class);
            assertThat(context.getBean(GeocodingProvider.class).geocode(GeocodingFixtures.ADDRESS))
                    .isEqualTo(new Failure(FailureKind.DISABLED, null));
        });
    }

    @Test
    void 활성_설정은_실제_HTTP_호출없이_adapter를_생성한다() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GeocodingProvider.class)).isInstanceOf(GoogleGeocodingProvider.class);
        });
    }

    @Test
    void 활성인데_키가_없으면_부팅_중에_실패한다() {
        runner.withPropertyValues("hashi.map.google-geocoding.enabled=true").run(context ->
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Google geocoding requires a valid server API key"));
    }

    @Test
    void 잘못된_키를_설정_문자열과_부팅_예외_원인과_로그에_노출하지_않는다(CapturedOutput output) {
        String secret = "synthetic private key!";
        enabled().withPropertyValues("hashi.map.google-geocoding.api-key=" + secret).run(context -> {
            assertThat(context).hasFailed();
            StringWriter trace = new StringWriter();
            context.getStartupFailure().printStackTrace(new PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain(secret);
        });
        assertThat(new GoogleGeocodingProperties(true, secret, null, null, null).toString()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout=0ms", "connect-timeout=-1ms", "connect-timeout=11s",
            "response-timeout=0ms", "response-timeout=31s", "response-timeout=1s",
            "max-response-bytes=0", "max-response-bytes=1023", "max-response-bytes=1048577"})
    void 활성_설정의_무제한_또는_과도한_제한값을_거부한다(String property) {
        enabled().withPropertyValues("hashi.map.google-geocoding." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 양의_밀리초_최솟값과_허용_최댓값을_검증한다() {
        new GoogleGeocodingProperties(true, GeocodingFixtures.API_KEY, Duration.ofMillis(1),
                Duration.ofMillis(1), 1024).validateEnabled();
        new GoogleGeocodingProperties(true, GeocodingFixtures.API_KEY, Duration.ofSeconds(10),
                Duration.ofSeconds(30), 1_048_576).validateEnabled();
        assertThatThrownBy(() -> new GoogleGeocodingProperties(true, GeocodingFixtures.API_KEY,
                Duration.ofNanos(1), Duration.ofSeconds(1), 1024).validateEnabled())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void HTTP_wire_logging_활성_환경에서는_외부_호출을_허용하지_않는다() {
        Logger logger = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.wire");
        Level previous = logger.getLevel();
        try {
            logger.setLevel(Level.DEBUG);
            enabled().run(context -> assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("Google geocoding requires HTTP wire logging to be disabled"));
        } finally {
            logger.setLevel(previous);
        }
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("hashi.map.google-geocoding.enabled=true",
                "hashi.map.google-geocoding.api-key=" + GeocodingFixtures.API_KEY);
    }
}
