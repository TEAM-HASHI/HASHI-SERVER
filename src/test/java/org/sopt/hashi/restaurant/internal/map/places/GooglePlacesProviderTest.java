package org.sopt.hashi.restaurant.internal.map.places;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

@ExtendWith(OutputCaptureExtension.class)
class GooglePlacesProviderTest {

    private final AtomicInteger attempts = new AtomicInteger();

    @ParameterizedTest
    @CsvSource({"400,INVALID_REQUEST", "401,ACCESS_DENIED", "403,ACCESS_DENIED",
            "404,CONFIGURATION_ERROR", "408,TIMEOUT", "429,QUOTA_EXCEEDED",
            "500,TRANSIENT_ERROR", "503,TRANSIENT_ERROR", "302,REDIRECT_REJECTED",
            "204,INVALID_RESPONSE", "418,INVALID_RESPONSE"})
    void 검색_HTTP_오류는_원문을_읽거나_재시도하지_않고_분류한다(int status, FailureKind expected) {
        MockClientHttpResponse response = unreadable(status);
        assertThat(provider(response).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(expected, status));
        assertThat(attempts).hasValue(1);
    }

    @Test
    void 상세_404는_검색결과_없음이고_다른_오류는_실패다() {
        assertThat(provider(unreadable(404)).details(PlacesFixtures.PLACE_ID))
                .isEqualTo(new PlaceDetailsResult.NoResults());
        assertThat(provider(unreadable(429)).details(PlacesFixtures.PLACE_ID))
                .isEqualTo(new PlaceDetailsResult.Failure(FailureKind.QUOTA_EXCEEDED, 429));
    }

    @ParameterizedTest
    @CsvSource({"RESOURCE_EXHAUSTED,QUOTA_EXCEEDED", "PERMISSION_DENIED,ACCESS_DENIED",
            "UNKNOWN,ACCESS_DENIED"})
    void HTTP_403은_제한된_상태값으로_quota와_권한오류를_구분한다(
            String rpcStatus, FailureKind expected, CapturedOutput output) {
        String body = "{\"error\":{\"message\":\"" + PlacesFixtures.API_KEY
                + "\",\"status\":\"" + rpcStatus + "\"}}";
        assertThat(provider(json(body, 403)).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(expected, 403));
        assertThat(output.getAll()).doesNotContain(PlacesFixtures.API_KEY, PlacesFixtures.QUERY);
        assertThat(attempts).hasValue(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void 빈_검색어는_HTTP_호출전에_거부한다(String query) {
        assertThat(provider(json("{}", 200)).search(query))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.INVALID_REQUEST, null));
        assertThat(attempts).hasValue(0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../places:searchText", "id/next", "id?key=leak", "id#fragment", "한글"})
    void 안전하지_않은_placeId는_경로를_만들기전에_거부한다(String placeId) {
        assertThat(provider(json(PlacesFixtures.CANDIDATE, 200)).details(placeId))
                .isEqualTo(new PlaceDetailsResult.Failure(FailureKind.INVALID_REQUEST, null));
        assertThat(attempts).hasValue(0);
    }

    @Test
    void 너무_긴_검색어와_placeId는_HTTP_호출전에_거부한다() {
        GooglePlacesProvider provider = provider(json("{}", 200));
        assertThat(provider.search("a".repeat(PlacesProvider.MAX_SEARCH_QUERY_LENGTH + 1)))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.INVALID_REQUEST, null));
        assertThat(provider.details("a".repeat(256)))
                .isEqualTo(new PlaceDetailsResult.Failure(FailureKind.INVALID_REQUEST, null));
        assertThat(attempts).hasValue(0);
    }

    @Test
    void 관리자_현지명과_주소의_최대_길이를_합친_검색어도_전송한다() {
        String query = "가".repeat(100) + " " + "東".repeat(255);
        assertThat(provider(json("{}", 200)).search(query)).isInstanceOf(PlacesSearchResult.NoResults.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void 잘못된_JSON과_초과응답을_안전하게_분류한다() {
        assertThat(provider(json("{\"places\":", 200)).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.INVALID_RESPONSE, 200));
        assertThat(provider(json("{" + " ".repeat(2048) + "}", 200)).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.RESPONSE_TOO_LARGE, 200));
    }

    @Test
    void 비_JSON과_압축된_응답을_거부한다() {
        MockClientHttpResponse html = json("<html>private</html>", 200);
        html.getHeaders().setContentType(MediaType.TEXT_HTML);
        assertThat(provider(html).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.INVALID_RESPONSE, 200));

        MockClientHttpResponse compressed = json("{}", 200);
        compressed.getHeaders().set("Content-Encoding", "gzip");
        assertThat(provider(compressed).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.INVALID_RESPONSE, 200));
    }

    @Test
    void 연결과_timeout_예외는_원문없이_분류한다(CapturedOutput output) {
        String privateText = PlacesFixtures.API_KEY + PlacesFixtures.QUERY + "https://private.invalid/full";
        GooglePlacesProvider connection = new GooglePlacesProvider(properties(), ignored -> (uri, method) -> {
            attempts.incrementAndGet();
            throw new IOException(privateText, new IllegalArgumentException(privateText));
        });
        assertThat(connection.search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.CONNECTION_ERROR, null));

        GooglePlacesProvider timeout = new GooglePlacesProvider(properties(), ignored -> (uri, method) -> {
            attempts.incrementAndGet();
            throw new SocketTimeoutException(privateText);
        });
        assertThat(timeout.details(PlacesFixtures.PLACE_ID))
                .isEqualTo(new PlaceDetailsResult.Failure(FailureKind.TIMEOUT, null));
        assertThat(output.getAll()).doesNotContain(privateText, PlacesFixtures.API_KEY, PlacesFixtures.QUERY);
    }

    @Test
    void 예상하지_못한_실패는_단계와_클래스명만_기록한다(CapturedOutput output) {
        String privateText = PlacesFixtures.API_KEY + PlacesFixtures.QUERY;
        GooglePlacesProvider provider = new GooglePlacesProvider(properties(), ignored -> (uri, method) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException(privateText, new IllegalArgumentException(privateText));
        });
        Logger logger = (Logger) LoggerFactory.getLogger(GooglePlacesProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(provider.search(PlacesFixtures.QUERY))
                    .isEqualTo(new PlacesSearchResult.Failure(FailureKind.INVALID_RESPONSE, null));
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Google Places failure phase=execute exceptionType=java.lang.IllegalStateException");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(output.getAll()).doesNotContain(privateText, PlacesFixtures.API_KEY, PlacesFixtures.QUERY,
                    "java.lang.IllegalArgumentException", "Caused by:");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 고정_endpoint만_사용하고_키는_query에_넣지_않는다() {
        GooglePlacesProvider provider = new GooglePlacesProvider(properties(), ignored -> (uri, method) -> {
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("places.googleapis.com");
            assertThat(uri.getRawQuery()).isNull();
            assertThat(uri.getPath()).isIn("/v1/places:searchText", "/v1/places/" + PlacesFixtures.PLACE_ID);
            return request(uri, json(method.name().equals("POST") ? "{}" : PlacesFixtures.CANDIDATE, 200));
        });
        provider.search("https://attacker.invalid/x?key=bad#fragment");
        provider.details(PlacesFixtures.PLACE_ID);
        assertThat(attempts).hasValue(2);
    }

    private GooglePlacesProvider provider(MockClientHttpResponse response) {
        return new GooglePlacesProvider(properties(), ignored -> (uri, method) -> request(uri, response));
    }

    private MockClientHttpRequest request(URI uri, MockClientHttpResponse response) {
        attempts.incrementAndGet();
        MockClientHttpRequest request = new MockClientHttpRequest();
        request.setURI(uri);
        request.setResponse(response);
        return request;
    }

    private MockClientHttpResponse unreadable(int status) {
        return new MockClientHttpResponse(new ByteArrayInputStream(new byte[0]) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                throw new AssertionError("Error body must not be read");
            }
        }, HttpStatusCode.valueOf(status));
    }

    private MockClientHttpResponse json(String body, int status) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8),
                HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    private GooglePlacesProperties properties() {
        return new GooglePlacesProperties(true, PlacesFixtures.API_KEY, null, null, 1024);
    }
}
