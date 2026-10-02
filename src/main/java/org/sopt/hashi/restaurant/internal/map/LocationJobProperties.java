package org.sopt.hashi.restaurant.internal.map;

import java.math.BigDecimal;
import java.time.Duration;
import org.sopt.hashi.restaurant.domain.MapBounds;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.map.location-job")
public record LocationJobProperties(
        boolean enabled,
        Duration retention,
        BigDecimal south,
        BigDecimal north,
        BigDecimal west,
        BigDecimal east,
        Integer maxAttempts
) {
    public static final Duration LEASE = Duration.ofMinutes(2);

    public LocationJobProperties {
        maxAttempts = maxAttempts == null ? 4 : maxAttempts;
        if (maxAttempts < 1 || maxAttempts > 8) {
            throw new IllegalArgumentException("Location job max-attempts must be between 1 and 8");
        }
    }

    /** Missing map settings must not prevent the existing restaurant application from starting. */
    public boolean isConfigured() {
        if (retention == null || retention.compareTo(Duration.ofMinutes(5)) < 0
                || retention.compareTo(Duration.ofDays(30)) > 0) {
            return false;
        }
        try {
            MapBounds.of(south, north, west, east);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
