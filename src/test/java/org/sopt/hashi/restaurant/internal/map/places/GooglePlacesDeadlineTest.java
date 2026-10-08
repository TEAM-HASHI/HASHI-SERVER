package org.sopt.hashi.restaurant.internal.map.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

class GooglePlacesDeadlineTest {

    private static final int AWAIT_SECONDS = 15;

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService callers;

    @BeforeEach
    void setUp() throws IOException {
        callers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(callers);
        server.createContext("/v1/places:searchText", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        callers.shutdownNow();
    }

    @Test
    void 취소를_무시하는_DNS가_있어도_호출수와_대기열이_계속_늘지_않는다() throws Exception {
        int maximum = GooglePlacesProvider.MAX_CONCURRENT_CALLS;
        DelayedResolver resolver = new DelayedResolver(maximum);
        GooglePlacesProvider provider = provider(resolver);
        ArrayList<Future<PlacesSearchResult>> results = new ArrayList<>();
        for (int index = 0; index < maximum; index++) {
            results.add(callers.submit(() -> provider.search(PlacesFixtures.QUERY)));
        }
        try {
            assertThat(resolver.entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            for (Future<PlacesSearchResult> result : results) {
                assertThat(result.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                        .isEqualTo(new PlacesSearchResult.Failure(FailureKind.TIMEOUT, null));
            }
            for (int index = 0; index < 20; index++) {
                assertThat(provider.search(PlacesFixtures.QUERY))
                        .isEqualTo(new PlacesSearchResult.Failure(FailureKind.CAPACITY_EXCEEDED, null));
            }
            assertThat(resolver.calls).hasValue(maximum);
            assertThat(resolver.threads).hasSize(maximum).allMatch(Thread::isAlive);
            assertThat(requests).hasValue(0);
        } finally {
            resolver.release.countDown();
        }
        await().atMost(Duration.ofSeconds(AWAIT_SECONDS))
                .until(() -> resolver.threads.stream().noneMatch(Thread::isAlive));
        assertThat(requests).hasValue(0);
        assertThat(provider.search(PlacesFixtures.QUERY)).isEqualTo(new PlacesSearchResult.NoResults());
        assertThat(requests).hasValue(1);
    }

    private GooglePlacesProvider provider(DnsResolver resolver) {
        GooglePlacesProperties properties = new GooglePlacesProperties(true, PlacesFixtures.API_KEY,
                Duration.ofMillis(100), Duration.ofSeconds(2), 1024);
        int port = server.getAddress().getPort();
        return new GooglePlacesProvider(properties, factory -> (uri, method) -> factory.createRequest(
                URI.create("http://synthetic.invalid:" + port + uri.getRawPath()), method), resolver);
    }

    private static final class DelayedResolver implements DnsResolver {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        private DelayedResolver(int expectedCalls) {
            entered = new CountDownLatch(expectedCalls);
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            calls.incrementAndGet();
            threads.add(Thread.currentThread());
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Model a resolver that cannot be interrupted.
                }
            }
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
