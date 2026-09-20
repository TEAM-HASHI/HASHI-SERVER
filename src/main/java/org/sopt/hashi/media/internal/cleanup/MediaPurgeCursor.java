package org.sopt.hashi.media.internal.cleanup;

import java.time.LocalDateTime;
import java.util.Objects;

public record MediaPurgeCursor(LocalDateTime lastAttemptAt, long assetId) {

    public MediaPurgeCursor {
        Objects.requireNonNull(lastAttemptAt);
        if (assetId < 0) {
            throw new IllegalArgumentException("purge cursor id must not be negative");
        }
    }

    public static MediaPurgeCursor initial() {
        return new MediaPurgeCursor(LocalDateTime.of(1000, 1, 1, 0, 0), 0L);
    }
}
