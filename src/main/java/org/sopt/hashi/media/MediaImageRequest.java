package org.sopt.hashi.media;

import java.util.Objects;
import java.util.UUID;

/** asset과 화면 role로 파생 이미지 projection을 조회하는 bulk key. */
public record MediaImageRequest(UUID assetId, MediaImageRole role) {

    public MediaImageRequest {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }
}
