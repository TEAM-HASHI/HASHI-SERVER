package org.sopt.hashi.auth.internal.web;

/**
 * 현재 인증 주체 응답 — 액세스 토큰의 컨텍스트만 담는다(auth는 도메인 모듈을 되참조할 수 없다, auth.md §1).
 * role은 USER·ADMIN·ONBOARDING 3값으로, 클라이언트는 이 값 하나로 진입 라우팅을 분기한다(401 = 미로그인).
 * subjectId는 USER면 userId, ADMIN이면 adminId, ONBOARDING이면 null이다 —
 * 온보딩 토큰의 subject는 내부 식별자(kakaoId)라 노출하지 않는다.
 */
public record AuthMeResponse(Long subjectId, String role) {

    private static final String ROLE_PREFIX = "ROLE_";

    /** 시큐리티 권한 문자열(ROLE_USER 등)에서 클라이언트 친화적인 역할명(USER 등)으로 변환한다. */
    public static AuthMeResponse of(Long subjectId, String authority) {
        String role = authority.startsWith(ROLE_PREFIX)
                ? authority.substring(ROLE_PREFIX.length())
                : authority;
        return new AuthMeResponse(subjectId, role);
    }
}
