package org.sopt.hashi.admin.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.media.MediaImage;

/** 어드민 매거진 단건 응답. 기존 URL 필드를 유지하면서 슬롯별 최적화 이미지 응답을 추가한다. */
public record AdminMagazineResponse(
        Long magazineId,
        String title,
        String bannerImageUrl,
        MediaImage bannerImage,
        String thumbnailImageUrl,
        MediaImage thumbnailImage,
        String instagramRedirectUrl,
        LocalDateTime createdAt) {

    public static AdminMagazineResponse from(MagazineInfo info) {
        return new AdminMagazineResponse(
                info.magazineId(),
                info.title(),
                info.bannerImageUrl(),
                info.bannerImage(),
                info.thumbnailImageUrl(),
                info.thumbnailImage(),
                info.instagramRedirectUrl(),
                info.createdAt());
    }
}
