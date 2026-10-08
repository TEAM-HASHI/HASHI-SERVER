package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantImageInfo;

/**
 * 어드민 식당 단건 응답(등록·수정 결과). 기존 URL 필드는 legacy 또는 READY media 호환용이며,
 * 신규 이미지 필드는 상태와 반응형 후보를 전달한다. genre·curationTypes는 사용자 API와 같은
 * 소문자 케밥 값이고, placeType(음식점 분류, #211)은 restaurant·cafe·bar다.
 */
@Schema(description = "어드민 식당 저장 결과. 저장 성공과 Google 위치 확인 완료는 별개이며 locationStatus로 후속 상태를 확인")
public record AdminRestaurantResponse(
        Long restaurantId,
        String name,
        String localName,
        String summary,
        String description,
        @Schema(description = "사용자 화면에 표시할 전체 주소")
        String address,
        @Schema(description = "Google 위치 확인에만 쓰는 별도 지정 주소. null이면 address를 위치 확인 기준으로 사용")
        String geocodingAddress,
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
        List<AdminRestaurantMenuResponse> menus,
        List<String> hashtags,
        List<String> curationTypes,
        List<AdminRestaurantBusinessHourResponse> businessHours,
        LocalDateTime createdAt,
        @Schema(description = "비동기 위치 처리 상태. PENDING 응답은 식당 저장과 작업 등록 성공이며 Google 위치 확인 완료가 아님",
                allowableValues = {"UNRESOLVED", "PENDING", "READY", "RETRY_WAIT", "REVIEW_REQUIRED", "FAILED"},
                example = "PENDING")
        String locationStatus,
        @Schema(description = "현재 위치 확인 기준 주소의 revision. 위치 재시도 전 상태 조회 API에서 최신 값을 확인",
                example = "1")
        long addressRevision) {

    public static AdminRestaurantResponse from(AdminRestaurantInfo info) {
        return new AdminRestaurantResponse(
                info.restaurantId(),
                info.name(),
                info.localName(),
                info.summary(),
                info.description(),
                info.address(),
                info.geocodingAddress(),
                info.area(),
                info.genre(),
                info.foodCategory(),
                info.placeType(),
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
                info.createdAt(),
                info.locationStatus(),
                info.addressRevision());
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
