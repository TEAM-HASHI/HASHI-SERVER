package org.sopt.hashi.media.internal.recovery;

import java.time.LocalDateTime;

public record MediaCleanupCandidateCursor(LocalDateTime updatedAt, long assetId) {

    public static MediaCleanupCandidateCursor initial() {
        return new MediaCleanupCandidateCursor(
                LocalDateTime.of(1000, 1, 1, 0, 0),
                0L
        );
    }
}
