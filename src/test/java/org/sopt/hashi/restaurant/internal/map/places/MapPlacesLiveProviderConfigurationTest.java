package org.sopt.hashi.restaurant.internal.map.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Changes the live-test opt-in property without invoking Google")
class MapPlacesLiveProviderConfigurationTest {

    @Test
    void 명시적_실행동의_없이는_실제_provider를_생성하지_않는다() {
        String previous = System.getProperty("map.live.enabled");
        try {
            System.clearProperty("map.live.enabled");
            assertThatThrownBy(() -> new MapPlacesLiveProviderConfiguration().liveGooglePlacesProperties())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Live Places provider requires explicit opt-in");
        } finally {
            if (previous == null) {
                System.clearProperty("map.live.enabled");
            } else {
                System.setProperty("map.live.enabled", previous);
            }
        }
    }

    @Test
    void 실제_provider_호출은_검색한번과_같은장소_상세두번만_허용한다() {
        var requests = new MapPlacesLiveProviderConfiguration.LiveRequestCounter();

        assertThat(requests.acquireSearch()).isEqualTo(1);
        assertThat(requests.acquireDetails("place-one")).isEqualTo(2);
        assertThat(requests.acquireDetails("place-one")).isEqualTo(3);
        assertThat(requests.searchCount()).isEqualTo(1);
        assertThat(requests.detailCount()).isEqualTo(2);
        assertThat(requests.totalCount()).isEqualTo(3);
        assertThatThrownBy(requests::acquireSearch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only one live Places search request is allowed");
        assertThatThrownBy(() -> requests.acquireDetails("place-one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only two live Places details requests are allowed");
    }

    @Test
    void 검색에서_고른장소와_다른_ID로_갱신하지_않는다() {
        var requests = new MapPlacesLiveProviderConfiguration.LiveRequestCounter();

        requests.acquireSearch();
        requests.acquireDetails("place-one");
        assertThatThrownBy(() -> requests.acquireDetails("place-two"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Live Places refresh must use the selected place ID");
        assertThat(requests.totalCount()).isEqualTo(2);
    }

    @Test
    void 검색전에_상세호출을_허용하지_않는다() {
        var requests = new MapPlacesLiveProviderConfiguration.LiveRequestCounter();

        assertThatThrownBy(() -> requests.acquireDetails("place-one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Live Places details requires the search request first");
        assertThat(requests.totalCount()).isZero();
    }

    @Test
    void 테스트_DNS는_Places_Google호스트만_loopback으로_연결한다() throws Exception {
        var resolver = new MapPlacesLiveProviderConfiguration.TunnelDnsResolver();

        assertThat(resolver.resolve("places.googleapis.com")[0].getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(resolver.resolveCanonicalHostname("places.googleapis.com")).isEqualTo("places.googleapis.com");
        assertThatThrownBy(() -> resolver.resolve("other.example")).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolveCanonicalHostname("other.example"))
                .isInstanceOf(UnknownHostException.class);
    }
}
