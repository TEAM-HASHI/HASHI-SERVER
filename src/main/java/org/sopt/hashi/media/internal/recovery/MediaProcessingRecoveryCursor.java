package org.sopt.hashi.media.internal.recovery;

import java.time.LocalDateTime;

public record MediaProcessingRecoveryCursor(LocalDateTime startedAt, long assetId) {

    public static MediaProcessingRecoveryCursor initial() {
        return new MediaProcessingRecoveryCursor(
                LocalDateTime.of(1000, 1, 1, 0, 0),
                0L
        );
    }
}
