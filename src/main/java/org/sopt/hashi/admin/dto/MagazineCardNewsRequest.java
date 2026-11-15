package org.sopt.hashi.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 카드뉴스 이미지 1장 — legacy key 또는 READY public asset ID 중 정확히 하나를 받는다. 목록의 순서가 노출 순서다. */
public record MagazineCardNewsRequest(
        @Schema(description = "카드뉴스 이미지 S3 key(업로드 완료본)", example = "magazines/a1b2c3-card-1.jpg")
        @Size(max = 500, message = "카드뉴스 이미지 키는 500자 이내입니다") String imageKey,
        @Schema(description = "카드뉴스 이미지 public asset ID(purpose=MAGAZINE_CARD_NEWS)",
                example = "0b9d6c52-7c0e-4b7a-9a59-3f4f2f1f6c11")
        UUID imageAssetId) {

    @AssertTrue(message = "카드뉴스 이미지는 imageKey 또는 imageAssetId 중 하나가 필요합니다")
    @JsonIgnore
    public boolean isImageSourceValid() {
        return (imageKey == null || !imageKey.isBlank()) && ((imageKey == null) != (imageAssetId == null));
    }
}
