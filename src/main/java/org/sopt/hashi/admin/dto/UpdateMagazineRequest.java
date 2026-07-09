package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.Size;

/** 어드민 매거진 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다. */
public record UpdateMagazineRequest(
        @Size(max = 150) String title,
        @Size(max = 500) String bannerKey,
        @Size(max = 255) String instagramRedirectUrl) {
}
