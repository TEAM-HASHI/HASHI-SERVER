package org.sopt.hashi.magazine;

import java.time.LocalDateTime;
import org.sopt.hashi.media.MediaImage;

/**
 * 모듈 간 전달용 매거진 요약 DTO — 진입점(admin)이 매거진을 관리할 때 받는 계약.
 * URL 필드는 legacy URL 또는 READY 파생본 URL이며, 신규 asset의 상태별 응답을 함께 제공한다.
 */
public record MagazineInfo(
        Long magazineId,
        String title,
        String bannerImageUrl,
        MediaImage bannerImage,
        String thumbnailImageUrl,
        MediaImage thumbnailImage,
        String instagramRedirectUrl,
        LocalDateTime createdAt) {
}
