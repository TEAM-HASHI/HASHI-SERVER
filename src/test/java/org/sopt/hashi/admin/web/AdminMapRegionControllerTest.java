package org.sopt.hashi.admin.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sopt.hashi.admin.service.AdminMapRegionService;
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
import org.sopt.hashi.restaurant.AdminMapRegionInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminMapRegionController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({AdminMapRegionService.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long", "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m", "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback", "hashi.cors.allowed-origins=https://app.hashi.test",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class AdminMapRegionControllerTest {
    private static final String PATH = "/api/v1/admin/map-regions";

    @Autowired MockMvc mvc;
    @Autowired JwtProvider tokens;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean OnboardingTokenStore onboardingTokenStore;
    @MockitoBean TokenBlacklist tokenBlacklist;

    @Test
    void 관리자_목록은_현재_페이지_크기와_구분한_전체_지역수를_totalCount로_반환한다() throws Exception {
        var draft = new AdminMapRegionInfo(12L, "TEST_AREA", "합성 지역",
                new BigDecimal("0.5"), new BigDecimal("0.5"),
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, 0, false);
        given(restaurants.getMapRegionsByAdmin(1, 2))
                .willReturn(new AdminMapRegionInfo.Page(List.of(draft), 1, 2, 3, 2));

        mvc.perform(get(PATH).param("page", "1").param("size", "2").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].mapRegionId").value(12))
                .andExpect(jsonPath("$.data.content[0].active").value(false))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").value(2));
        verify(restaurants).getMapRegionsByAdmin(1, 2);
    }

    @ParameterizedTest
    @CsvSource({"0, 20, 0, 0", "2, 2, 3, 2"})
    void 빈_페이지도_totalCount와_페이지_메타데이터를_유지한다(
            int page, int size, long totalCount, int totalPages) throws Exception {
        given(restaurants.getMapRegionsByAdmin(page, size))
                .willReturn(new AdminMapRegionInfo.Page(List.of(), page, size, totalCount, totalPages));

        mvc.perform(get(PATH).param("page", Integer.toString(page)).param("size", Integer.toString(size))
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(page))
                .andExpect(jsonPath("$.data.size").value(size))
                .andExpect(jsonPath("$.data.totalCount").value(totalCount))
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").value(totalPages));
        verify(restaurants).getMapRegionsByAdmin(page, size);
    }

    private String admin() {
        return "Bearer " + tokens.createAccessToken(1L, "ROLE_ADMIN");
    }
}
