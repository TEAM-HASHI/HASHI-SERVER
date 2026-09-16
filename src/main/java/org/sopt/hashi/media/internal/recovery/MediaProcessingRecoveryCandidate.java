package org.sopt.hashi.media.internal.recovery;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record MediaProcessingRecoveryCandidate(
        long assetId,
        UUID jobId,
        LocalDateTime startedAt
) {

    public MediaProcessingRecoveryCandidate {
        if (assetId < 1) {
            throw new IllegalArgumentException("assetId must be positive");
        }
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(startedAt);
    }

    public MediaProcessingRecoveryCursor nextCursor() {
        return new MediaProcessingRecoveryCursor(startedAt, assetId);
    }
}
