package org.sopt.hashi.media;

import java.util.UUID;

/**
 * 콘텐츠 모듈이 소유한 이미지 association의 전환기 참조 값이다.
 * legacy backfill이 끝난 association은 두 값이 모두 존재할 수 있다.
 */
public record ImageReference(UUID assetId, String legacyUrl) {

    public static ImageReference legacy(String legacyUrl) {
        return new ImageReference(null, legacyUrl);
    }

    public static ImageReference asset(UUID assetId) {
        return new ImageReference(assetId, null);
    }
}
