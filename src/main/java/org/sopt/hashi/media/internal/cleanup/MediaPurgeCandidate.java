package org.sopt.hashi.media.internal.cleanup;

import java.time.LocalDateTime;
import java.util.Objects;
import org.sopt.hashi.media.service.MediaPurgeWork;

public record MediaPurgeCandidate(MediaPurgeWork work, LocalDateTime lastAttemptAt) {

    public MediaPurgeCandidate {
        Objects.requireNonNull(work);
        Objects.requireNonNull(lastAttemptAt);
    }

    public MediaPurgeCursor nextCursor() {
        return new MediaPurgeCursor(lastAttemptAt, work.assetId());
    }

    @Override
    public String toString() {
        return "MediaPurgeCandidate[redacted]";
    }
}
