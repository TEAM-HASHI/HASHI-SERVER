package org.sopt.hashi.restaurant.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.stereotype.Service;

@Service
public class LocationRetryPolicy {
    private static final long[] DELAY_SECONDS = {60, 300, 1800, 7200, 21600, 43200, 86400};

    public boolean canRetry(FailureKind kind) {
        return switch (kind) {
            case TIMEOUT, CONNECTION_ERROR, TRANSIENT_ERROR, QUOTA_EXCEEDED, CAPACITY_EXCEEDED, CANCELLED -> true;
            case DISABLED, INVALID_REQUEST, ACCESS_DENIED, CONFIGURATION_ERROR, INVALID_RESPONSE,
                 RESPONSE_TOO_LARGE, REDIRECT_REJECTED -> false;
        };
    }

    public Duration delay(FailureKind kind, int attempt) {
        if (!canRetry(kind) || attempt < 1 || attempt > 8) {
            throw new IllegalArgumentException("Retry delay requires a retryable failure and attempt between 1 and 8");
        }
        // The final quota failure still pauses other jobs, using the last interval.
        long seconds = DELAY_SECONDS[Math.min(attempt, DELAY_SECONDS.length) - 1];
        return Duration.ofSeconds(seconds + ThreadLocalRandom.current().nextLong(seconds / 10 + 1));
    }
}
