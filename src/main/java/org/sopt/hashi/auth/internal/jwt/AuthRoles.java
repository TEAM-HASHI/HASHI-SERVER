package org.sopt.hashi.auth.internal.jwt;

/**
 * 토큰 role 클레임에 담는 권한 문자열. 온보딩 임시 권한은 정식 사용자와 구분된다(auth.md §4).
 */
public final class AuthRoles {

    public static final String USER = "ROLE_USER";
    public static final String ADMIN = "ROLE_ADMIN";
    public static final String ONBOARDING = "ROLE_ONBOARDING";

    private AuthRoles() {
    }
}
