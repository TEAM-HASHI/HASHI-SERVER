package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 어드민 매거진 등록 요청. bannerKey는 presigned URL로 업로드 완료된 S3 object key다. */
public record CreateMagazineRequest(
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 500) String bannerKey,
        @NotBlank @Size(max = 255) String instagramRedirectUrl) {
}
