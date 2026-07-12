package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * 어드민 식당 등록 요청. imageKeys·메뉴 imageKey는 presigned URL로 업로드 완료된 S3 object key다.
 * genre·foodCategory·curationTypes는 사용자 API와 같은 소문자 케밥 값이다.
 * businessHours는 7개 요일(MONDAY~SUNDAY)을 중복 없이 모두 포함해야 한다(시간은 "HH:mm").
 */
public record CreateRestaurantRequest(
        @Schema(description = "식당명", example = "야키니쿠 리키마루 이케부쿠로점")
        @NotBlank(message = "식당명은 필수입니다")
        @Size(max = 100, message = "식당명은 100자 이내입니다") String name,
        @Schema(description = "현지(일본어) 식당명", example = "焼肉力丸 池袋東口店")
        @NotBlank(message = "현지 식당명은 필수입니다")
        @Size(max = 100, message = "현지 식당명은 100자 이내입니다") String localName,
        @Schema(description = "한 줄 소개", example = "이케부쿠로의 인기 야키니쿠 전문점")
        @NotBlank(message = "한 줄 소개는 필수입니다")
        @Size(max = 100, message = "한 줄 소개는 100자 이내입니다") String summary,
        @Schema(description = "매장 상세 설명", example = "엄선된 고기와 다양한 코스를 제공합니다.")
        @NotBlank(message = "상세 설명은 필수입니다")
        @Size(max = 500, message = "상세 설명은 500자 이내입니다") String description,
        @Schema(description = "주소", example = "도쿄도 도시마구 히가시이케부쿠로 1-1-1")
        @NotBlank(message = "주소는 필수입니다")
        @Size(max = 255, message = "주소는 255자 이내입니다") String address,
        @Schema(description = "지역", example = "이케부쿠로")
        @NotBlank(message = "지역은 필수입니다")
        @Size(max = 20, message = "지역은 20자 이내입니다") String area,
        @Schema(description = "장르(소문자 케밥)", example = "sushi")
        @NotBlank(message = "장르는 필수입니다") String genre,
        @Schema(description = "음식 카테고리(소문자 케밥)", example = "sushi")
        @NotBlank(message = "음식 카테고리는 필수입니다") String foodCategory,
        @Schema(description = "통화 코드", example = "JPY")
        @NotBlank(message = "통화는 필수입니다")
        @Size(min = 3, max = 3, message = "통화는 3자리 코드여야 합니다") String priceCurrency,
        @Schema(description = "1인 최소 가격", example = "3000")
        @NotNull(message = "최소 가격은 필수입니다")
        @PositiveOrZero(message = "최소 가격은 0 이상입니다") BigDecimal minPrice,
        @Schema(description = "1인 최대 가격", example = "8000")
        @NotNull(message = "최대 가격은 필수입니다")
        @PositiveOrZero(message = "최대 가격은 0 이상입니다") BigDecimal maxPrice,
        @Schema(description = "식당 이미지 S3 key 목록", example = "[\"restaurants/a1b2c3-1.jpg\"]")
        @NotNull(message = "식당 이미지는 필수입니다")
        @Size(min = 1, message = "식당 이미지는 최소 1개 이상 필요합니다")
        List<@NotBlank(message = "이미지 키는 비어 있을 수 없습니다")
        @Size(max = 500, message = "이미지 키는 500자 이내입니다") String> imageKeys,
        List<@NotNull(message = "메뉴 항목은 null일 수 없습니다") @Valid MenuRequest> menus,
        @Schema(description = "해시태그 목록", example = "[\"현지인맛집\"]")
        @NotNull(message = "해시태그는 필수입니다")
        @Size(min = 1, message = "해시태그는 최소 1개 이상 필요합니다")
        List<@NotBlank(message = "해시태그는 비어 있을 수 없습니다")
        @Size(max = 20, message = "해시태그는 20자 이내입니다") String> hashtags,
        @Schema(description = "큐레이션 유형 목록(소문자 케밥, 선택)", example = "[\"sns-hot\"]")
        List<@NotBlank(message = "큐레이션 유형은 비어 있을 수 없습니다") String> curationTypes,
        @NotNull(message = "영업시간은 필수입니다")
        @Size(min = 7, max = 7, message = "영업시간은 모든 요일(7개)을 포함해야 합니다")
        List<@NotNull(message = "영업시간 항목은 null일 수 없습니다") @Valid BusinessHourRequest> businessHours) {

    /** 메뉴 항목 — 목록 전체가 함께 저장되므로 각 항목은 완전한 값으로 받는다. */
    public record MenuRequest(
            @Schema(description = "메뉴명", example = "특선 모둠 야키니쿠")
            @NotBlank(message = "메뉴명은 필수입니다")
            @Size(max = 100, message = "메뉴명은 100자 이내입니다") String name,
            @Schema(description = "메뉴 설명", example = "엄선한 부위 5종 모둠")
            @NotBlank(message = "메뉴 설명은 필수입니다")
            @Size(max = 500, message = "메뉴 설명은 500자 이내입니다") String description,
            @Schema(description = "메뉴 이미지 S3 key(선택)", example = "restaurant-menus/a1b2c3-menu.jpg")
            @Pattern(regexp = ".*\\S.*", message = "메뉴 이미지 키는 공백일 수 없습니다")
            @Size(max = 500, message = "메뉴 이미지 키는 500자 이내입니다") String imageKey,
            @Schema(description = "통화 코드", example = "JPY")
            @NotBlank(message = "통화는 필수입니다")
            @Size(min = 3, max = 3, message = "통화는 3자리 코드여야 합니다") String priceCurrency,
            @Schema(description = "가격", example = "4500")
            @NotNull(message = "가격은 필수입니다")
            @PositiveOrZero(message = "가격은 0 이상입니다") BigDecimal priceAmount,
            @Schema(description = "대표 메뉴 여부", example = "true")
            @NotNull(message = "대표 메뉴 여부는 필수입니다") Boolean main) {
    }

    /** 요일별 영업시간 — 휴무일(closed=true)은 시간 없이 보내고, 영업일은 openTime·closeTime이 필수다. */
    public record BusinessHourRequest(
            @Schema(description = "요일", example = "MONDAY")
            @NotNull(message = "요일은 필수입니다") DayOfWeek dayOfWeek,
            @Schema(description = "오픈 시각(HH:mm)", example = "11:00")
            LocalTime openTime,
            @Schema(description = "마감 시각(HH:mm)", example = "22:00")
            LocalTime closeTime,
            @Schema(description = "브레이크타임 시작 시각(HH:mm, 선택)", example = "15:00")
            LocalTime breakStart,
            @Schema(description = "브레이크타임 종료 시각(HH:mm, 선택)", example = "16:00")
            LocalTime breakEnd,
            @Schema(description = "휴무 여부", example = "false")
            @NotNull(message = "휴무 여부는 필수입니다") Boolean closed) {
    }
}
