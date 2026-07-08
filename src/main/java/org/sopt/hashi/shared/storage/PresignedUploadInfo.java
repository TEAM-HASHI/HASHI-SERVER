package org.sopt.hashi.shared.storage;

public record PresignedUploadInfo(
        String uploadUrl,
        String fileKey,
        String fileUrl,
        int expiresInSeconds,
        String uploadMethod
) {
}
