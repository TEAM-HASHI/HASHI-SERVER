package org.sopt.hashi.magazine.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 매거진 상세 응답. cardNewsImageUrls는 캐러셀 순서대로의 카드뉴스 이미지 조회 URL이고,
 * liked는 로그인 회원의 좋아요 여부(비로그인은 false)다. restaurants는 연결 식당 카드로,
 * 삭제된 식당은 제외되며 연결 식당이 없으면 빈 목록이다.
 */
public record MagazineDetailResponse(
        Long magazineId,
        String title,
        List<String> cardNewsImageUrls,
        String content,
        List<String> hashtags,
        LocalDateTime createdAt,
        long likeCount,
        boolean liked,
        List<MagazineRestaurantResponse> restaurants) {

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
