package org.sopt.hashi.restaurant.internal.map.places;

import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.map.google-places")
public record GooglePlacesProperties(
        boolean enabled,
        String apiKey,
        Duration connectTimeout,
        Duration responseTimeout,
        Integer maxResponseBytes
) {

    public GooglePlacesProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(5) : responseTimeout;
        maxResponseBytes = maxResponseBytes == null ? 65_536 : maxResponseBytes;
    }

    void validateEnabled() {
        if (apiKey == null || !apiKey.matches("[A-Za-z0-9_-]{1,256}")) {
            throw new IllegalStateException("Google Places requires a valid server API key");
        }
        validateTimeout(connectTimeout, Duration.ofSeconds(10), "connect timeout");
        validateTimeout(responseTimeout, Duration.ofSeconds(30), "response timeout");
        if (responseTimeout.compareTo(connectTimeout) < 0) {
            throw new IllegalStateException("Google Places response timeout must cover connect timeout");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > 1_048_576) {
            throw new IllegalStateException("Google Places response limit must be between 1024 and 1048576 bytes");
        }
        for (String logger : new String[]{"org.apache.hc.client5.http.wire", "org.apache.hc.client5.http.headers",
                "org.apache.hc.client5.http.impl.classic.InternalHttpClient",
                "org.apache.hc.client5.http.impl.classic.MainClientExec",
                "org.apache.hc.client5.http.impl.classic.ProtocolExec"}) {
            if (LoggerFactory.getLogger(logger).isDebugEnabled()) {
                throw new IllegalStateException("Google Places requires HTTP wire logging to be disabled");
            }
        }
    }

    private static void validateTimeout(Duration value, Duration maximum, String name) {
        boolean isOutOfBounds = value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(maximum) > 0;
        if (isOutOfBounds) {
            throw new IllegalStateException("Google Places " + name + " is outside the allowed range");
        }
    }

    @Override
    public String toString() {
        return "GooglePlacesProperties[redacted]";
    }
}
