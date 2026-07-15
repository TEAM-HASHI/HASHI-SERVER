package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 어드민 매거진 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다. */
public record UpdateMagazineRequest(
        @Schema(description = "매거진 제목(선택)", example = "이번 주 핫한 이자카야 7곳")
        @Size(max = 150, message = "제목은 150자 이내입니다") String title,
        @Schema(description = "새 배너 이미지 S3 key(선택, 보내면 교체)", example = "magazines/a1b2c3-new-banner.jpg")
        @Size(max = 500, message = "배너 이미지 키는 500자 이내입니다") String bannerKey,
        @Schema(description = "새 썸네일 이미지 S3 key(선택, 보내면 교체)", example = "magazines/a1b2c3-new-thumbnail.jpg")
        @Size(max = 500, message = "썸네일 이미지 키는 500자 이내입니다") String thumbnailKey,
        @Schema(description = "인스타그램 URL(선택)", example = "https://www.instagram.com/p/def456/")
        @Size(max = 255, message = "인스타그램 리다이렉트 URL은 255자 이내입니다") String instagramRedirectUrl) {
}
