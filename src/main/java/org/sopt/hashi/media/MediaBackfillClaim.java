package org.sopt.hashi.media;

import java.util.Objects;
import java.util.UUID;

/** source 재확인 결과와 준비된 asset을 대조한다. 원시 콘텐츠 ID나 S3 경로는 포함하지 않는다. */
public record MediaBackfillClaim(UUID assetId, MediaAssetPurpose purpose, String identityHash) {

    public MediaBackfillClaim {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (identityHash == null || !identityHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backfill identity must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return "MediaBackfillClaim[redacted]";
    }
}
