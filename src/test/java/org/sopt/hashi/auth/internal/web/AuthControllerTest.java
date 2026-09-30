package org.sopt.hashi.auth.internal.web;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.security.SecurityConfig;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationEntryPoint;
import org.sopt.hashi.auth.internal.security.JwtAccessDeniedHandler;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.UserAuthService;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 보안 크리티컬(HTTP 계층): 로그아웃은 액세스 토큰 없이 리프레시 쿠키만으로 호출되고(permitAll),
 * 성공 응답은 리프레시 쿠키를 만료(Max-Age=0)시켜 브라우저에서 제거해야 한다.
 * SecurityConfig 인가 규칙 + 필터 체인을 실제로 태워 검증한다(웹 슬라이스라 JPA·Redis 자동설정은 로딩하지 않음).
 */
@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d",
        "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.com/callback",
        "hashi.cors.allowed-origins=https://app.hashi.com",
        "hashi.cors.allowed-origin-patterns=https://hashi-client-*-example-team.vercel.app",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class AuthControllerTest {

    private static final String ALLOWED_ORIGIN = "https://app.hashi.com";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserAuthService userAuthService;

    // JwtAuthenticationFilter 의존 — 로그아웃 요청은 Bearer 없이 오므로 온보딩 토큰 대조는 일어나지 않는다.
    @MockitoBean
    OnboardingTokenStore onboardingTokenStore;

    @Test
    @DisplayName("로그아웃 성공 시 세션을 폐기하고 리프레시 쿠키를 만료(Max-Age=0)시킨다")
    void 로그아웃_성공_리프레시_쿠키_만료() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "userRefresh"))
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AUTH-205"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        startsWith(CookieUtil.REFRESH_TOKEN_COOKIE + "=;"),
                        containsString("Path=/api/v1/auth"),
                        containsString("Max-Age=0"))));

        verify(userAuthService).logout("userRefresh");
    }
}
