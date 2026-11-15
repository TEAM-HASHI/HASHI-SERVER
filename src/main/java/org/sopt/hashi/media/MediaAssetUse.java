package org.sopt.hashi.media;

import java.util.Objects;
import java.util.UUID;

/**
 * claim 또는 retire 대상 asset과 콘텐츠가 기대하는 purpose.
 * requireReady는 교체 claim에 추가하는 조건이며, false여도 목적별 기본 상태 검증은 적용한다.
 */
public record MediaAssetUse(UUID assetId, MediaAssetPurpose purpose, boolean requireReady) {

    public MediaAssetUse(UUID assetId, MediaAssetPurpose purpose) {
        this(assetId, purpose, false);
    }

    public MediaAssetUse {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
    }
}
