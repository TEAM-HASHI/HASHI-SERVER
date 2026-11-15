package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.map.places-selection")
public record PlacesSelectionProperties(boolean enabled, String signingKey, Duration tokenTtl) {
    private static final Duration REQUIRED_TTL = Duration.ofMinutes(10);

    public PlacesSelectionProperties {
        tokenTtl = tokenTtl == null ? REQUIRED_TTL : tokenTtl;
    }

    public boolean isConfigured() {
        if (!enabled || signingKey == null || !REQUIRED_TTL.equals(tokenTtl)) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(signingKey).length >= 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    byte[] signingKeyBytes() {
        if (!isConfigured()) {
            throw new IllegalStateException("Places selection signing is not configured");
        }
        return Base64.getDecoder().decode(signingKey);
    }

    @Override
    public String toString() {
        return "PlacesSelectionProperties[redacted]";
    }
}
