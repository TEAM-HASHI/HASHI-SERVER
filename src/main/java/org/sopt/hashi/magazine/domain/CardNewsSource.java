package org.sopt.hashi.magazine.domain;

import java.util.UUID;

/** 카드뉴스 한 장의 이미지 출처 — legacy key 또는 public asset ID 중 정확히 하나를 가진다. */
public record CardNewsSource(String fileKey, UUID imageAssetId) {

    public CardNewsSource {
        boolean hasBlankKey = fileKey != null && fileKey.isBlank();
        boolean hasExactlyOneSource = (fileKey == null) != (imageAssetId == null);
        if (hasBlankKey || !hasExactlyOneSource) {
            throw new IllegalArgumentException("card news requires exactly one non-blank source");
        }
    }
}
