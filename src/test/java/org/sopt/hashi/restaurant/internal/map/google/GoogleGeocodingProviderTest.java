package org.sopt.hashi.restaurant.internal.map.google;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

@ExtendWith(OutputCaptureExtension.class)
class GoogleGeocodingProviderTest {

    private final AtomicInteger attempts = new AtomicInteger();

    @ParameterizedTest
    @CsvSource({"400,INVALID_REQUEST", "401,ACCESS_DENIED", "403,ACCESS_DENIED", "404,CONFIGURATION_ERROR",
            "408,TIMEOUT", "429,QUOTA_EXCEEDED", "500,TRANSIENT_ERROR", "502,TRANSIENT_ERROR",
            "503,TRANSIENT_ERROR", "504,TRANSIENT_ERROR", "204,INVALID_RESPONSE", "201,INVALID_RESPONSE",
            "302,REDIRECT_REJECTED", "418,INVALID_RESPONSE"})
    void HTTP_오류는_원문을_읽거나_재시도하지_않고_분류한다(int status, FailureKind expected) {
        MockClientHttpResponse response = new MockClientHttpResponse(new ByteArrayInputStream(new byte[0]) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                throw new AssertionError("Error body must not be read");
            }
        }, HttpStatusCode.valueOf(status));
        assertThat(provider(response).geocode(GeocodingFixtures.ADDRESS)).isEqualTo(new Failure(expected, status));
        assertThat(attempts).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"RESOURCE_EXHAUSTED,QUOTA_EXCEEDED", "OVER_QUERY_LIMIT,ACCESS_DENIED",
            "rateLimitExceeded,ACCESS_DENIED", "userRateLimitExceeded,ACCESS_DENIED",
            "PERMISSION_DENIED,ACCESS_DENIED", "UNKNOWN,ACCESS_DENIED",
            "OVER_DAILY_LIMIT,ACCESS_DENIED", "dailyLimitExceeded,ACCESS_DENIED"})
    void HTTP_403의_제한된_상태값으로_quota와_권한오류를_구분하고_원문은_버린다(
            String rpcStatus, FailureKind expected, CapturedOutput output) {
        String body = "{\"error\":{\"code\":403,\"message\":\"" + GeocodingFixtures.API_KEY
                + "\",\"status\":\"" + rpcStatus + "\",\"details\":[{\"private\":\""
                + GeocodingFixtures.ADDRESS + "\"}]}}";
        assertThat(provider(json(body, 403)).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(expected, 403));
        assertThat(output.getAll()).doesNotContain(GeocodingFixtures.API_KEY, GeocodingFixtures.ADDRESS);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void HTTP_403의_잘못된_JSON이나_초과한_오류본문은_권한오류로_안전하게_되돌린다() {
        assertThat(provider(json("{\"error\":", 403)).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.ACCESS_DENIED, 403));
        assertThat(provider(json("{\"error\":{\"message\":\"" + "x".repeat(8192)
                + "\",\"status\":\"RESOURCE_EXHAUSTED\"}}", 403)).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.ACCESS_DENIED, 403));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void 빈_입력은_HTTP_이전에_거부한다(String address) {
        assertThat(provider(json("{}", 200)).geocode(address))
                .isEqualTo(new Failure(FailureKind.INVALID_REQUEST, null));
        assertThat(attempts).hasValue(0);
    }

    @Test
    void 기존_주소_길이_한도를_넘는_입력은_HTTP_이전에_거부한다() {
        assertThat(provider(json("{}", 200)).geocode("a".repeat(256)))
                .isEqualTo(new Failure(FailureKind.INVALID_REQUEST, null));
        assertThat(attempts).hasValue(0);
    }

    @Test
    void 연결_예외의_URL_키_주소와_cause를_결과나_로그에_노출하지_않는다(CapturedOutput output) {
        String privateDetails = GeocodingFixtures.API_KEY + GeocodingFixtures.ADDRESS + "https://private.invalid/full";
        GoogleGeocodingProvider provider = new GoogleGeocodingProvider(properties(), ignored -> (uri, method) -> {
            attempts.incrementAndGet();
            throw new IOException(privateDetails, new IllegalArgumentException(privateDetails));
        });
        assertThat(provider.geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.CONNECTION_ERROR, null));
        assertThat(attempts).hasValue(1);
        assertThat(output.getAll())
                .doesNotContain(privateDetails, GeocodingFixtures.API_KEY, GeocodingFixtures.ADDRESS);
    }

    @Test
    void timeout_예외도_원인을_보존하지_않고_분류한다() {
        GoogleGeocodingProvider provider = new GoogleGeocodingProvider(properties(), ignored -> (uri, method) -> {
            attempts.incrementAndGet();
            throw new SocketTimeoutException(GeocodingFixtures.API_KEY);
        });
        assertThat(provider.geocode(GeocodingFixtures.ADDRESS)).isEqualTo(new Failure(FailureKind.TIMEOUT, null));
        assertThat(attempts).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"false,execute,java.lang.IllegalStateException", "true,await,java.lang.AssertionError"})
    void 예상하지_못한_실패는_분류를_유지하며_WARN에_단계와_클래스명만_남긴다(
            boolean isError, String phase, String exceptionType, CapturedOutput output) {
        String privateUrl = "https://private.invalid/geocode?address=" + GeocodingFixtures.ADDRESS;
        String privateMessage = "synthetic-private-message " + GeocodingFixtures.API_KEY + privateUrl;
        String privateCause = "synthetic-private-cause " + GeocodingFixtures.API_KEY;
        GoogleGeocodingProvider provider = new GoogleGeocodingProvider(properties(), ignored -> (uri, method) -> {
            attempts.incrementAndGet();
            IllegalArgumentException cause = new IllegalArgumentException(privateCause);
            if (isError) {
                throw new AssertionError(privateMessage, cause);
            }
            throw new IllegalStateException(privateMessage, cause);
        });
        Logger logger = (Logger) LoggerFactory.getLogger(GoogleGeocodingProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Failure expected = new Failure(FailureKind.INVALID_RESPONSE, null);
            assertThat(provider.geocode(GeocodingFixtures.ADDRESS)).isEqualTo(expected);
            assertThat(attempts).hasValue(1);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Google geocoding failure phase=" + phase + " exceptionType=" + exceptionType);
                assertThat(event.getArgumentArray()).containsExactly(exceptionType);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(output.getAll()).doesNotContain(privateUrl, privateMessage, privateCause,
                    GeocodingFixtures.API_KEY, GeocodingFixtures.ADDRESS,
                    "java.lang.IllegalArgumentException", "Caused by:");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 비_JSON과_압축된_응답을_거부한다() {
        MockClientHttpResponse html = json("<html>private response</html>", 200);
        html.getHeaders().setContentType(MediaType.TEXT_HTML);
        assertThat(provider(html).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.INVALID_RESPONSE, 200));
        MockClientHttpResponse compressed = json("{}", 200);
        compressed.getHeaders().set("Content-Encoding", "gzip");
        assertThat(provider(compressed).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.INVALID_RESPONSE, 200));
    }

    @Test
    void 성공_실패_원문을_로그에_노출하지_않는다(CapturedOutput output) {
        provider(json(GeocodingFixtures.SUCCESS, 200)).geocode(GeocodingFixtures.ADDRESS);
        provider(json("{\"private\":\"" + GeocodingFixtures.API_KEY, 200)).geocode(GeocodingFixtures.ADDRESS);
        assertThat(output.getAll()).doesNotContain(GeocodingFixtures.API_KEY, GeocodingFixtures.ADDRESS,
                "35.12345678901234567", "架空住所", "must-not-be-retained");
    }

    @Test
    void 주소로_endpoint를_바꾸거나_키를_query에_넣을_수_없다() {
        String hostile = "https://attacker.invalid/x?key=bad#fragment&regionCode=US";
        GoogleGeocodingProvider provider = new GoogleGeocodingProvider(properties(), ignored -> (uri, method) -> {
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("geocode.googleapis.com");
            assertThat(uri.getPath()).isEqualTo("/v4/geocode/address");
            assertThat(uri.getRawFragment()).isNull();
            assertThat(uri.getRawQuery()).doesNotContain("key=", GeocodingFixtures.API_KEY);
            assertThat(uri.getRawQuery().split("&")).hasSize(4);
            return request(uri, json("{}", 200));
        });
        provider.geocode(hostile);
        assertThat(attempts).hasValue(1);
    }

    private GoogleGeocodingProvider provider(MockClientHttpResponse response) {
        return new GoogleGeocodingProvider(properties(), ignored -> (uri, method) -> request(uri, response));
    }

    private MockClientHttpRequest request(URI uri, MockClientHttpResponse response) {
        attempts.incrementAndGet();
        MockClientHttpRequest request = new MockClientHttpRequest();
        request.setURI(uri);
        request.setResponse(response);
        return request;
    }

    private MockClientHttpResponse json(String body, int status) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8),
                HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    private GoogleGeocodingProperties properties() {
        return new GoogleGeocodingProperties(true, GeocodingFixtures.API_KEY, null, null, null);
    }
}
