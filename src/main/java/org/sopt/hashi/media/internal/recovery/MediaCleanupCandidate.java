package org.sopt.hashi.media.internal.recovery;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;

public record MediaCleanupCandidate(
        long assetId,
        UUID publicId,
        ImageProcessingStatus processingStatus,
        MediaCreationOrigin creationOrigin,
        LocalDateTime updatedAt
) {

    public MediaCleanupCandidate {
        if (assetId < 1) {
            throw new IllegalArgumentException("assetId must be positive");
        }
        Objects.requireNonNull(publicId);
        Objects.requireNonNull(processingStatus);
        Objects.requireNonNull(creationOrigin);
        Objects.requireNonNull(updatedAt);
    }

    public MediaCleanupCandidateCursor nextCursor() {
        return new MediaCleanupCandidateCursor(updatedAt, assetId);
    }
}
