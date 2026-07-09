package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 어드민 매거진 등록 요청. bannerKey는 presigned URL로 업로드 완료된 S3 object key다. */
public record CreateMagazineRequest(
        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 150, message = "제목은 150자 이내입니다") String title,
        @NotBlank(message = "배너 이미지 키는 필수입니다")
        @Size(max = 500, message = "배너 이미지 키는 500자 이내입니다") String bannerKey,
        @NotBlank(message = "인스타그램 리다이렉트 URL은 필수입니다")
        @Size(max = 255, message = "인스타그램 리다이렉트 URL은 255자 이내입니다") String instagramRedirectUrl) {
}
