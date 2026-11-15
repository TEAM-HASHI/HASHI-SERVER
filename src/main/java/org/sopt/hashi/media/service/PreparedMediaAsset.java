package org.sopt.hashi.media.service;

import java.time.LocalDateTime;
import java.util.UUID;

record PreparedMediaAsset(
        UUID assetId,
        String objectKey,
        String contentType,
        long contentLength,
        LocalDateTime uploadExpiresAt
) {
}
