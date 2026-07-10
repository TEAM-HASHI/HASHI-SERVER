package org.sopt.hashi.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;

/**
 * 어드민 식당 단건 응답(등록·수정 결과). thumbnailUrl·imageUrls·메뉴 imageUrl은 저장된 키를
 * 변환한 조회 URL이다. genre·curationTypes는 사용자 API와 같은 소문자 케밥 값이다.
 */
public record AdminRestaurantResponse(
        Long restaurantId,
        String name,
        String localName,
        String description,
        String storeDescription,
        String address,
        String area,
        String genre,
        String thumbnailUrl,
        Long reservationFee,
        String currency,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean active,
        List<String> imageUrls,
        List<AdminRestaurantMenuResponse> menus,
        List<String> curationTypes,
        List<AdminRestaurantBusinessHourResponse> businessHours,
        LocalDateTime createdAt) {

    public static AdminRestaurantResponse from(AdminRestaurantInfo info) {
        return new AdminRestaurantResponse(
                info.restaurantId(),
                info.name(),
                info.localName(),
                info.description(),
                info.storeDescription(),
                info.address(),
                info.area(),
                info.genre(),
                info.thumbnailUrl(),
                info.reservationFee(),
                info.currency(),
                info.minPrice(),
                info.maxPrice(),
                info.active(),
                info.imageUrls(),
                info.menus().stream()
                        .map(AdminRestaurantMenuResponse::from)
                        .toList(),
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
            String currency,
            BigDecimal price,
            boolean representative) {

        static AdminRestaurantMenuResponse from(AdminRestaurantInfo.AdminRestaurantMenuInfo info) {
            return new AdminRestaurantMenuResponse(
                    info.menuId(),
                    info.name(),
                    info.description(),
                    info.imageUrl(),
                    info.currency(),
                    info.price(),
                    info.representative());
        }
    }

    /** 요일별 영업시간 — dayOfWeek는 MONDAY~SUNDAY, 시간은 HH:mm 문자열(휴무일은 null). */
    public record AdminRestaurantBusinessHourResponse(
            String dayOfWeek,
            String openTime,
            String closeTime,
            String lastOrderTime,
            boolean closed) {

        static AdminRestaurantBusinessHourResponse from(
                AdminRestaurantInfo.AdminRestaurantBusinessHourInfo info) {
            return new AdminRestaurantBusinessHourResponse(
                    info.dayOfWeek(),
                    info.openTime(),
                    info.closeTime(),
                    info.lastOrderTime(),
                    info.closed());
        }
    }
}
