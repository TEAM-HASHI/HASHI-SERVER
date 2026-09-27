package org.sopt.hashi.media;

import java.util.Objects;
import java.util.UUID;

/** migration 진행 판단용 snapshot. Entity, 원본 경로, 원시 콘텐츠 식별자는 전달하지 않는다. */
public record MediaBackfillAssetInfo(
        UUID assetId,
        MediaAssetPurpose purpose,
        String identityHash,
        State state
) {

    public MediaBackfillAssetInfo {
        Objects.requireNonNull(assetId, "assetId is required");
        Objects.requireNonNull(purpose, "purpose is required");
        Objects.requireNonNull(state, "backfill state is required");
        if (identityHash == null || !identityHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backfill identity must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return "MediaBackfillAssetInfo[redacted]";
    }

    /** READY는 미연결·cleanup ACTIVE인 경우만 의미하며, claim 시 현재 상태를 다시 검증한다. */
    public enum State {
        PENDING_COPY,
        PROCESSING,
        READY,
        FAILED,
        EXPIRED,
        BOUND,
        RETIRED,
        PURGING,
        PURGED
    }
}
