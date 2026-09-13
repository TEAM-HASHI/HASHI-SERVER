package org.sopt.hashi.media.service;

import java.time.LocalDateTime;
import java.util.UUID;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;

record OwnedAssetSnapshot(
        UUID assetId,
        String objectKey,
        String declaredContentType,
        long declaredBytes,
        LocalDateTime uploadExpiresAt,
        ImageProcessingStatus status,
        MediaCleanupStatus cleanupStatus
) {
}
