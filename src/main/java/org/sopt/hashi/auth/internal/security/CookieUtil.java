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
    /**
     * 온보딩 제출(/api/v1/users/onboarding)과 인증 상태 조회(/api/v1/auth/me) 둘 다 커버해야 해서
     * 공통 프리픽스로 의도적으로 넓혔다(쿠키 Path는 1개뿐). 다른 API로 전송돼도 ROLE_ONBOARDING이라
     * SecurityConfig 역할 규칙이 403으로 차단한다 — 전송 범위는 넓게, 접근 범위는 서버가 정밀하게.
     */
    private static final String SIGNUP_COOKIE_PATH = "/api/v1";
    /**
     * 경로 확장 이전(/api/v1/users/onboarding 스코프)에 발급된 쿠키의 잔존 경로. 쿠키는 이름+Path
     * 조합으로 구분되어 같은 이름이 2개 공존할 수 있고, 브라우저는 긴 Path를 먼저 보내므로 낡은 토큰이
     * 선택될 수 있다 — 발급·만료 시 이 경로도 함께 만료시킨다(구 쿠키 TTL 30분 소진 후 제거 가능한 전환기 처리).
     */
    private static final String LEGACY_SIGNUP_COOKIE_PATH = "/api/v1/users/onboarding";

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

    /** 온보딩 임시 토큰 쿠키 생성 — 온보딩 제출·인증 상태 조회(/auth/me)에 전송된다. TTL은 Redis 임시 토큰과 동일. */
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

    /** 경로 확장 이전에 발급된 구경로 온보딩 쿠키 만료 — 같은 이름 쿠키 2개 공존(낡은 토큰 선택) 방지. */
    public ResponseCookie expireLegacySignupTokenCookie() {
        return baseCookieBuilder(SIGNUP_TOKEN_COOKIE, "", LEGACY_SIGNUP_COOKIE_PATH)
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
