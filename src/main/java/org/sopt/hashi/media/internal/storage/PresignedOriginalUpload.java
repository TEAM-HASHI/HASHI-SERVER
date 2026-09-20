package org.sopt.hashi.media.internal.storage;

import java.util.Map;

public record PresignedOriginalUpload(
        String uploadUrl,
        Map<String, String> requiredHeaders,
        long expectedContentLength,
        int expiresInSeconds,
        String uploadMethod
) {

    public PresignedOriginalUpload {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}
