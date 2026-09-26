package org.sopt.hashi.restaurant.internal.map.google;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.NoResults;

class GoogleGeocodingDeadlineTest {

    private HttpServer server;
    private ExecutorService callers;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        callers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(callers);
        server.createContext("/v4/geocode/address", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'{', '}'});
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        callers.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void DNS가_취소를_무시해도_호출자는_반환하고_늦은_HTTP를_전송하지_않는다(boolean interruptCaller)
            throws Exception {
        DelayedResolver resolver = new DelayedResolver(1);
        GoogleGeocodingProvider provider = provider(resolver);
        AtomicReference<Thread> caller = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        Future<GeocodingResult> result = callers.submit(() -> {
            caller.set(Thread.currentThread());
            GeocodingResult outcome = provider.geocode(GeocodingFixtures.ADDRESS);
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            return outcome;
        });
        try {
            assertThat(resolver.entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (interruptCaller) {
                caller.get().interrupt();
            }
            FailureKind expected = interruptCaller ? FailureKind.CANCELLED : FailureKind.TIMEOUT;
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(new Failure(expected, null));
            assertThat(interruptedAfterReturn.get()).isEqualTo(interruptCaller);
            assertThat(resolver.threads).hasSize(1).allMatch(Thread::isAlive);
            assertThat(requests).hasValue(0);
        } finally {
            resolver.release.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> resolver.threads.stream().noneMatch(Thread::isAlive));
        assertThat(requests).hasValue(0);
    }

    @Test
    void 끝나지_않은_DNS가_있어도_작업과_대기열이_계속_늘지_않는다() throws Exception {
        int maximum = GoogleGeocodingProvider.MAX_CONCURRENT_CALLS;
        DelayedResolver resolver = new DelayedResolver(maximum);
        GoogleGeocodingProvider provider = provider(resolver);
        ArrayList<Future<GeocodingResult>> results = new ArrayList<>();
        for (int i = 0; i < maximum; i++) {
            results.add(callers.submit(() -> provider.geocode(GeocodingFixtures.ADDRESS)));
        }
        try {
            assertThat(resolver.entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (Future<GeocodingResult> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(new Failure(FailureKind.TIMEOUT, null));
            }
            for (int i = 0; i < 20; i++) {
                assertThat(provider.geocode(GeocodingFixtures.ADDRESS))
                        .isEqualTo(new Failure(FailureKind.CAPACITY_EXCEEDED, null));
            }
            assertThat(resolver.calls).hasValue(maximum);
            assertThat(resolver.threads).hasSize(maximum).allMatch(Thread::isAlive);
            assertThat(requests).hasValue(0);
        } finally {
            resolver.release.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> resolver.threads.stream().noneMatch(Thread::isAlive));
        assertThat(requests).hasValue(0);
        assertThat(provider.geocode(GeocodingFixtures.ADDRESS)).isEqualTo(new NoResults());
        assertThat(requests).hasValue(1);
    }

    private GoogleGeocodingProvider provider(DnsResolver resolver) {
        GoogleGeocodingProperties properties = new GoogleGeocodingProperties(true, GeocodingFixtures.API_KEY,
                Duration.ofMillis(100), Duration.ofSeconds(2), 1024);
        return new GoogleGeocodingProvider(properties, factory -> (uri, method) -> factory.createRequest(
                URI.create("http://synthetic.invalid:" + server.getAddress().getPort()
                        + uri.getRawPath() + "?" + uri.getRawQuery()), method), resolver);
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
                    // Model a platform DNS lookup that cannot be interrupted.
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
