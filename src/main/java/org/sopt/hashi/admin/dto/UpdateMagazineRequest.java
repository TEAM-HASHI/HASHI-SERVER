package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 어드민 매거진 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다. */
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
        @Size(max = 255, message = "인스타그램 리다이렉트 URL은 255자 이내입니다") String instagramRedirectUrl) {

    public UpdateMagazineRequest(
            String title,
            String bannerKey,
            String thumbnailKey,
            String instagramRedirectUrl
    ) {
        this(title, bannerKey, null, thumbnailKey, null, instagramRedirectUrl);
    }

    @AssertTrue(message = "bannerKey와 bannerImageAssetId는 함께 사용할 수 없습니다")
    public boolean isBannerImageSourceValid() {
        return optionalSingleSource(bannerKey, bannerImageAssetId);
    }

    @AssertTrue(message = "thumbnailKey와 thumbnailImageAssetId는 함께 사용할 수 없습니다")
    public boolean isThumbnailImageSourceValid() {
        return optionalSingleSource(thumbnailKey, thumbnailImageAssetId);
    }

    private boolean optionalSingleSource(String key, UUID assetId) {
        return (key == null || !key.isBlank()) && (key == null || assetId == null);
    }
}
