package org.sopt.hashi.auth.internal.security;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보안 크리티컬(HTTP 인가 계층): 온보딩 임시 토큰(ROLE_ONBOARDING)은 온보딩 외 보호 API에 접근하면 403이어야 한다.
 * SecurityConfig 인가 규칙 + 필터 체인을 실제로 태워 검증한다(웹 슬라이스라 JPA·Redis 자동설정은 로딩하지 않음).
 */
@WebMvcTest(
        controllers = OnboardingAuthorizationTest.ProtectedTestController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        OnboardingAuthorizationTest.ProtectedTestController.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d",
        "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.com/callback",
        "hashi.cors.allowed-origins=https://app.hashi.com",
        "hashi.cors.allowed-origin-patterns=https://hashi-client-*-gyeongbinmins-projects.vercel.app",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class OnboardingAuthorizationTest {

    private static final String PROTECTED_PATH = "/api/v1/reviews";
    private static final String ALLOWED_ORIGIN = "https://app.hashi.com";
    private static final String ALLOWED_PREVIEW_ORIGIN =
            "https://hashi-client-iqr83xez1-gyeongbinmins-projects.vercel.app";

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtProvider jwtProvider;

    // 온보딩 토큰의 Redis 대조는 통과시킨다 — 여기서 보려는 건 "인가(403)" 판정이다.
    @MockitoBean
    OnboardingTokenStore onboardingTokenStore;

    @Test
    @DisplayName("온보딩 토큰으로 온보딩 외 보호 API에 접근하면 403을 반환한다")
    void 온보딩_토큰_보호API_403() throws Exception {
        String onboardingToken = jwtProvider.createOnboardingToken(555L);

        mvc.perform(get(PROTECTED_PATH)
                        .cookie(new Cookie(CookieUtil.SIGNUP_TOKEN_COOKIE, onboardingToken))
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰 없이 보호 API에 접근하면 401을 반환한다")
    void 무토큰_보호API_401() throws Exception {
        mvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정식 USER 액세스 토큰이면 보호 API 접근이 허용된다(경로가 열려 있고 role만 문제였음을 확인)")
    void 정식_USER_토큰_보호API_200() throws Exception {
        String accessToken = jwtProvider.createAccessToken(7L, AuthRoles.USER);

        mvc.perform(get(PROTECTED_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("허용 패턴과 일치하는 Preview Origin의 preflight 요청은 통과한다")
    void Preview_Origin_preflight_통과() throws Exception {
        mvc.perform(options(PROTECTED_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_PREVIEW_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_PREVIEW_ORIGIN));
    }

    @Test
    @DisplayName("허용 패턴과 다른 Vercel Origin의 preflight 요청은 거부한다")
    void 다른_Vercel_Origin_preflight_거부() throws Exception {
        mvc.perform(options(PROTECTED_PATH)
                        .header(HttpHeaders.ORIGIN,
                                "https://other-project-preview-gyeongbinmins-projects.vercel.app")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ProtectedTestController {
        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "ok";
        }
    }
}
