package org.sopt.hashi.media;

import java.util.Objects;
import java.util.UUID;

/** claim 또는 retire 대상 asset과 콘텐츠가 기대하는 purpose. */
public record MediaAssetUse(UUID assetId, MediaAssetPurpose purpose) {

    public MediaAssetUse {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
    }
}
