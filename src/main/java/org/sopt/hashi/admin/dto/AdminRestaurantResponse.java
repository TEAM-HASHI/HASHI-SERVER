package org.sopt.hashi.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantImageInfo;

/**
 * 어드민 식당 단건 응답(등록·수정 결과). 기존 URL 필드는 legacy 또는 READY media 호환용이며,
 * 신규 이미지 필드는 상태와 반응형 후보를 전달한다. genre·curationTypes는 사용자 API와 같은
 * 소문자 케밥 값이다.
 */
public record AdminRestaurantResponse(
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
        RestaurantImageInfo thumbnailImage,
        String priceCurrency,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean deleted,
        List<String> imageUrls,
        List<RestaurantImageInfo> heroImages,
        List<AdminRestaurantMenuResponse> menus,
        List<String> hashtags,
        List<String> curationTypes,
        List<AdminRestaurantBusinessHourResponse> businessHours,
        LocalDateTime createdAt) {

    public static AdminRestaurantResponse from(AdminRestaurantInfo info) {
        return new AdminRestaurantResponse(
                info.restaurantId(),
                info.name(),
                info.localName(),
                info.summary(),
                info.description(),
                info.address(),
                info.area(),
                info.genre(),
                info.foodCategory(),
                info.thumbnailUrl(),
                info.thumbnailImage(),
                info.priceCurrency(),
                info.minPrice(),
                info.maxPrice(),
                info.deleted(),
                info.imageUrls(),
                info.heroImages(),
                info.menus().stream()
                        .map(AdminRestaurantMenuResponse::from)
                        .toList(),
                info.hashtags(),
                info.curationTypes(),
                info.businessHours().stream()
                        .map(AdminRestaurantBusinessHourResponse::from)
                        .toList(),
                info.createdAt());
    }

    public record AdminRestaurantMenuResponse(
            Long menuId,
            String name,
            String description,
            String imageUrl,
            MediaImage listImage,
            String priceCurrency,
            BigDecimal priceAmount,
            boolean main) {

        static AdminRestaurantMenuResponse from(AdminRestaurantInfo.AdminRestaurantMenuInfo info) {
            return new AdminRestaurantMenuResponse(
                    info.menuId(),
                    info.name(),
                    info.description(),
                    info.imageUrl(),
                    info.listImage(),
                    info.priceCurrency(),
                    info.priceAmount(),
                    info.main());
        }
    }

    /** 요일별 영업시간 — dayOfWeek는 MONDAY~SUNDAY, 시간은 HH:mm 문자열(휴무일은 null). */
    public record AdminRestaurantBusinessHourResponse(
            String dayOfWeek,
            String openTime,
            String closeTime,
            String breakStart,
            String breakEnd,
            boolean closed) {

        static AdminRestaurantBusinessHourResponse from(
                AdminRestaurantInfo.AdminRestaurantBusinessHourInfo info) {
            return new AdminRestaurantBusinessHourResponse(
                    info.dayOfWeek(),
                    info.openTime(),
                    info.closeTime(),
                    info.breakStart(),
                    info.breakEnd(),
                    info.closed());
        }
    }
}
