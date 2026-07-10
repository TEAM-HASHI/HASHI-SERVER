package org.sopt.hashi.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * 어드민 식당 등록 요청. thumbnailKey·imageKeys·메뉴 imageKey는 presigned URL로 업로드 완료된
 * S3 object key다. genre·curationTypes는 사용자 API와 같은 소문자 케밥 값("sushi", "sns-hot")이다.
 * businessHours는 7개 요일(MONDAY~SUNDAY)을 중복 없이 모두 포함해야 한다(시간은 "HH:mm").
 */
public record CreateRestaurantRequest(
        @NotBlank(message = "식당명은 필수입니다")
        @Size(max = 100, message = "식당명은 100자 이내입니다") String name,
        @Size(max = 100, message = "현지 식당명은 100자 이내입니다") String localName,
        @Size(max = 500, message = "설명은 500자 이내입니다") String description,
        String storeDescription,
        @NotBlank(message = "주소는 필수입니다")
        @Size(max = 255, message = "주소는 255자 이내입니다") String address,
        @Size(max = 100, message = "지역은 100자 이내입니다") String area,
        @NotBlank(message = "장르는 필수입니다") String genre,
        @Size(max = 500, message = "썸네일 이미지 키는 500자 이내입니다") String thumbnailKey,
        @NotNull(message = "예약 수수료는 필수입니다")
        @PositiveOrZero(message = "예약 수수료는 0 이상입니다") Long reservationFee,
        @NotBlank(message = "통화는 필수입니다")
        @Size(max = 10, message = "통화는 10자 이내입니다") String currency,
        @PositiveOrZero(message = "최소 가격은 0 이상입니다") BigDecimal minPrice,
        @PositiveOrZero(message = "최대 가격은 0 이상입니다") BigDecimal maxPrice,
        List<@NotBlank(message = "이미지 키는 비어 있을 수 없습니다")
        @Size(max = 500, message = "이미지 키는 500자 이내입니다") String> imageKeys,
        @Valid List<MenuRequest> menus,
        List<@NotBlank(message = "큐레이션 유형은 비어 있을 수 없습니다") String> curationTypes,
        @NotNull(message = "영업시간은 필수입니다")
        @Size(min = 7, max = 7, message = "영업시간은 모든 요일(7개)을 포함해야 합니다")
        @Valid List<BusinessHourRequest> businessHours) {

    /** 메뉴 항목 — 목록 전체가 함께 저장되므로 각 항목은 완전한 값으로 받는다. */
    public record MenuRequest(
            @NotBlank(message = "메뉴명은 필수입니다")
            @Size(max = 100, message = "메뉴명은 100자 이내입니다") String name,
            @Size(max = 500, message = "메뉴 설명은 500자 이내입니다") String description,
            @Size(max = 500, message = "메뉴 이미지 키는 500자 이내입니다") String imageKey,
            @NotBlank(message = "통화는 필수입니다")
            @Size(max = 10, message = "통화는 10자 이내입니다") String currency,
            @PositiveOrZero(message = "가격은 0 이상입니다") BigDecimal price,
            @NotNull(message = "대표 메뉴 여부는 필수입니다") Boolean representative) {
    }

    /** 요일별 영업시간 — 휴무일(closed=true)은 시간 없이 보내고, 영업일은 openTime·closeTime이 필수다. */
    public record BusinessHourRequest(
            @NotNull(message = "요일은 필수입니다") DayOfWeek dayOfWeek,
            LocalTime openTime,
            LocalTime closeTime,
            LocalTime lastOrderTime,
            @NotNull(message = "휴무 여부는 필수입니다") Boolean closed) {
    }
}
