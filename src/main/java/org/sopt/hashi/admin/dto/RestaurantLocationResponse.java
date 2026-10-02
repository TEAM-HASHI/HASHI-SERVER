package org.sopt.hashi.admin.dto;

import java.time.Instant;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;

public record RestaurantLocationResponse(Long restaurantId, String locationStatus, long addressRevision,
                                         Instant validUntil, int attempt, Instant nextAttemptAt,
                                         String failureCode, boolean canRetry) {
    public static RestaurantLocationResponse from(RestaurantLocationInfo info) {
        return new RestaurantLocationResponse(info.restaurantId(), info.locationStatus(), info.addressRevision(),
                info.validUntil(), info.attempt(), info.nextAttemptAt(), info.failureCode(), info.canRetry());
    }
}
