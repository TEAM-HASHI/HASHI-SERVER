package org.sopt.hashi.restaurant.migration;

import java.util.Objects;

record RestaurantMediaBackfillCandidate(
        RestaurantMediaBackfillTarget target,
        long associationId,
        long restaurantId,
        String legacyKey
) {

    RestaurantMediaBackfillCandidate {
        Objects.requireNonNull(target, "target is required");
        if (associationId < 1 || restaurantId < 1 || legacyKey == null) {
            throw new IllegalArgumentException("invalid restaurant media backfill candidate");
        }
    }

    boolean hasUsableLegacyKey() {
        return !legacyKey.isBlank();
    }

    @Override
    public String toString() {
        return "RestaurantMediaBackfillCandidate[redacted]";
    }
}
