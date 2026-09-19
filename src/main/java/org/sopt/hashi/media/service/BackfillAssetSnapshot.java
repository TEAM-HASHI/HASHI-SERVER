package org.sopt.hashi.media.service;

import java.time.LocalDateTime;
import java.util.UUID;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaPurpose;

/** 트랜잭션 밖 복사 작업에 필요한 media 내부 snapshot. Entity와 legacy 소속 ID는 전달하지 않는다. */
public record BackfillAssetSnapshot(
        UUID assetId,
        String identityHash,
        MediaPurpose purpose,
        String originalObjectKey,
        LocalDateTime expiresAt,
        ImageProcessingStatus processingStatus,
        ImageBindingStatus bindingStatus,
        MediaCleanupStatus cleanupStatus,
        String sourceVersionId,
        String sourceETag,
        UUID jobId
) {

    static BackfillAssetSnapshot from(ImageAsset asset) {
        return new BackfillAssetSnapshot(asset.getPublicId(), asset.getBackfillIdentityHash(),
                asset.getPurpose(), asset.getOriginalObjectKey(), asset.getUploadExpiresAt(),
                asset.getProcessingStatus(), asset.getBindingStatus(), asset.getCleanupStatus(),
                asset.getSourceVersionId(), asset.getSourceEtag(), asset.getCurrentJobId());
    }

    @Override
    public String toString() {
        return "BackfillAssetSnapshot[redacted]";
    }
}
