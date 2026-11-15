package org.sopt.hashi.magazine;

import org.sopt.hashi.media.MediaImage;

/**
 * 모듈 간 전달용 카드뉴스 이미지 1장 — 식당 이미지 wrapper와 같은 형태다(image-delivery-contract §9.2).
 * image는 public asset으로 연결된 카드뉴스의 상태별 이미지이고, legacyUrl은 asset ID가 없는
 * legacy key 카드뉴스에만 있다. asset이 있으면 READY 여부와 관계없이 legacyUrl은 null이다.
 */
public record MagazineCardNewsInfo(
        Long cardNewsId,
        int displayOrder,
        MediaImage image,
        String legacyUrl
) {
}
