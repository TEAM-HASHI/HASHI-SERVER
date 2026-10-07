package org.sopt.hashi.restaurant.internal.map.google;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Changes the live-test opt-in property without invoking Google")
class MapLiveProviderConfigurationTest {
    @Test
    void 진단은_원문과_알수없는_타입을_출력하지_않는다() {
        var component = new org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.AddressComponent(
                "비공개주소二丁目", "비공개약어", java.util.List.of("premise", "비공개타입"));
        assertThat(MapLiveProviderConfiguration.componentSummary(component))
                .isEqualTo("Live diagnostic types=[premise, UNKNOWN] empty=false digitsOnly=false containsKanjiNumeral=true");
    }

    @Test
    void 명시적_실행_동의_없이는_실제_provider를_생성하지_않는다() {
        String previous = System.getProperty("map.live.enabled");
        try {
            System.clearProperty("map.live.enabled");
            assertThatThrownBy(() -> new MapLiveProviderConfiguration().liveGeocodingProvider())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Live provider requires explicit opt-in");
        } finally {
            if (previous == null) System.clearProperty("map.live.enabled");
            else System.setProperty("map.live.enabled", previous);
        }
    }
    @Test
    void 테스트_DNS는_Google호스트만_loopback으로_연결한다() throws Exception {
        var resolver = new MapLiveProviderConfiguration.TunnelDnsResolver();
        assertThat(resolver.resolve("geocode.googleapis.com")[0].getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(resolver.resolveCanonicalHostname("geocode.googleapis.com")).isEqualTo("geocode.googleapis.com");
        assertThatThrownBy(() -> resolver.resolve("other.example")).isInstanceOf(java.net.UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolveCanonicalHostname("other.example")).isInstanceOf(java.net.UnknownHostException.class);
    }
}
