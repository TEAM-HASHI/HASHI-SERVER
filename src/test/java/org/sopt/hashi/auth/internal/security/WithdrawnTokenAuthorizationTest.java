package org.sopt.hashi.auth.internal.security;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
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
 * 보안 크리티컬(HTTP 인증 계층): 탈퇴해 블랙리스트에 오른 회원의 액세스 토큰은 서명·만료가 유효해도 401(AUTH-001)이어야 한다.
 * SecurityConfig + 필터 체인을 실제로 태워 검증한다(웹 슬라이스라 JPA·Redis 자동설정은 로딩하지 않음).
 */
@WebMvcTest(
        controllers = WithdrawnTokenAuthorizationTest.ProtectedTestController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        WithdrawnTokenAuthorizationTest.ProtectedTestController.class})
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
class WithdrawnTokenAuthorizationTest {

    private static final String PROTECTED_PATH = "/api/v1/reviews";

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtProvider jwtProvider;

    @MockitoBean
    OnboardingTokenStore onboardingTokenStore;

    @MockitoBean
    TokenBlacklist tokenBlacklist;

    @Test
    @DisplayName("블랙리스트에 오른 회원의 액세스 토큰은 401(AUTH-001)로 거부한다")
    void 탈퇴_회원_토큰_401() throws Exception {
        given(tokenBlacklist.isUserBlocked(7L)).willReturn(true);
        String accessToken = jwtProvider.createAccessToken(7L, AuthRoles.USER);

        mvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-001"));
    }

    @Test
    @DisplayName("블랙리스트에 없는 회원의 액세스 토큰은 그대로 통과한다")
    void 활성_회원_토큰_200() throws Exception {
        String accessToken = jwtProvider.createAccessToken(7L, AuthRoles.USER);

        mvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());

        verify(tokenBlacklist).isUserBlocked(7L);
    }

    @Test
    @DisplayName("어드민 토큰은 subject가 adminId라 블랙리스트를 대조하지 않는다")
    void 어드민_토큰_대조_생략() throws Exception {
        String adminToken = jwtProvider.createAccessToken(7L, AuthRoles.ADMIN);

        // 어드민은 회원 API 접근이 403이지만, 그 전에 블랙리스트 대조가 일어나지 않았는지가 요점이다
        mvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        verify(tokenBlacklist, never()).isUserBlocked(anyLong());
    }

    @RestController
    static class ProtectedTestController {
        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "ok";
        }
    }
}
