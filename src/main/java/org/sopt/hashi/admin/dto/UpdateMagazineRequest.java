package org.sopt.hashi.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 어드민 매거진 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다.
 * cardNews·hashtags·restaurantIds는 보내면 전체 교체하며(빈 목록은 모두 지움), 목록의 순서가 노출 순서다.
 */
public record UpdateMagazineRequest(
        @Schema(description = "매거진 제목(선택)", example = "이번 주 핫한 이자카야 7곳")
        @Size(max = 150, message = "제목은 150자 이내입니다") String title,
        @Schema(description = "새 배너 이미지 S3 key(선택, 보내면 교체)", example = "magazines/a1b2c3-new-banner.jpg")
        @Size(max = 500, message = "배너 이미지 키는 500자 이내입니다") String bannerKey,
        @Schema(description = "새 배너 이미지 public asset ID(선택)",
                example = "a3af06f1-4ef2-46f8-a489-2347fb840447")
        UUID bannerImageAssetId,
        @Schema(description = "새 썸네일 이미지 S3 key(선택, 보내면 교체)", example = "magazines/a1b2c3-new-thumbnail.jpg")
        @Size(max = 500, message = "썸네일 이미지 키는 500자 이내입니다") String thumbnailKey,
        @Schema(description = "새 썸네일 이미지 public asset ID(선택)",
                example = "c5f7c106-d92d-4823-a612-2e9221e8e689")
        UUID thumbnailImageAssetId,
        @Schema(description = "인스타그램 URL(선택)", example = "https://www.instagram.com/p/def456/")
        @Size(max = 255, message = "인스타그램 리다이렉트 URL은 255자 이내입니다") String instagramRedirectUrl,
        @Schema(description = "상세 본문(선택, 빈 문자열이면 본문을 지움)", example = "퇴근길에 들르기 좋은 이자카야를 모았습니다.")
        @Size(max = 2000, message = "본문은 2000자 이내입니다") String content,
        @Schema(description = "카드뉴스 이미지 목록(선택, 보내면 전체 교체·순서대로 노출)")
        List<@NotNull(message = "카드뉴스 항목은 null일 수 없습니다") @Valid MagazineCardNewsRequest> cardNews,
        @Schema(description = "해시태그 목록(선택, 보내면 전체 교체)", example = "[\"이자카야\", \"퇴근길\"]")
        List<@NotBlank(message = "해시태그는 비어 있을 수 없습니다")
        @Size(max = 20, message = "해시태그는 20자 이내입니다") String> hashtags,
        @Schema(description = "연결 식당 ID 목록(선택, 보내면 전체 교체·순서대로 노출)", example = "[1001, 1002]")
        List<@NotNull(message = "식당 ID는 null일 수 없습니다")
        @Positive(message = "식당 ID는 양수여야 합니다") Long> restaurantIds) {

    @AssertTrue(message = "bannerKey와 bannerImageAssetId는 함께 사용할 수 없습니다")
    @JsonIgnore
    public boolean isBannerImageSourceValid() {
        return optionalSingleSource(bannerKey, bannerImageAssetId);
    }

    @AssertTrue(message = "thumbnailKey와 thumbnailImageAssetId는 함께 사용할 수 없습니다")
    @JsonIgnore
    public boolean isThumbnailImageSourceValid() {
        return optionalSingleSource(thumbnailKey, thumbnailImageAssetId);
    }

    @AssertTrue(message = "연결 식당 ID는 중복될 수 없습니다")
    @JsonIgnore
    public boolean isRestaurantIdsUnique() {
        return restaurantIds == null || new HashSet<>(restaurantIds).size() == restaurantIds.size();
    }

    private boolean optionalSingleSource(String key, UUID assetId) {
        return (key == null || !key.isBlank()) && (key == null || assetId == null);
    }
}
