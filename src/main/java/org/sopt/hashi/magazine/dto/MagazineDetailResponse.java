package org.sopt.hashi.magazine.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;

/**
 * 매거진 상세 응답. cardNewsImageUrls는 캐러셀 순서대로의 카드뉴스 이미지 조회 URL로,
 * 표시 가능한 기존 주소와 READY 주소만 담는다. cardNewsImages는 카드뉴스 전체를 순서대로 담은
 * wrapper 목록이며 식당 이미지 wrapper와 같은 형태다(image-delivery-contract §9.2) —
 * 카드뉴스는 원본 비율을 유지해 높이가 이미지마다 다르므로 image의 width·height를 사용한다.
 * 두 목록은 길이가 다를 수 있어 같은 인덱스로 맞추지 않는다(기존 URL 필드는 호환을 위해 유지).
 * liked는 로그인 회원의 좋아요 여부(비로그인은 false)다. restaurants는 연결 식당 카드로,
 * 삭제된 식당은 제외되며 연결 식당이 없으면 빈 목록이다.
 */
public record MagazineDetailResponse(
        Long magazineId,
        String title,
        List<String> cardNewsImageUrls,
        List<CardNewsImageResponse> cardNewsImages,
        String content,
        List<String> hashtags,
        LocalDateTime createdAt,
        long likeCount,
        boolean liked,
        List<MagazineRestaurantResponse> restaurants) {

    /**
     * 카드뉴스 이미지 1장. image.status가 READY면 새 이미지를, image가 null이고 legacyUrl이 있으면 기존 주소를 쓴다.
     * legacyUrl은 asset ID가 없는 카드뉴스에만 있고, asset이 있으면 상태와 관계없이 null이다.
     */
    public record CardNewsImageResponse(
            Long cardNewsId,
            int displayOrder,
            MediaImage image,
            String legacyUrl) {
    }

    /** 연결 식당 카드 — 식당명·평점·지역·카테고리·식당 이미지(최대 3장)·오늘 영업시간·예상 가격대. */
    public record MagazineRestaurantResponse(
            Long restaurantId,
            String name,
            BigDecimal rating,
            String area,
            String foodCategory,
            List<String> imageUrls,
            TodayBusinessHourResponse todayBusinessHour,
            PriceRangeResponse priceRange) {
    }

    /** 오늘 영업시간 — 등록된 요일이 없으면 null. 휴무일이면 시각이 null이고 closed가 true다. */
    public record TodayBusinessHourResponse(
            String date,
            String dayOfWeek,
            String openTime,
            String closeTime,
            boolean closed) {
    }

    public record PriceRangeResponse(
            String currency,
            Long minPrice,
            Long maxPrice) {
    }
}
