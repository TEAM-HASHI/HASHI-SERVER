package org.sopt.hashi.restaurant.internal.map.google;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.apache.hc.client5.http.DnsResolver;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.AddressComponent;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only TCP tunnel: the HTTPS URI, SNI and normal certificate verification stay unchanged. */
@TestConfiguration(proxyBeanMethods = false)
class MapLiveProviderConfiguration {
    @Bean @Primary
    GeocodingProvider liveGeocodingProvider(LiveRequestCounter requests) {
        if (!Boolean.getBoolean("map.live.enabled")) {
            throw new IllegalStateException("Live provider requires explicit opt-in");
        }
        var properties = new GoogleGeocodingProperties(true,
                System.getenv("HASHI_MAP_GOOGLEGEOCODING_APIKEY"),
                Duration.ofSeconds(5), Duration.ofSeconds(15), 65536);
        var delegate = new GoogleGeocodingProvider(properties, UnaryOperator.identity(), new TunnelDnsResolver());
        return address -> {
            requests.acquire();
            var result = delegate.geocode(address);
            if (result instanceof Candidates candidates) {
                for (int index = 0; index < candidates.candidates().size(); index++) {
                    var components = candidates.candidates().get(index).addressComponents();
                    System.out.println("Live diagnostic candidate=" + index + " componentCount=" + components.size());
                    components.forEach(component -> System.out.println(componentSummary(component)));
                }
            }
            return result;
        };
    }

    @Bean
    LiveRequestCounter liveRequestCounter() {
        return new LiveRequestCounter(2);
    }

    // Never print provider strings, except names in this fixed type allowlist.
    static String componentSummary(AddressComponent component) {
        Set<String> known = Set.of("country", "postal_code", "political", "sublocality", "locality",
                "administrative_area_level_1", "administrative_area_level_2", "sublocality_level_1",
                "sublocality_level_2", "sublocality_level_3", "sublocality_level_4", "sublocality_level_5",
                "route", "street_number", "premise", "subpremise", "street_address", "point_of_interest");
        String text = component.longText() == null ? "" : component.longText();
        return "Live diagnostic types=" + component.types().stream().map(type -> known.contains(type) ? type : "UNKNOWN").toList()
                + " empty=" + text.isBlank() + " digitsOnly=" + text.matches("[0-9０-９]+")
                + " containsKanjiNumeral=" + text.matches(".*[〇零一二三四五六七八九十百千万壱弐参].*");
    }

    static final class LiveRequestCounter {
        private final int maximum;
        private final AtomicInteger requests = new AtomicInteger();

        LiveRequestCounter(int maximum) {
            this.maximum = maximum;
        }

        int acquire() {
            while (true) {
                int current = requests.get();
                if (current >= maximum) {
                    throw new IllegalStateException("Only two live provider requests are allowed");
                }
                if (requests.compareAndSet(current, current + 1)) {
                    return current + 1;
                }
            }
        }

        int count() {
            return requests.get();
        }
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
