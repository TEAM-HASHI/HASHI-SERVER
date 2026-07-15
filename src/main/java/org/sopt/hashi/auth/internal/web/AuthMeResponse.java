package org.sopt.hashi.auth.internal.web;

/**
 * 현재 인증 상태 응답. role은 USER·ADMIN·ONBOARDING 3값(401 = 미로그인).
 * subjectId는 USER면 userId, ADMIN이면 adminId, ONBOARDING이면 null이다.
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
