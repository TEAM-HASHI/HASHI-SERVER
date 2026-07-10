package org.sopt.hashi.admin.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.magazine.MagazineInfo;

/** 어드민 매거진 단건 응답(등록·수정 결과). bannerImageUrl은 저장된 키를 변환한 조회 URL이다. */
public record AdminMagazineResponse(
        Long magazineId,
        String title,
        String bannerImageUrl,
        String instagramRedirectUrl,
        LocalDateTime createdAt) {

    public static AdminMagazineResponse from(MagazineInfo info) {
        return new AdminMagazineResponse(
                info.magazineId(),
                info.title(),
                info.bannerImageUrl(),
                info.instagramRedirectUrl(),
                info.createdAt());
    }
}
