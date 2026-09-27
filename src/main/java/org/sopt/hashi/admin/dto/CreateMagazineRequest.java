package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 어드민 매거진 등록 요청. 각 슬롯은 legacy key 또는 public asset ID 중 하나를 받는다. */
public record CreateMagazineRequest(
        @Schema(description = "매거진 제목", example = "이번 주 핫한 이자카야 5곳")
        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 150, message = "제목은 150자 이내입니다") String title,
        @Schema(description = "배너 이미지 S3 key(업로드 완료본)", example = "magazines/a1b2c3-banner.jpg")
        @Size(max = 500, message = "배너 이미지 키는 500자 이내입니다") String bannerKey,
        @Schema(description = "배너 이미지 public asset ID",
                example = "a3af06f1-4ef2-46f8-a489-2347fb840447")
        UUID bannerImageAssetId,
        @Schema(description = "썸네일 이미지 S3 key(업로드 완료본)", example = "magazines/a1b2c3-thumbnail.jpg")
        @Size(max = 500, message = "썸네일 이미지 키는 500자 이내입니다") String thumbnailKey,
        @Schema(description = "썸네일 이미지 public asset ID",
                example = "c5f7c106-d92d-4823-a612-2e9221e8e689")
        UUID thumbnailImageAssetId,
        @Schema(description = "배너 탭 시 이동할 인스타그램 URL", example = "https://www.instagram.com/p/abc123/")
        @NotBlank(message = "인스타그램 리다이렉트 URL은 필수입니다")
        @Size(max = 255, message = "인스타그램 리다이렉트 URL은 255자 이내입니다") String instagramRedirectUrl) {

    public CreateMagazineRequest(
            String title,
            String bannerKey,
            String thumbnailKey,
            String instagramRedirectUrl
    ) {
        this(title, bannerKey, null, thumbnailKey, null, instagramRedirectUrl);
    }

    @AssertTrue(message = "배너 이미지는 bannerKey 또는 bannerImageAssetId 중 하나가 필요합니다")
    public boolean isBannerImageSourceValid() {
        return exactlyOneSource(bannerKey, bannerImageAssetId);
    }

    @AssertTrue(message = "썸네일 이미지는 thumbnailKey 또는 thumbnailImageAssetId 중 하나가 필요합니다")
    public boolean isThumbnailImageSourceValid() {
        return exactlyOneSource(thumbnailKey, thumbnailImageAssetId);
    }

    private boolean exactlyOneSource(String key, UUID assetId) {
        return (key == null || !key.isBlank()) && ((key == null) != (assetId == null));
    }
}
