package org.sopt.hashi.media.internal.reconciliation;

import java.util.List;

public record MediaObjectVersionPage(
        List<MediaObjectVersion> objects,
        MediaObjectVersionCursor nextCursor
) {

    public MediaObjectVersionPage {
        objects = List.copyOf(objects);
    }

    public boolean hasNext() {
        return nextCursor != null;
    }
}
