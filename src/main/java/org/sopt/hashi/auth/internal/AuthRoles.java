package org.sopt.hashi.auth.internal;

/**
 * 토큰 role 클레임에 담는 권한 문자열. 온보딩 임시 권한은 정식 사용자와 구분된다(auth.md §4).
 */
final class AuthRoles {

    static final String USER = "ROLE_USER";
    static final String ADMIN = "ROLE_ADMIN";
    static final String ONBOARDING = "ROLE_ONBOARDING";

    private AuthRoles() {
    }
}
