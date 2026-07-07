package org.sopt.hashi.auth.internal.security;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 브라우저가 관리하는 크리덴셜 쿠키 처리(리프레시·온보딩). HttpOnly·Secure로 내려 JS 접근을 차단하고,
 * path를 각 사용 경로로 제한해 불필요한 전송을 막는다. SameSite=None(교차 출처 프론트) — CORS allowCredentials와 짝.
 */
@Component
public class CookieUtil {

    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    public static final String SIGNUP_TOKEN_COOKIE = "signup_token";

    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    private static final String SIGNUP_COOKIE_PATH = "/api/v1/users/onboarding";

    private final JwtProperties properties;

    public CookieUtil(JwtProperties properties) {
        this.properties = properties;
    }

    /** 리프레시 토큰 쿠키 생성. */
    public ResponseCookie createRefreshTokenCookie(String refreshToken) {
        return baseCookieBuilder(REFRESH_TOKEN_COOKIE, refreshToken, REFRESH_COOKIE_PATH)
                .maxAge(properties.refreshTokenTtl())
                .build();
    }

    /** 리프레시 토큰 쿠키 만료(무효화 시 클라 쿠키 제거). */
    public ResponseCookie expireRefreshTokenCookie() {
        return baseCookieBuilder(REFRESH_TOKEN_COOKIE, "", REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
    }

    /** 온보딩 임시 토큰 쿠키 생성 — 온보딩 엔드포인트에만 전송된다. TTL은 Redis 임시 토큰과 동일. */
    public ResponseCookie createSignupTokenCookie(String signupToken) {
        return baseCookieBuilder(SIGNUP_TOKEN_COOKIE, signupToken, SIGNUP_COOKIE_PATH)
                .maxAge(properties.onboardingTokenTtl())
                .build();
    }

    /** 온보딩 임시 토큰 쿠키 만료(가입 완료로 소비된 뒤 클라 쿠키 제거). */
    public ResponseCookie expireSignupTokenCookie() {
        return baseCookieBuilder(SIGNUP_TOKEN_COOKIE, "", SIGNUP_COOKIE_PATH)
                .maxAge(0)
                .build();
    }

    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        return extract(request, REFRESH_TOKEN_COOKIE);
    }

    public Optional<String> extractSignupToken(HttpServletRequest request) {
        return extract(request, SIGNUP_TOKEN_COOKIE);
    }

    private Optional<String> extract(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookieBuilder(String name, String value, String path) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path(path);
    }
}
