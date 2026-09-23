package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;

/**
 * 모듈 간 전달용 어드민 식당 상세 DTO — 진입점(admin)이 식당을 관리(등록·수정)할 때 받는 계약.
 * 기존 URL 필드는 legacy key 또는 READY media의 기본 후보를 호환 projection한 값이며, 신규 이미지
 * 필드는 상태와 반응형 후보를 함께 전달한다. object key 자체는 노출하지 않는다.
 * genre·curationTypes는 사용자 API와 같은 소문자 케밥 값이고, placeType(음식점 분류, #211)은
 * "restaurant"·"cafe"·"bar"다.
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
        String placeType,
        String thumbnailUrl,
        RestaurantImageInfo thumbnailImage,
        String priceCurrency,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean deleted,
        List<String> imageUrls,
        List<RestaurantImageInfo> heroImages,
        List<AdminRestaurantMenuInfo> menus,
        List<String> hashtags,
        List<String> curationTypes,
        List<AdminRestaurantBusinessHourInfo> businessHours,
        LocalDateTime createdAt) {

    public AdminRestaurantInfo(
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
            boolean deleted,
            List<String> imageUrls,
            List<AdminRestaurantMenuInfo> menus,
            List<String> hashtags,
            List<String> curationTypes,
            List<AdminRestaurantBusinessHourInfo> businessHours,
            LocalDateTime createdAt
    ) {
        this(
                restaurantId, name, localName, summary, description, address, area, genre,
                foodCategory, null, thumbnailUrl, null, priceCurrency, minPrice, maxPrice, deleted,
                imageUrls, List.of(), menus, hashtags, curationTypes, businessHours, createdAt);
    }

    public record AdminRestaurantMenuInfo(
            Long menuId,
            String name,
            String description,
            String imageUrl,
            MediaImage listImage,
            String priceCurrency,
            BigDecimal priceAmount,
            boolean main) {

        public AdminRestaurantMenuInfo(
                Long menuId,
                String name,
                String description,
                String imageUrl,
                String priceCurrency,
                BigDecimal priceAmount,
                boolean main
        ) {
            this(
                    menuId, name, description, imageUrl, null,
                    priceCurrency, priceAmount, main);
        }
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
