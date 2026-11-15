package org.sopt.hashi.magazine.migration;

import org.sopt.hashi.media.MediaBackfillTarget;

public enum MagazineMediaBackfillTarget {

    MAGAZINE_BANNER(MediaBackfillTarget.MAGAZINE_BANNER),
    MAGAZINE_THUMBNAIL(MediaBackfillTarget.MAGAZINE_THUMBNAIL);

    private final MediaBackfillTarget mediaTarget;

    MagazineMediaBackfillTarget(MediaBackfillTarget mediaTarget) {
        this.mediaTarget = mediaTarget;
    }

    MediaBackfillTarget mediaTarget() {
        return mediaTarget;
    }
}
