package org.sopt.hashi.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 어드민 식당 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다(값 비우기 불가).
 * 컬렉션은 전체 교체 의미다. null이면 유지하며, imageKeys·hashtags는 최소 1개를 유지해야 한다.
 * businessHours는 보낼 경우 7개 요일을 중복 없이 모두 포함해야 한다.
 */
public record UpdateRestaurantRequest(
        @Schema(description = "식당명(선택)", example = "야키니쿠 리키마루 이케부쿠로점")
        @Size(max = 100, message = "식당명은 100자 이내입니다") String name,
        @Schema(description = "현지(일본어) 식당명(선택, 공백 불가)", example = "焼肉力丸 池袋東口店")
        @Pattern(regexp = ".*\\S.*", message = "현지 식당명은 공백일 수 없습니다")
        @Size(max = 100, message = "현지 식당명은 100자 이내입니다") String localName,
        @Schema(description = "한 줄 소개(선택)", example = "이케부쿠로의 인기 야키니쿠 전문점")
        @Size(max = 100, message = "한 줄 소개는 100자 이내입니다") String summary,
        @Schema(description = "매장 상세 설명(선택)", example = "엄선된 고기와 다양한 코스를 제공합니다.")
        @Size(max = 500, message = "상세 설명은 500자 이내입니다") String description,
        @Schema(description = "주소(선택)", example = "도쿄도 도시마구 히가시이케부쿠로 1-1-1")
        @Size(max = 255, message = "주소는 255자 이내입니다") String address,
        @Schema(description = "지역(선택)", example = "이케부쿠로")
        @Size(max = 20, message = "지역은 20자 이내입니다") String area,
        @Schema(description = "장르(소문자 케밥, 선택)", example = "sushi")
        String genre,
        @Schema(description = "음식 카테고리(카드 표시용 자유 텍스트, 선택, 공백 불가)", example = "야키니쿠")
        @Pattern(regexp = ".*\\S.*", message = "음식 카테고리는 공백일 수 없습니다")
        @Size(max = 20, message = "음식 카테고리는 20자 이내입니다") String foodCategory,
        @Schema(description = "음식점 분류(restaurant·cafe·bar, 선택, 공백 불가)", example = "cafe")
        @Pattern(regexp = ".*\\S.*", message = "음식점 분류는 공백일 수 없습니다") String placeType,
        @Schema(description = "통화 코드(선택)", example = "JPY")
        @Size(min = 3, max = 3, message = "통화는 3자리 코드여야 합니다") String priceCurrency,
        @Schema(description = "1인 최소 가격(선택)", example = "3000")
        @PositiveOrZero(message = "최소 가격은 0 이상입니다") BigDecimal minPrice,
        @Schema(description = "1인 최대 가격(선택)", example = "8000")
        @PositiveOrZero(message = "최대 가격은 0 이상입니다") BigDecimal maxPrice,
        @Schema(description = "식당 이미지 S3 key 목록(선택) — 보내면 전체 교체, 최소 1개",
                example = "[\"restaurants/a1b2c3-1.jpg\"]")
        @Size(min = 1, message = "식당 이미지는 최소 1개 이상 필요합니다")
        List<@NotBlank(message = "이미지 키는 비어 있을 수 없습니다")
        @Size(max = 500, message = "이미지 키는 500자 이내입니다") String> imageKeys,
        @Schema(description = "식당 이미지 ordered wrapper(선택) — 보내면 전체 교체")
        @Size(min = 1, message = "식당 이미지는 최소 1개 이상 필요합니다")
        List<@NotNull(message = "식당 이미지 항목은 null일 수 없습니다") @Valid ImageRequest> images,
        @JsonProperty("imageAssetIds")
        @Schema(hidden = true)
        JsonNode unsupportedImageAssetIds,
        List<@NotNull(message = "메뉴 항목은 null일 수 없습니다") @Valid MenuRequest> menus,
        @Schema(description = "해시태그 목록(선택) — 보내면 전체 교체, 최소 1개", example = "[\"오마카세\"]")
        @Size(min = 1, message = "해시태그는 최소 1개 이상 필요합니다")
        List<@NotBlank(message = "해시태그는 비어 있을 수 없습니다")
        @Size(max = 20, message = "해시태그는 20자 이내입니다") String> hashtags,
        @Schema(description = "큐레이션 유형 목록(소문자 케밥, 선택) — 보내면 전체 교체", example = "[\"sns-hot\"]")
        List<@NotBlank(message = "큐레이션 유형은 비어 있을 수 없습니다") String> curationTypes,
        @Size(min = 7, max = 7, message = "영업시간은 모든 요일(7개)을 포함해야 합니다")
        List<@NotNull(message = "영업시간 항목은 null일 수 없습니다") @Valid BusinessHourRequest> businessHours) {

    @AssertTrue(message = "imageKeys와 images는 함께 사용할 수 없습니다")
    @JsonIgnore
    public boolean isImageCollectionSourceValid() {
        return imageKeys == null || images == null;
    }

    @AssertTrue(message = "식당 수정에서는 imageAssetIds를 사용할 수 없습니다")
    @JsonIgnore
    public boolean isUpdateImageContractValid() {
        return unsupportedImageAssetIds == null;
    }

    public record ImageRequest(
            @Positive(message = "식당 이미지 ID는 1 이상이어야 합니다") Long restaurantImageId,
            UUID imageAssetId
    ) {

        @AssertTrue(message = "restaurantImageId 또는 imageAssetId 중 하나만 필요합니다")
        @JsonIgnore
        public boolean isReferenceValid() {
            return (restaurantImageId == null) != (imageAssetId == null);
        }
    }

    /** 메뉴 항목 — 기존 메뉴는 menuId를, 신규 메뉴는 null을 전달한다. */
    public record MenuRequest(
            @Schema(description = "기존 메뉴 ID(신규 메뉴는 생략)", example = "10")
            @Positive(message = "메뉴 ID는 1 이상이어야 합니다") Long menuId,
            @Schema(description = "메뉴명", example = "특선 모둠 야키니쿠")
            @NotBlank(message = "메뉴명은 필수입니다")
            @Size(max = 100, message = "메뉴명은 100자 이내입니다") String name,
            @Schema(description = "메뉴 설명", example = "엄선한 부위 5종 모둠")
            @NotBlank(message = "메뉴 설명은 필수입니다")
            @Size(max = 500, message = "메뉴 설명은 500자 이내입니다") String description,
            @Schema(description = "메뉴 이미지 S3 key(선택)", example = "restaurant-menus/a1b2c3-menu.jpg")
            @Pattern(regexp = ".*\\S.*", message = "메뉴 이미지 키는 공백일 수 없습니다")
            @Size(max = 500, message = "메뉴 이미지 키는 500자 이내입니다") String imageKey,
            @Schema(description = "메뉴 이미지 asset ID(선택)")
            UUID imageAssetId,
            @Schema(description = "통화 코드", example = "JPY")
            @NotBlank(message = "통화는 필수입니다")
            @Size(min = 3, max = 3, message = "통화는 3자리 코드여야 합니다") String priceCurrency,
            @Schema(description = "가격", example = "4500")
            @NotNull(message = "가격은 필수입니다")
            @PositiveOrZero(message = "가격은 0 이상입니다") BigDecimal priceAmount,
            @Schema(description = "대표 메뉴 여부", example = "true")
            @NotNull(message = "대표 메뉴 여부는 필수입니다") Boolean main) {

        public MenuRequest(
                Long menuId,
                String name,
                String description,
                String imageKey,
                String priceCurrency,
                BigDecimal priceAmount,
                Boolean main
        ) {
            this(menuId, name, description, imageKey, null, priceCurrency, priceAmount, main);
        }

        @AssertTrue(message = "메뉴 이미지는 imageKey와 imageAssetId를 함께 사용할 수 없습니다")
        @JsonIgnore
        public boolean isImageSourceValid() {
            return imageKey == null || imageAssetId == null;
        }
    }

    /**
     * 요일별 영업시간 — 휴무일(closed=true)은 시간 없이 보내고, 영업일은 openTime·closeTime이 필수다.
     * 마감 시각이 오픈 시각보다 이르면 익일 마감(자정 넘김), 같으면 24시간 영업으로 해석한다.
     */
    public record BusinessHourRequest(
            @Schema(description = "요일", example = "MONDAY")
            @NotNull(message = "요일은 필수입니다") DayOfWeek dayOfWeek,
            @Schema(description = "오픈 시각(HH:mm)", example = "11:00")
            LocalTime openTime,
            @Schema(description = "마감 시각(HH:mm) — 오픈 시각보다 이르면 익일 마감, 같으면 24시간 영업", example = "22:00")
            LocalTime closeTime,
            @Schema(description = "브레이크타임 시작 시각(HH:mm, 선택)", example = "15:00")
            LocalTime breakStart,
            @Schema(description = "브레이크타임 종료 시각(HH:mm, 선택)", example = "16:00")
            LocalTime breakEnd,
            @Schema(description = "휴무 여부", example = "false")
            @NotNull(message = "휴무 여부는 필수입니다") Boolean closed) {
    }
}
