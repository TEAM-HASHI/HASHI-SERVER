package org.sopt.hashi.media;

import java.util.Objects;
import java.util.Optional;

/** 조회만 수행한 결과. asset이 비어 있으면 아직 해당 identity를 예약하지 않은 상태다. */
public record MediaBackfillInspectionInfo(
        String identityHash,
        MediaAssetPurpose purpose,
        Optional<MediaBackfillAssetInfo> asset
) {

    public MediaBackfillInspectionInfo {
        Objects.requireNonNull(purpose, "purpose is required");
        Objects.requireNonNull(asset, "asset optional is required");
        if (identityHash == null || !identityHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backfill identity must be a lowercase SHA-256 digest");
        }
        asset.ifPresent(info -> {
            if (info.purpose() != purpose || !identityHash.equals(info.identityHash())) {
                throw new IllegalArgumentException("backfill inspection and asset must have the same identity");
            }
        });
    }

    @Override
    public String toString() {
        return "MediaBackfillInspectionInfo[redacted]";
    }
}
