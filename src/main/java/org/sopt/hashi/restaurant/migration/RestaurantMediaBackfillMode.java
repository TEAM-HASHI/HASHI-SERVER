package org.sopt.hashi.restaurant.migration;

public enum RestaurantMediaBackfillMode {

    DRY_RUN,
    PREPARE,
    ATTACH;

    boolean usesCheckpoint() {
        return this != DRY_RUN;
    }
}
