package org.sopt.hashi.auth.internal.app;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.security.JwtAccessDeniedHandler;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationEntryPoint;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.auth.internal.security.SecurityConfig;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;

@WebMvcTest(controllers = AppAuthController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class, CookieUtil.class,
        OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long", "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m", "kakao.client-id=test",
        "kakao.redirect-uri=https://web.example/callback", "hashi.cors.allowed-origins=https://web.example",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class AppAuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @MockitoBean AppAuthService service;
    @MockitoBean OnboardingTokenStore onboarding;
    @MockitoBean TokenBlacklist blacklist;

    @Test
    void 앱_재발급과_로그아웃은_본문을_받고_웹_쿠키를_변경하지_않는다() throws Exception {
        when(service.reissue("refresh")).thenReturn(new AppAuthService.AppTokenResponse("a", "r", 1800, 1209600));
        mvc.perform(post("/api/v1/auth/app/reissue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value("a"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mvc.perform(post("/api/v1/auth/app/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("AUTH-205"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        org.mockito.Mockito.verify(service).logout("refresh");
    }

    @Test
    void 앱_access로_코드를_발급하고_짧은_challenge는_거절한다() throws Exception {
        String access = jwt.createSessionAccessToken(7L, "01234567-1234-1234-1234-012345678901", "app");
        when(service.issueCode(anyString(), anyString())).thenReturn(new AppAuthService.WebviewCodeResponse("code", 60));
        mvc.perform(post("/api/v1/auth/webview/code").header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"codeChallenge\":\"" + "a".repeat(43) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresIn").value(60))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mvc.perform(post("/api/v1/auth/webview/code").header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"codeChallenge\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 앱_로그인은_본문_토큰이고_쿠키는_설정하지_않는다() throws Exception {
        when(service.login("sdk")).thenReturn(new AppAuthService.AppLoginResponse(true,
                new AppAuthService.AppTokenResponse("access", "refresh", 1800, 1209600)));
        mvc.perform(post("/api/v1/auth/app/kakao/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kakaoAccessToken\":\"sdk\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokens.refreshToken").value("refresh"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void 교환은_허용된_Origin에서_리프레시_쿠키만_설정한다() throws Exception {
        when(service.exchange(anyString(), anyString())).thenReturn("web-refresh");
        mvc.perform(post("/api/v1/auth/webview/exchange").header(HttpHeaders.ORIGIN, "https://web.example")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("HttpOnly"),
                        containsString("Secure"), containsString("SameSite=None"), containsString("Path=/api/v1/auth"))));
    }

    @Test
    void 교환은_Origin_누락과_다른_사이트를_거절한다() throws Exception {
        mvc.perform(post("/api/v1/auth/webview/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(exchangeBody())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/webview/exchange").header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 코드_발급은_인증없이_또는_관리자_권한으로_호출할_수_없다() throws Exception {
        String body = "{\"codeChallenge\":\"" + "a".repeat(43) + "\"}";
        mvc.perform(post("/api/v1/auth/webview/code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/webview/code").header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwt.createAccessToken(7L, AuthRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void 검증에_실패한_토큰은_응답에_노출되지_않는다() throws Exception {
        mvc.perform(post("/api/v1/auth/app/reissue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + "secret".repeat(700) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rejectedValue").isEmpty())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    private String exchangeBody() {
        return "{\"code\":\"" + "a".repeat(43) + "\",\"codeVerifier\":\"" + "v".repeat(43) + "\"}";
    }
}
