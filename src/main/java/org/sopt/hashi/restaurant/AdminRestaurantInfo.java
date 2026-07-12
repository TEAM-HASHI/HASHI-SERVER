package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 모듈 간 전달용 어드민 식당 상세 DTO — 진입점(admin)이 식당을 관리(등록·수정)할 때 받는 계약.
 * thumbnailUrl·imageUrls·메뉴 imageUrl은 저장된 S3 키를 restaurant가 조회 URL로 변환한 값이다
 * (키 자체는 노출하지 않는다). genre·curationTypes는 사용자 API와 같은 소문자 케밥 값이다.
 */
public record AdminRestaurantInfo(
        Long restaurantId,
        String name,
        String localName,
        String summary,
        String description,
        String address,
        String area,
        String genre,
        String foodCategory,
        String thumbnailUrl,
        String priceCurrency,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean active,
        List<String> imageUrls,
        List<AdminRestaurantMenuInfo> menus,
        List<String> hashtags,
        List<String> curationTypes,
        List<AdminRestaurantBusinessHourInfo> businessHours,
        LocalDateTime createdAt) {

    public record AdminRestaurantMenuInfo(
            Long menuId,
            String name,
            String description,
            String imageUrl,
            String priceCurrency,
            BigDecimal priceAmount,
            boolean main) {
    }

    /** 요일별 영업시간 — dayOfWeek는 MONDAY~SUNDAY, 시간은 HH:mm 문자열(휴무일은 null). */
    public record AdminRestaurantBusinessHourInfo(
            String dayOfWeek,
            String openTime,
            String closeTime,
            String breakStart,
            String breakEnd,
            boolean closed) {
    }
}
