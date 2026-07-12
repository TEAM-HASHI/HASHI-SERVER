package org.sopt.hashi.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * 어드민 식당 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다(값 비우기 불가).
 * 컬렉션은 전체 교체 의미다. null이면 유지하며, imageKeys·hashtags는 최소 1개를 유지해야 한다.
 * businessHours는 보낼 경우 7개 요일을 중복 없이 모두 포함해야 한다.
 */
public record UpdateRestaurantRequest(
        @Size(max = 100, message = "식당명은 100자 이내입니다") String name,
        @Pattern(regexp = ".*\\S.*", message = "현지 식당명은 공백일 수 없습니다")
        @Size(max = 100, message = "현지 식당명은 100자 이내입니다") String localName,
        @Size(max = 100, message = "한 줄 소개는 100자 이내입니다") String summary,
        @Size(max = 500, message = "상세 설명은 500자 이내입니다") String description,
        @Size(max = 255, message = "주소는 255자 이내입니다") String address,
        @Size(max = 20, message = "지역은 20자 이내입니다") String area,
        String genre,
        String foodCategory,
        @Size(min = 3, max = 3, message = "통화는 3자리 코드여야 합니다") String priceCurrency,
        @PositiveOrZero(message = "최소 가격은 0 이상입니다") BigDecimal minPrice,
        @PositiveOrZero(message = "최대 가격은 0 이상입니다") BigDecimal maxPrice,
        @Size(min = 1, message = "식당 이미지는 최소 1개 이상 필요합니다")
        List<@NotBlank(message = "이미지 키는 비어 있을 수 없습니다")
        @Size(max = 500, message = "이미지 키는 500자 이내입니다") String> imageKeys,
        List<@NotNull(message = "메뉴 항목은 null일 수 없습니다") @Valid MenuRequest> menus,
        @Size(min = 1, message = "해시태그는 최소 1개 이상 필요합니다")
        List<@NotBlank(message = "해시태그는 비어 있을 수 없습니다")
        @Size(max = 20, message = "해시태그는 20자 이내입니다") String> hashtags,
        List<@NotBlank(message = "큐레이션 유형은 비어 있을 수 없습니다") String> curationTypes,
        @Size(min = 7, max = 7, message = "영업시간은 모든 요일(7개)을 포함해야 합니다")
        List<@NotNull(message = "영업시간 항목은 null일 수 없습니다") @Valid BusinessHourRequest> businessHours) {

    /** 메뉴 항목 — 목록 전체 교체 단위라 수정 요청이라도 각 항목은 완전한 값으로 받는다. */
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
