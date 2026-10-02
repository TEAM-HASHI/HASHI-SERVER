package org.sopt.hashi.auth.internal.security;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.service.RestaurantSaveSummaryService;
import org.sopt.hashi.user.collection.web.RestaurantSaveSummaryController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 실제 Controller/Service/USER 판정과 SecurityFilterChain을 통과한다. DB/restaurant 구현만 모킹한다. */
@WebMvcTest(controllers = RestaurantSaveSummaryController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        CurrentUserProviderImpl.class, RestaurantSaveSummaryService.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long", "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m", "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.com/callback", "hashi.cors.allowed-origins=https://app.hashi.com",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class RestaurantSaveSummaryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @MockitoBean OnboardingTokenStore onboardingTokenStore;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean SavedRestaurantRepository saved;

    @BeforeEach
    void setUp() {
        given(restaurants.findActiveCards(anyCollection())).willReturn(List.of(
                new RestaurantCardInfo(1L, "식당", "도쿄", "sushi", "restaurant", BigDecimal.ZERO, 0, null)));
    }

    @Test
    void 익명_집계는_공개이고_개인_조회는_인증이_필요하다() throws Exception {
        mvc.perform(get("/api/v1/restaurants/save-counts").param("restaurantIds", "1"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.restaurants[0].saveCount").value(0));
        mvc.perform(get("/api/v1/users/me/restaurant-saves").param("restaurantIds", "1"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("COMMON-401"));
    }

    @Test
    void USER만_내_저장_조회가_가능하고_ADMIN과_ONBOARDING은_403이다() throws Exception {
        mvc.perform(get("/api/v1/users/me/restaurant-saves").param("restaurantIds", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(7L, AuthRoles.USER)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.restaurants[0].saved").value(false));
        for (String token : List.of(jwt.createAccessToken(7L, AuthRoles.ADMIN), jwt.createOnboardingToken(7L))) {
            mvc.perform(get("/api/v1/users/me/restaurant-saves").param("restaurantIds", "1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void 잘못된_ID는_공통_400으로_응답한다() throws Exception {
        for (String value : List.of("", "1,1", "0", "-1", "abc", "1,,2")) {
            mvc.perform(get("/api/v1/restaurants/save-counts").param("restaurantIds", value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"));
        }
        mvc.perform(get("/api/v1/restaurants/save-counts"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"));
    }
}
