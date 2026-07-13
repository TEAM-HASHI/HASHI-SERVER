package org.sopt.hashi.auth.internal.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = PublicRestaurantAuthorizationTest.TestController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        PublicRestaurantAuthorizationTest.TestController.class})
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
class PublicRestaurantAuthorizationTest {

    private static final String PUBLIC_RESTAURANT_PATH = "/api/v1/restaurants/1/menus/10";
    private static final String PROTECTED_PATH = "/api/v1/reviews/authorization-test";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OnboardingTokenStore onboardingTokenStore;

    @Test
    @DisplayName("비회원은 식당 메뉴 상세 API에 접근할 수 있다")
    void guestCanAccessRestaurantMenuDetail() throws Exception {
        mvc.perform(get(PUBLIC_RESTAURANT_PATH))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비회원의 보호 API 접근은 계속 차단한다")
    void guestCannotAccessProtectedApi() throws Exception {
        mvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestController {

        @GetMapping(PUBLIC_RESTAURANT_PATH)
        String publicRestaurantEndpoint() {
            return "ok";
        }

        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "ok";
        }
    }
}
