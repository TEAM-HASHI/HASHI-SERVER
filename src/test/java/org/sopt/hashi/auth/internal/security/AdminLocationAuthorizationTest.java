package org.sopt.hashi.auth.internal.security;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.admin.service.AdminRestaurantService;
import org.sopt.hashi.admin.web.AdminRestaurantController;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminRestaurantController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({AdminRestaurantService.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long", "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m", "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback", "hashi.cors.allowed-origins=https://app.hashi.test",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class AdminLocationAuthorizationTest {
    private static final String PATH = "/api/v1/admin/restaurants/1/location";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider tokens;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean OnboardingTokenStore onboardingTokenStore;

    @Test
    void ADMIN은_실제_controller_service_port로_상태와_재처리_계약을_사용한다() throws Exception {
        var state = new RestaurantLocationInfo(1L, "PENDING", 3, null, 0, null, null, false);
        given(restaurants.getLocationByAdmin(1L)).willReturn(state);
        given(restaurants.retryLocationByAdmin(1L, 3)).willReturn(state);
        mvc.perform(get(PATH).header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.addressRevision").value(3))
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.address").doesNotExist());
        mvc.perform(post(PATH + "/retry").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAddressRevision\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON-200"));
        verify(restaurants).retryLocationByAdmin(1L, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ONBOARDING", "ANONYMOUS"})
    void 일반회원과_온보딩과_익명은_조회와_재처리_모두_차단한다(String role) throws Exception {
        var read = get(PATH);
        var retry = post(PATH + "/retry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAddressRevision\":1}");
        if (!role.equals("ANONYMOUS")) {
            String token = role.equals("ONBOARDING") ? tokens.createOnboardingToken(1L)
                    : tokens.createAccessToken(1L, "ROLE_USER");
            read.header("Authorization", "Bearer " + token);
            retry.header("Authorization", "Bearer " + token);
        }
        int expected = role.equals("ANONYMOUS") ? 401 : 403;
        mvc.perform(read).andExpect(status().is(expected));
        mvc.perform(retry).andExpect(status().is(expected));
        verifyNoInteractions(restaurants);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedAddressRevision\":null}", "{\"expectedAddressRevision\":-1}",
            "{\"expectedAddressRevision\":\"bad\"}"})
    void 누락과_음수와_잘못된_revision은_기존_400_봉투로_거부한다(String body) throws Exception {
        mvc.perform(post(PATH + "/retry").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"));
        verifyNoInteractions(restaurants);
    }

    @Test
    void revision과_READY_충돌은_RESTAURANT019를_유지한다() throws Exception {
        given(restaurants.retryLocationByAdmin(1L, 1))
                .willThrow(new BusinessException(RestaurantErrorCode.LOCATION_RETRY_CONFLICT));
        mvc.perform(post(PATH + "/retry").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAddressRevision\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESTAURANT-019"));
    }

    private String admin() {
        return "Bearer " + tokens.createAccessToken(1L, "ROLE_ADMIN");
    }
}
