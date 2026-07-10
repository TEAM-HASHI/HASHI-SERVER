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
 * 어드민 식당 등록 요청. imageKeys·메뉴 imageKey는 presigned URL로 업로드 완료된 S3 object key다.
 * genre·foodCategory·curationTypes는 사용자 API와 같은 소문자 케밥 값이다.
 * businessHours는 7개 요일(MONDAY~SUNDAY)을 중복 없이 모두 포함해야 한다(시간은 "HH:mm").
 */
public record CreateRestaurantRequest(
        @NotBlank(message = "식당명은 필수입니다")
        @Size(max = 100, message = "식당명은 100자 이내입니다") String name,
        @NotBlank(message = "현지 식당명은 필수입니다")
        @Size(max = 100, message = "현지 식당명은 100자 이내입니다") String localName,
        @NotBlank(message = "한 줄 소개는 필수입니다")
        @Size(max = 100, message = "한 줄 소개는 100자 이내입니다") String summary,
        @NotBlank(message = "상세 설명은 필수입니다")
        @Size(max = 500, message = "상세 설명은 500자 이내입니다") String description,
        @NotBlank(message = "주소는 필수입니다")
        @Size(max = 255, message = "주소는 255자 이내입니다") String address,
        @NotBlank(message = "지역은 필수입니다")
        @Size(max = 20, message = "지역은 20자 이내입니다") String area,
        @NotBlank(message = "장르는 필수입니다") String genre,
        @NotBlank(message = "음식 카테고리는 필수입니다") String foodCategory,
        @NotBlank(message = "통화는 필수입니다")
        @Size(min = 3, max = 3, message = "통화는 3자리 코드여야 합니다") String priceCurrency,
        @NotNull(message = "최소 가격은 필수입니다")
        @PositiveOrZero(message = "최소 가격은 0 이상입니다") BigDecimal minPrice,
        @NotNull(message = "최대 가격은 필수입니다")
        @PositiveOrZero(message = "최대 가격은 0 이상입니다") BigDecimal maxPrice,
        @Size(min = 1, message = "식당 이미지는 최소 1개 이상 필요합니다")
        List<@NotBlank(message = "이미지 키는 비어 있을 수 없습니다")
        @Size(max = 500, message = "이미지 키는 500자 이내입니다") String> imageKeys,
        List<@NotNull(message = "메뉴 항목은 null일 수 없습니다") @Valid MenuRequest> menus,
        List<@NotBlank(message = "해시태그는 비어 있을 수 없습니다")
        @Size(max = 20, message = "해시태그는 20자 이내입니다") String> hashtags,
        List<@NotBlank(message = "큐레이션 유형은 비어 있을 수 없습니다") String> curationTypes,
        @NotNull(message = "영업시간은 필수입니다")
        @Size(min = 7, max = 7, message = "영업시간은 모든 요일(7개)을 포함해야 합니다")
        List<@NotNull(message = "영업시간 항목은 null일 수 없습니다") @Valid BusinessHourRequest> businessHours) {

    /** 메뉴 항목 — 목록 전체가 함께 저장되므로 각 항목은 완전한 값으로 받는다. */
    public record MenuRequest(
            @NotBlank(message = "메뉴명은 필수입니다")
            @Size(max = 100, message = "메뉴명은 100자 이내입니다") String name,
            @NotBlank(message = "메뉴 설명은 필수입니다")
            @Size(max = 500, message = "메뉴 설명은 500자 이내입니다") String description,
            @Size(max = 500, message = "메뉴 이미지 키는 500자 이내입니다") String imageKey,
            @NotBlank(message = "통화는 필수입니다")
            @Size(min = 3, max = 3, message = "통화는 3자리 코드여야 합니다") String priceCurrency,
            @NotNull(message = "가격은 필수입니다")
            @PositiveOrZero(message = "가격은 0 이상입니다") BigDecimal priceAmount,
            @NotNull(message = "대표 메뉴 여부는 필수입니다") Boolean main) {
    }

    /** 요일별 영업시간 — 휴무일(closed=true)은 시간 없이 보내고, 영업일은 openTime·closeTime이 필수다. */
    public record BusinessHourRequest(
            @NotNull(message = "요일은 필수입니다") DayOfWeek dayOfWeek,
            LocalTime openTime,
            LocalTime closeTime,
            LocalTime breakStart,
            LocalTime breakEnd,
            @NotNull(message = "휴무 여부는 필수입니다") Boolean closed) {
    }
}
