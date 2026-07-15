package org.sopt.hashi.magazine;

import java.time.LocalDateTime;

/**
 * 모듈 간 전달용 매거진 요약 DTO — 진입점(admin)이 매거진을 관리할 때 받는 계약.
 * bannerImageUrl·thumbnailImageUrl은 저장된 S3 키를 magazine이 조회 URL로 변환한 값이다(키 자체는 노출하지 않는다).
 */
public record MagazineInfo(
        Long magazineId,
        String title,
        String bannerImageUrl,
        String thumbnailImageUrl,
        String instagramRedirectUrl,
        LocalDateTime createdAt) {
}
