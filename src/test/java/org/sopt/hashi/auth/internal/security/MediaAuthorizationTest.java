package org.sopt.hashi.auth.internal.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = MediaAuthorizationTest.MediaTestController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        MediaAuthorizationTest.MediaTestController.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d",
        "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.com/callback",
        "hashi.cors.allowed-origins=https://app.hashi.com",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class MediaAuthorizationTest {

    private static final String MEDIA_PATH = "/api/v1/media/assets";
    private static final String ALLOWED_ORIGIN = "https://app.hashi.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OnboardingTokenStore onboardingTokenStore;

    @Test
    void USER는_media_API에_접근할_수_있다() throws Exception {
        assertAccessTokenAllowed(AuthRoles.USER, 1L);
    }

    @Test
    void ADMIN은_media_API에_접근할_수_있다() throws Exception {
        assertAccessTokenAllowed(AuthRoles.ADMIN, 1L);
    }

    @Test
    void ONBOARDING은_media_API에_접근할_수_있다() throws Exception {
        String token = jwtProvider.createOnboardingToken(555L);

        mvc.perform(post(MEDIA_PATH)
                        .cookie(new Cookie(CookieUtil.SIGNUP_TOKEN_COOKIE, token))
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk());
    }

    @Test
    void 미인증_요청은_media_API에_접근할_수_없다() throws Exception {
        mvc.perform(post(MEDIA_PATH))
                .andExpect(status().isUnauthorized());
    }

    private void assertAccessTokenAllowed(String role, Long subjectId) throws Exception {
        String token = jwtProvider.createAccessToken(subjectId, role);

        mvc.perform(post(MEDIA_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @RestController
    static class MediaTestController {

        @PostMapping(MEDIA_PATH)
        String createAsset() {
            return "ok";
        }
    }
}
