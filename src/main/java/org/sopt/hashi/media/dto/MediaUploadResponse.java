package org.sopt.hashi.media.dto;

import java.util.Map;
import java.util.UUID;
import org.sopt.hashi.media.domain.ImageProcessingStatus;

public record MediaUploadResponse(
        UUID assetId,
        ImageProcessingStatus status,
        String uploadUrl,
        Map<String, String> requiredHeaders,
        long expectedContentLength,
        int expiresInSeconds,
        String uploadMethod
) {

    public MediaUploadResponse {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}
