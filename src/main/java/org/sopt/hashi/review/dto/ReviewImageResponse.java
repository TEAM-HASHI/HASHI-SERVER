package org.sopt.hashi.review.dto;

import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageStatus;

/** 슬롯은 상태와 무관하게 유지하며, legacyUrl은 asset이 없는 사진에만 제공한다. */
public record ReviewImageResponse(Long reviewImageId, int displayOrder, MediaImage image, String legacyUrl) {
    public String displayUrl() {
        return image == null ? legacyUrl
                : image.status() == MediaImageStatus.READY ? image.defaultSource().url() : null;
    }
}
