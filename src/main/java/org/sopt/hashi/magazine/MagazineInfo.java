package org.sopt.hashi.magazine;

import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;

/**
 * 모듈 간 전달용 매거진 DTO — 진입점(admin)이 매거진을 관리할 때 받는 계약.
 * URL 필드는 legacy URL 또는 READY 파생본 URL이며, 신규 asset의 상태별 응답을 함께 제공한다.
 * cardNews·restaurantIds는 노출 순서대로이고, 없으면 빈 목록이다.
 */
public record MagazineInfo(
        Long magazineId,
        String title,
        String bannerImageUrl,
        MediaImage bannerImage,
        String thumbnailImageUrl,
        MediaImage thumbnailImage,
        String instagramRedirectUrl,
        LocalDateTime createdAt,
        String content,
        List<MagazineCardNewsInfo> cardNews,
        List<String> hashtags,
        List<Long> restaurantIds) {

    public MagazineInfo {
        cardNews = cardNews == null ? List.of() : List.copyOf(cardNews);
        hashtags = hashtags == null ? List.of() : List.copyOf(hashtags);
        restaurantIds = restaurantIds == null ? List.of() : List.copyOf(restaurantIds);
    }
}
