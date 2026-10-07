package org.sopt.hashi.admin.dto;

import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.magazine.MagazineCardNewsInfo;
import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.media.MediaImage;

/**
 * 어드민 매거진 단건 응답. 기존 URL 필드를 유지하면서 슬롯별 최적화 이미지 응답을 추가한다.
 * cardNewsImages·restaurantIds는 노출 순서대로이며, 없으면 빈 목록이다.
 */
public record AdminMagazineResponse(
        Long magazineId,
        String title,
        String bannerImageUrl,
        MediaImage bannerImage,
        String thumbnailImageUrl,
        MediaImage thumbnailImage,
        String instagramRedirectUrl,
        LocalDateTime createdAt,
        String content,
        List<CardNewsImageResponse> cardNewsImages,
        List<String> hashtags,
        List<Long> restaurantIds) {

    /**
     * 카드뉴스 이미지 1장 — 식당 이미지 wrapper와 같은 형태다(image-delivery-contract §9.2).
     * legacyUrl은 asset ID가 없는 카드뉴스에만 있고, asset이 있으면 상태와 관계없이 null이다.
     */
    public record CardNewsImageResponse(
            Long cardNewsId,
            int displayOrder,
            MediaImage image,
            String legacyUrl) {

        static CardNewsImageResponse from(MagazineCardNewsInfo info) {
            return new CardNewsImageResponse(
                    info.cardNewsId(), info.displayOrder(), info.image(), info.legacyUrl());
        }
    }

    public static AdminMagazineResponse from(MagazineInfo info) {
        return new AdminMagazineResponse(
                info.magazineId(),
                info.title(),
                info.bannerImageUrl(),
                info.bannerImage(),
                info.thumbnailImageUrl(),
                info.thumbnailImage(),
                info.instagramRedirectUrl(),
                info.createdAt(),
                info.content(),
                info.cardNews().stream().map(CardNewsImageResponse::from).toList(),
                info.hashtags(),
                info.restaurantIds());
    }
}
