package org.sopt.hashi.restaurant.internal.map.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

class GoogleGeocodingWireTest {

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();

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
    void 일본어와_예약문자를_보존하고_고정_헤더로_요청한다() {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> mask = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/v4/geocode/address", exchange -> {
            requests.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            key.set(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"));
            mask.set(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"));
            method.set(exchange.getRequestMethod());
            respond(exchange, 200, GeocodingFixtures.SUCCESS, false);
        });

        assertThat(provider(2000).geocode(GeocodingFixtures.ADDRESS)).isInstanceOf(Candidates.class);
        assertThat(requests).hasValue(1);
        assertThat(method.get()).isEqualTo("GET");
        assertThat(key.get()).isEqualTo(GeocodingFixtures.API_KEY);
        assertThat(mask.get()).isEqualTo(GoogleGeocodingProvider.FIELD_MASK).doesNotContain("*", "formattedAddress");
        String[] params = query.get().split("&");
        assertThat(params).hasSize(3);
        assertThat(URLDecoder.decode(params[0].substring("address.addressLines=".length()), StandardCharsets.UTF_8))
                .isEqualTo(GeocodingFixtures.ADDRESS);
        assertThat(query.get()).contains("%2B", "%26", "%23", "%2F", "%25", "languageCode=ja", "regionCode=JP");
        assertThat(query.get()).doesNotContain(GeocodingFixtures.API_KEY);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void redirect를_따라가지_않아_다음_주소로_키를_전달하지_않는다(int status) {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/v4/geocode/address", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/leak");
            respond(exchange, status, "sensitive error", false);
        });
        server.createContext("/leak", exchange -> {
            redirected.incrementAndGet();
            respond(exchange, 200, "{}", false);
        });
        assertThat(provider(2000).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.REDIRECT_REJECTED, status));
        assertThat(requests).hasValue(1);
        assertThat(redirected).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void quota와_일시_실패는_실제_HTTP에서도_재시도하지_않는다(int status) {
        server.createContext("/v4/geocode/address", exchange -> {
            requests.incrementAndGet();
            respond(exchange, status, "{\"error\":{\"message\":\"private\"}}", false);
        });
        assertThat(provider(2000).geocode(GeocodingFixtures.ADDRESS)).isInstanceOf(Failure.class);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ContentLength와_chunked_모두_응답_크기를_제한한다(boolean chunked) {
        server.createContext("/v4/geocode/address", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, " ".repeat(2048), chunked);
        });
        assertThat(provider(2000).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.RESPONSE_TOO_LARGE, 200));
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 헤더와_본문_지연을_모두_유한한_timeout으로_중단한다(boolean bodyDelay) {
        server.createContext("/v4/geocode/address", exchange -> {
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
                // The client closes the stream at the deadline.
            }
        });
        long started = System.nanoTime();
        assertThat(provider(200).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.TIMEOUT, null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(requests).hasValue(1);
    }

    @Test
    void 요청을_받은_뒤_응답없이_끊어도_HTTP를_재전송하지_않는다() throws Exception {
        try (ServerSocket socketServer = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))) {
            // Allow first-use TLS/client initialization before the first accept, then observe possible replays.
            socketServer.setSoTimeout(5000);
            var received = executor.submit(() -> {
                int count = 0;
                while (true) {
                    try (Socket socket = socketServer.accept()) {
                        socket.setSoTimeout(700);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            // Consume just the request headers and then close without a response.
                        }
                        count++;
                        socketServer.setSoTimeout(700);
                    } catch (SocketTimeoutException done) {
                        return count;
                    }
                }
            });
            GoogleGeocodingProvider provider = localProvider(socketServer.getLocalPort(), 2000);
            var result = provider.geocode(GeocodingFixtures.ADDRESS);
            assertThat(received.get(6, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(result).isEqualTo(new Failure(FailureKind.CONNECTION_ERROR, null));
        }
    }

    @Test
    void 인증_challenge에도_두번째_요청을_보내지_않는다() {
        server.createContext("/v4/geocode/address", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=synthetic");
            respond(exchange, 401, "{}", false);
        });
        assertThat(provider(2000).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.ACCESS_DENIED, 401));
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void 초과_또는_오류_본문은_cleanup에서도_끝까지_읽지_않는다(int status) {
        CountDownLatch finishBody = new CountDownLatch(1);
        server.createContext("/v4/geocode/address", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, 0);
                exchange.getResponseBody().write(new byte[1025]);
                exchange.getResponseBody().flush();
                finishBody.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(' ');
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Expected: cleanup aborts the connection without draining this delayed body.
            }
        });
        FailureKind expected = status == 200 ? FailureKind.RESPONSE_TOO_LARGE : FailureKind.TRANSIENT_ERROR;
        try {
            // A draining close would wait for finishBody, hit the client deadline and return TIMEOUT instead.
            assertThat(provider(2000).geocode(GeocodingFixtures.ADDRESS)).isEqualTo(new Failure(expected, status));
        } finally {
            finishBody.countDown();
        }
    }

    @Test
    void 연결_거부도_분류하며_다음_HTTP_호출을_시작하지_않는다() throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0)) {
            port = reserved.getLocalPort();
        }
        assertThat(localProvider(port, 2000).geocode(GeocodingFixtures.ADDRESS))
                .isEqualTo(new Failure(FailureKind.CONNECTION_ERROR, null));
    }

    private GoogleGeocodingProvider provider(int timeoutMillis) {
        return localProvider(server.getAddress().getPort(), timeoutMillis);
    }

    private GoogleGeocodingProvider localProvider(int port, int timeoutMillis) {
        GoogleGeocodingProperties properties = new GoogleGeocodingProperties(true, GeocodingFixtures.API_KEY,
                Duration.ofMillis(100), Duration.ofMillis(timeoutMillis), 1024);
        return new GoogleGeocodingProvider(properties, factory -> (uri, method) -> {
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("geocode.googleapis.com");
            return factory.createRequest(URI.create("http://127.0.0.1:" + port + uri.getRawPath()
                    + "?" + uri.getRawQuery()), method);
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
}
