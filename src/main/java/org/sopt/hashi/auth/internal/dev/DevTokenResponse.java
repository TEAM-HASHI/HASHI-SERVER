package org.sopt.hashi.auth.internal.dev;

/** 개발용 토큰 발급 응답 — accessToken을 Swagger 우상단 Authorize에 붙여 쓴다. */
public record DevTokenResponse(
        String accessToken,
        String role,
        Long subjectId) {
}
