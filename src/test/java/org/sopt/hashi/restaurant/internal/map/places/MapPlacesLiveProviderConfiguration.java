package org.sopt.hashi.restaurant.internal.map.places;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.apache.hc.client5.http.DnsResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only TCP tunnel: the HTTPS URI, SNI and normal certificate verification stay unchanged. */
@TestConfiguration(proxyBeanMethods = false)
class MapPlacesLiveProviderConfiguration {

    @Bean
    @Primary
    GooglePlacesProperties liveGooglePlacesProperties() {
        if (!Boolean.getBoolean("map.live.enabled")) {
            throw new IllegalStateException("Live Places provider requires explicit opt-in");
        }
        return new GooglePlacesProperties(true,
                System.getenv("HASHI_MAP_GOOGLEGEOCODING_APIKEY"),
                Duration.ofSeconds(5), Duration.ofSeconds(15), 65_536);
    }

    @Bean
    @Primary
    PlacesProvider livePlacesProvider(
            @Qualifier("liveGooglePlacesProperties") GooglePlacesProperties properties,
            LiveRequestCounter requests) {
        var delegate = new GooglePlacesProvider(properties, UnaryOperator.identity(), new TunnelDnsResolver());
        return new PlacesProvider() {
            @Override
            public PlacesSearchResult search(String query) {
                requests.acquireSearch();
                return delegate.search(query);
            }

            @Override
            public PlaceDetailsResult details(String placeId) {
                requests.acquireDetails(placeId);
                return delegate.details(placeId);
            }
        };
    }

    @Bean
    LiveRequestCounter livePlacesRequestCounter() {
        return new LiveRequestCounter();
    }

    static final class LiveRequestCounter {
        private final AtomicInteger searchRequests = new AtomicInteger();
        private final AtomicInteger detailRequests = new AtomicInteger();
        private final AtomicReference<String> selectedPlaceId = new AtomicReference<>();

        int acquireSearch() {
            if (!searchRequests.compareAndSet(0, 1)) {
                throw new IllegalStateException("Only one live Places search request is allowed");
            }
            return totalCount();
        }

        int acquireDetails(String placeId) {
            if (searchRequests.get() != 1) {
                throw new IllegalStateException("Live Places details requires the search request first");
            }
            if (placeId == null || placeId.isBlank()) {
                throw new IllegalStateException("Live Places details requires a selected place ID");
            }
            String selected = selectedPlaceId.get();
            if (selected == null) {
                if (!selectedPlaceId.compareAndSet(null, placeId)) {
                    selected = selectedPlaceId.get();
                }
            }
            if (selected != null && !selected.equals(placeId)) {
                throw new IllegalStateException("Live Places refresh must use the selected place ID");
            }
            while (true) {
                int current = detailRequests.get();
                if (current >= 2) {
                    throw new IllegalStateException("Only two live Places details requests are allowed");
                }
                if (detailRequests.compareAndSet(current, current + 1)) {
                    return totalCount();
                }
            }
        }

        int searchCount() {
            return searchRequests.get();
        }

        int detailCount() {
            return detailRequests.get();
        }

        int totalCount() {
            return searchCount() + detailCount();
        }
    }

    static final class TunnelDnsResolver implements DnsResolver {
        private void checkHost(String host) throws UnknownHostException {
            if (!"places.googleapis.com".equals(host)) {
                throw new UnknownHostException("Unexpected live-test host");
            }
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            checkHost(host);
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            checkHost(host);
            return host;
        }
    }
}
