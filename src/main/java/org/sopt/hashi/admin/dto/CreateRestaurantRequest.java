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
 * 어드민 식당 등록 요청. thumbnailKey·imageKeys·메뉴 imageKey는 presigned URL로 업로드 완료된
 * S3 object key다. genre·curationTypes는 사용자 API와 같은 소문자 케밥 값("sushi", "sns-hot")이다.
 * businessHours는 7개 요일(MONDAY~SUNDAY)을 중복 없이 모두 포함해야 한다(시간은 "HH:mm").
 */
public record CreateRestaurantRequest(
        @Schema(description = "식당명", example = "야키니쿠 리키마루 이케부쿠로점")
        @NotBlank(message = "식당명은 필수입니다")
        @Size(max = 100, message = "식당명은 100자 이내입니다") String name,
        @Schema(description = "현지(일본어) 식당명(선택)", example = "焼肉力丸 池袋東口店")
        @Size(max = 100, message = "현지 식당명은 100자 이내입니다") String localName,
        @Schema(description = "한 줄 설명(선택)", example = "이케부쿠로의 인기 야키니쿠 전문점")
        @Size(max = 500, message = "설명은 500자 이내입니다") String description,
        @Schema(description = "매장 상세 설명(선택)")
        String storeDescription,
        @Schema(description = "주소", example = "도쿄도 도시마구 히가시이케부쿠로 1-1-1")
        @NotBlank(message = "주소는 필수입니다")
        @Size(max = 255, message = "주소는 255자 이내입니다") String address,
        @Schema(description = "지역(선택)", example = "이케부쿠로")
        @Size(max = 100, message = "지역은 100자 이내입니다") String area,
        @Schema(description = "장르(소문자 케밥)", example = "sushi")
        @NotBlank(message = "장르는 필수입니다") String genre,
        @Schema(description = "썸네일 이미지 S3 key(선택, 업로드 완료본)", example = "restaurants/a1b2c3-thumb.jpg")
        @Pattern(regexp = ".*\\S.*", message = "썸네일 이미지 키는 공백일 수 없습니다")
        @Size(max = 500, message = "썸네일 이미지 키는 500자 이내입니다") String thumbnailKey,
        @Schema(description = "예약 수수료", example = "4000")
        @NotNull(message = "예약 수수료는 필수입니다")
        @PositiveOrZero(message = "예약 수수료는 0 이상입니다") Long reservationFee,
        @Schema(description = "통화 코드", example = "JPY")
        @NotBlank(message = "통화는 필수입니다")
        @Size(max = 10, message = "통화는 10자 이내입니다") String currency,
        @Schema(description = "1인 최소 가격(선택)", example = "3000")
        @PositiveOrZero(message = "최소 가격은 0 이상입니다") BigDecimal minPrice,
        @Schema(description = "1인 최대 가격(선택)", example = "8000")
        @PositiveOrZero(message = "최대 가격은 0 이상입니다") BigDecimal maxPrice,
        @Schema(description = "식당 이미지 S3 key 목록(선택)", example = "[\"restaurants/a1b2c3-1.jpg\"]")
        List<@NotBlank(message = "이미지 키는 비어 있을 수 없습니다")
        @Size(max = 500, message = "이미지 키는 500자 이내입니다") String> imageKeys,
        List<@NotNull(message = "메뉴 항목은 null일 수 없습니다") @Valid MenuRequest> menus,
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
            @Schema(description = "메뉴 설명(선택)", example = "엄선한 부위 5종 모둠")
            @Size(max = 500, message = "메뉴 설명은 500자 이내입니다") String description,
            @Schema(description = "메뉴 이미지 S3 key(선택)", example = "restaurant-menus/a1b2c3-menu.jpg")
            @Pattern(regexp = ".*\\S.*", message = "메뉴 이미지 키는 공백일 수 없습니다")
            @Size(max = 500, message = "메뉴 이미지 키는 500자 이내입니다") String imageKey,
            @Schema(description = "통화 코드", example = "JPY")
            @NotBlank(message = "통화는 필수입니다")
            @Size(max = 10, message = "통화는 10자 이내입니다") String currency,
            @Schema(description = "가격(선택)", example = "4500")
            @PositiveOrZero(message = "가격은 0 이상입니다") BigDecimal price,
            @Schema(description = "대표 메뉴 여부", example = "true")
            @NotNull(message = "대표 메뉴 여부는 필수입니다") Boolean representative) {
    }

    /** 요일별 영업시간 — 휴무일(closed=true)은 시간 없이 보내고, 영업일은 openTime·closeTime이 필수다. */
    public record BusinessHourRequest(
            @Schema(description = "요일", example = "MONDAY")
            @NotNull(message = "요일은 필수입니다") DayOfWeek dayOfWeek,
            @Schema(description = "오픈 시각(HH:mm)", example = "11:00")
            LocalTime openTime,
            @Schema(description = "마감 시각(HH:mm)", example = "22:00")
            LocalTime closeTime,
            @Schema(description = "라스트 오더 시각(HH:mm, 선택)", example = "21:00")
            LocalTime lastOrderTime,
            @Schema(description = "휴무 여부", example = "false")
            @NotNull(message = "휴무 여부는 필수입니다") Boolean closed) {
    }
}
