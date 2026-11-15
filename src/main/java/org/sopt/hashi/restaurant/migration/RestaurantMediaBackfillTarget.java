package org.sopt.hashi.restaurant.migration;

import org.sopt.hashi.media.MediaBackfillTarget;

public enum RestaurantMediaBackfillTarget {

    RESTAURANT_IMAGE(MediaBackfillTarget.RESTAURANT_IMAGE),
    RESTAURANT_MENU(MediaBackfillTarget.RESTAURANT_MENU);

    private final MediaBackfillTarget mediaTarget;

    RestaurantMediaBackfillTarget(MediaBackfillTarget mediaTarget) {
        this.mediaTarget = mediaTarget;
    }

    MediaBackfillTarget mediaTarget() {
        return mediaTarget;
    }
}
