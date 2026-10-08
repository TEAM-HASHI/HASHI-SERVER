package org.sopt.hashi.restaurant.internal.map.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

class GooglePlacesWireTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void 검색과_상세는_공식_method_body_key_fieldMask만_전송한다() throws Exception {
        List<CapturedRequest> captured = java.util.Collections.synchronizedList(new ArrayList<>());
        server.createContext("/v1/", exchange -> {
            requests.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"),
                    exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"), body));
            String response = exchange.getRequestURI().getRawPath().endsWith(":searchText")
                    ? PlacesFixtures.SEARCH_SUCCESS : PlacesFixtures.CANDIDATE;
            respond(exchange, 200, response, false);
        });

        GooglePlacesProvider provider = provider(2000, 4096);
        assertThat(provider.search(PlacesFixtures.QUERY)).isInstanceOf(PlacesSearchResult.Candidates.class);
        assertThat(provider.details(PlacesFixtures.PLACE_ID)).isInstanceOf(PlaceDetailsResult.Place.class);

        assertThat(requests).hasValue(2);
        CapturedRequest search = captured.get(0);
        assertThat(search.method()).isEqualTo("POST");
        assertThat(search.path()).isEqualTo("/v1/places:searchText");
        assertThat(search.contentType()).startsWith("application/json");
        assertThat(search.apiKey()).isEqualTo(PlacesFixtures.API_KEY);
        assertThat(search.fieldMask()).isEqualTo(GooglePlacesProvider.SEARCH_FIELD_MASK).doesNotContain("*");
        JsonNode body = mapper.readTree(search.body());
        List<String> fieldNames = new ArrayList<>();
        body.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder("textQuery", "languageCode",
                "regionCode", "pageSize", "includePureServiceAreaBusinesses");
        assertThat(body.path("textQuery").textValue()).isEqualTo(PlacesFixtures.QUERY);
        assertThat(body.path("languageCode").textValue()).isEqualTo("en");
        assertThat(body.path("regionCode").textValue()).isEqualTo("JP");
        assertThat(body.path("pageSize").intValue()).isEqualTo(5);
        assertThat(body.path("includePureServiceAreaBusinesses").booleanValue()).isFalse();

        CapturedRequest details = captured.get(1);
        assertThat(details.method()).isEqualTo("GET");
        assertThat(details.path()).isEqualTo("/v1/places/" + PlacesFixtures.PLACE_ID);
        assertThat(details.apiKey()).isEqualTo(PlacesFixtures.API_KEY);
        assertThat(details.fieldMask()).isEqualTo(GooglePlacesProvider.DETAILS_FIELD_MASK).doesNotContain("*");
        assertThat(details.body()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void redirect를_따라가지_않아_키를_다른_경로로_전달하지_않는다(int status) {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/v1/places:searchText", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/leak");
            respond(exchange, status, "private redirect body", false);
        });
        server.createContext("/leak", exchange -> {
            redirected.incrementAndGet();
            respond(exchange, 200, "{}", false);
        });

        assertThat(provider(2000, 1024).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.REDIRECT_REJECTED, status));
        assertThat(requests).hasValue(1);
        assertThat(redirected).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void 제한과_일시실패를_HTTP에서_재시도하지_않는다(int status) {
        server.createContext("/v1/places:searchText", exchange -> {
            requests.incrementAndGet();
            respond(exchange, status, "{\"error\":{\"message\":\"private\"}}", false);
        });
        assertThat(provider(2000, 1024).search(PlacesFixtures.QUERY)).isInstanceOf(
                PlacesSearchResult.Failure.class);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ContentLength와_chunked_응답을_모두_제한한다(boolean chunked) {
        server.createContext("/v1/places:searchText", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, " ".repeat(2048), chunked);
        });
        assertThat(provider(2000, 1024).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.RESPONSE_TOO_LARGE, 200));
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 헤더와_본문_지연을_전체_timeout으로_중단한다(boolean bodyDelay) {
        server.createContext("/v1/places:searchText", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                if (bodyDelay) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    for (int i = 0; i < 20; i++) {
                        exchange.getResponseBody().write(' ');
                        exchange.getResponseBody().flush();
                        Thread.sleep(50);
                    }
                } else {
                    Thread.sleep(1000);
                    respond(exchange, 200, "{}", false);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The provider aborts the call at its deadline.
            }
        });

        long started = System.nanoTime();
        assertThat(provider(200, 1024).search(PlacesFixtures.QUERY))
                .isEqualTo(new PlacesSearchResult.Failure(FailureKind.TIMEOUT, null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(requests).hasValue(1);
    }

    @Test
    void placeId_경로주입은_HTTP_요청을_만들지_않는다() {
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/v1/", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, PlacesFixtures.CANDIDATE, false);
        });
        GooglePlacesProvider provider = provider(2000, 4096);
        assertThat(provider.details("../places:searchText?key=leak"))
                .isEqualTo(new PlaceDetailsResult.Failure(FailureKind.INVALID_REQUEST, null));
        assertThat(requests).hasValue(0);
        assertThat(path.get()).isNull();
    }

    private GooglePlacesProvider provider(int timeoutMillis, int maxResponseBytes) {
        GooglePlacesProperties properties = new GooglePlacesProperties(true, PlacesFixtures.API_KEY,
                Duration.ofMillis(100), Duration.ofMillis(timeoutMillis), maxResponseBytes);
        int port = server.getAddress().getPort();
        return new GooglePlacesProvider(properties, factory -> (uri, method) -> {
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("places.googleapis.com");
            return factory.createRequest(URI.create("http://127.0.0.1:" + port + uri.getRawPath()), method);
        });
    }

    private void respond(HttpExchange exchange, int status, String body, boolean chunked) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private record CapturedRequest(
            String method,
            String path,
            String contentType,
            String apiKey,
            String fieldMask,
            byte[] body
    ) {
    }
}
