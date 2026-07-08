package org.sopt.hashi.upload.dto;

import org.sopt.hashi.shared.storage.PresignedUploadInfo;

public record PresignedUrlResponse(
        String uploadUrl,
        String fileKey,
        String fileUrl,
        int expiresInSeconds,
        String uploadMethod
) {

    public static PresignedUrlResponse from(PresignedUploadInfo presignedUploadInfo) {
        return new PresignedUrlResponse(
                presignedUploadInfo.uploadUrl(),
                presignedUploadInfo.fileKey(),
                presignedUploadInfo.fileUrl(),
                presignedUploadInfo.expiresInSeconds(),
                presignedUploadInfo.uploadMethod()
        );
    }
}
