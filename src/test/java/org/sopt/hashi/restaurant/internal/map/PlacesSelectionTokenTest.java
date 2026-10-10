package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlacesSelectionTokenTest {
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final PlacesSelectionProperties PROPERTIES =
            new PlacesSelectionProperties(true, KEY, Duration.ofMinutes(10));

    @Test
    void 식당_revision_request_PlaceID를_10분_서명토큰으로_왕복한다() {
        UUID requestId = UUID.randomUUID();
        PlacesSelectionToken tokens = tokensAt(NOW);

        var issued = tokens.issue(11L, 3, requestId, "place-123");

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(tokens.verify(issued.token())).hasValueSatisfying(claims -> {
            assertThat(claims.restaurantId()).isEqualTo(11L);
            assertThat(claims.addressRevision()).isEqualTo(3);
            assertThat(claims.requestId()).isEqualTo(requestId);
            assertThat(claims.placeId()).isEqualTo("place-123");
        });
        assertThat(issued.token()).doesNotContain("place-123");
    }

    @Test
    void 변조와_만료와_설정오류는_검증을_통과하지못한다() {
        String token = tokensAt(NOW).issue(11L, 3, UUID.randomUUID(), "place-123").token();
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertThat(tokensAt(NOW).verify(tampered)).isEmpty();
        assertThat(tokensAt(NOW.plus(Duration.ofMinutes(10))).verify(token)).isEmpty();
        assertThat(new PlacesSelectionProperties(true,
                Base64.getEncoder().encodeToString(new byte[31]), Duration.ofMinutes(10)).isConfigured()).isFalse();
        assertThat(new PlacesSelectionProperties(true, KEY, Duration.ofMinutes(11)).isConfigured()).isFalse();
    }

    private PlacesSelectionToken tokensAt(Instant instant) {
        return new PlacesSelectionToken(PROPERTIES, Clock.fixed(instant, ZoneOffset.UTC));
    }
}
