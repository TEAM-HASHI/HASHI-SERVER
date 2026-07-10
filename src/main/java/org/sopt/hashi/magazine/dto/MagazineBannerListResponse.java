package org.sopt.hashi.magazine.dto;

import java.util.List;

/** 매거진 배너 목록 응답 — 최신 매거진 5개의 배너. 배너 클릭 시 instagramRedirectUrl로 이동한다. */
public record MagazineBannerListResponse(List<MagazineBannerResponse> banners) {

    public record MagazineBannerResponse(
            Long magazineId,
            String title,
            String bannerImageUrl,
            String instagramRedirectUrl) {
    }
}
