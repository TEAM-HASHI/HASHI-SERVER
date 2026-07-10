package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.Size;

/** 어드민 매거진 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다. */
public record UpdateMagazineRequest(
        @Size(max = 150, message = "제목은 150자 이내입니다") String title,
        @Size(max = 500, message = "배너 이미지 키는 500자 이내입니다") String bannerKey,
        @Size(max = 255, message = "인스타그램 리다이렉트 URL은 255자 이내입니다") String instagramRedirectUrl) {
}
