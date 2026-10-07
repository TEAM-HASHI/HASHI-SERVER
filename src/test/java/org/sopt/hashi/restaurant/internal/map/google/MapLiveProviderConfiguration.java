package org.sopt.hashi.restaurant.internal.map.google;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.apache.hc.client5.http.DnsResolver;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only TCP tunnel: the HTTPS URI, SNI and normal certificate verification stay unchanged. */
@TestConfiguration(proxyBeanMethods = false)
class MapLiveProviderConfiguration {
    @Bean @Primary
    GeocodingProvider liveGeocodingProvider() {
        if (!Boolean.getBoolean("map.live.enabled")) {
            throw new IllegalStateException("Live provider requires explicit opt-in");
        }
        var properties = new GoogleGeocodingProperties(true,
                System.getenv("HASHI_MAP_GOOGLEGEOCODING_APIKEY"),
                Duration.ofSeconds(5), Duration.ofSeconds(15), 65536);
        var delegate = new GoogleGeocodingProvider(properties, UnaryOperator.identity(), new TunnelDnsResolver());
        AtomicInteger requests = new AtomicInteger();
        return address -> {
            if (requests.incrementAndGet() != 1) {
                throw new IllegalStateException("Only one live provider request is allowed");
            }
            return delegate.geocode(address);
        };
    }
    static final class TunnelDnsResolver implements DnsResolver {
        private void checkHost(String host) throws UnknownHostException {
            if (!"geocode.googleapis.com".equals(host)) {
                throw new UnknownHostException("Unexpected live-test host");
            }
        }
        public InetAddress[] resolve(String host) throws UnknownHostException {
            checkHost(host);
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
        }
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            checkHost(host);
            return host;
        }
    }
}
