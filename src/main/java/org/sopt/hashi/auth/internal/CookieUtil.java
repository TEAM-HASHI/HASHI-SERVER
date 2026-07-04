package org.sopt.hashi.auth.internal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 쿠키 처리. HttpOnly·Secure로 내려 JS 접근을 차단하고,
 * 재발급 경로(/api/v1/auth)에만 전송되도록 path를 제한한다.
 */
@Component
public class CookieUtil {

    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final JwtProperties properties;

    public CookieUtil(JwtProperties properties) {
        this.properties = properties;
    }

    /** 리프레시 토큰 쿠키 생성. SameSite=None(교차 출처 프론트) — CORS allowCredentials와 짝을 이룬다. */
    public ResponseCookie createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(properties.refreshTokenTtl())
                .build();
    }

    /** 리프레시 토큰 쿠키 만료(무효화 시 클라 쿠키 제거). */
    public ResponseCookie expireRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
    }

    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> REFRESH_TOKEN_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
