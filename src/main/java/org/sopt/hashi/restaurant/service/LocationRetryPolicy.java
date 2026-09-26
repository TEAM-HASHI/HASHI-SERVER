package org.sopt.hashi.restaurant.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.stereotype.Service;

@Service
public class LocationRetryPolicy {
    public boolean canRetry(FailureKind kind) {
        return switch (kind) {
            case TIMEOUT, CONNECTION_ERROR, TRANSIENT_ERROR, QUOTA_EXCEEDED, CAPACITY_EXCEEDED, CANCELLED -> true;
            case DISABLED, INVALID_REQUEST, ACCESS_DENIED, CONFIGURATION_ERROR, INVALID_RESPONSE,
                 RESPONSE_TOO_LARGE, REDIRECT_REJECTED -> false;
        };
    }

    public Duration delay(FailureKind kind, int attempt) {
        long base = kind == FailureKind.QUOTA_EXCEEDED ? 300 : 30;
        long seconds = Math.min(1800, base * (1L << Math.min(Math.max(0, attempt - 1), 7)));
        return Duration.ofSeconds(seconds + ThreadLocalRandom.current().nextLong(seconds / 4 + 1));
    }
}
