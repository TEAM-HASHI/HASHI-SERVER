package org.sopt.hashi.restaurant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.security.JwtAccessDeniedHandler;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationEntryPoint;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.auth.internal.security.SecurityConfig;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapRegionSummary;
import org.sopt.hashi.restaurant.domain.RestaurantMapQueryRepository;
import org.sopt.hashi.restaurant.internal.map.MapQueryProperties;
import org.sopt.hashi.restaurant.service.RestaurantMapService;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

@WebMvcTest(controllers = RestaurantMapController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        RestaurantMapService.class, MapQueryProperties.class, RestaurantMapExceptionHandler.class,
        GlobalExceptionHandler.class, RestaurantMapControllerTest.Infrastructure.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m", "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.cors.allowed-origins=https://app.hashi.test",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false",
        "hashi.restaurant.map.initial-bounds.south=0", "hashi.restaurant.map.initial-bounds.north=1",
        "hashi.restaurant.map.initial-bounds.west=0", "hashi.restaurant.map.initial-bounds.east=1",
        "hashi.restaurant.map.supported-bounds.south=-1", "hashi.restaurant.map.supported-bounds.north=2",
        "hashi.restaurant.map.supported-bounds.west=-1", "hashi.restaurant.map.supported-bounds.east=2"
})
class RestaurantMapControllerTest {

    private static final Instant UNTIL = Instant.parse("2026-01-01T01:00:00.123456Z");

    @Autowired private MockMvc mvc;
    @MockitoBean private RestaurantMapQueryRepository repository;
    @MockitoBean private OnboardingTokenStore onboardingTokenStore;

    @Test
    void 익명_관광지역_응답은_실제_보안필터와_wrapper_직렬화_no_store를_지킨다() throws Exception {
        given(repository.findActiveRegions(any())).willReturn(List.of(new MapRegionSummary(1L, "합성 지역",
                new BigDecimal(".5"), new BigDecimal(".5"), MapQueryBounds.parse("0", "1", "0", "1"), 0, 0, 0)));
        mvc.perform(get("/api/v1/restaurants/map/regions"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.message").value("요청에 성공했습니다"))
                .andExpect(jsonPath("$.data.initialBounds.south").value(0))
                .andExpect(jsonPath("$.data.queryLimits.maxLatitudeSpan").value(1))
                .andExpect(jsonPath("$.data.queryLimits.supportedBounds.north").value(2))
                .andExpect(jsonPath("$.data.regions[0].restaurantCount").value(0))
                .andExpect(jsonPath("$.data.regions[0].mapRegionId").value(1))
                .andExpect(jsonPath("$.data.regions[0].clusterPosition.latitude").value(.5))
                .andExpect(jsonPath("$.data.regions[0].outsideBoundsCount").doesNotExist());
    }

    @Test
    void 선택식당은_좌표와_UTC_만료시각만_노출하고_엔티티_내부값을_노출하지_않는다() throws Exception {
        given(repository.findActiveMapInfos(any(), any())).willReturn(List.of(new RestaurantMapInfo(1L,
                "합성 식당", "restaurant", "sushi", new LocationInfo(BigDecimal.ZERO, BigDecimal.ZERO, UNTIL))));
        String body = mvc.perform(get("/api/v1/restaurants/1/map-location"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.restaurantId").value(1))
                .andExpect(jsonPath("$.data.location.latitude").value(0))
                .andExpect(jsonPath("$.data.location.validUntil").value(UNTIL.toString()))
                .andExpect(jsonPath("$.data.name").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("requestId", "addressRevision", "obtainedAt", "lockVersion", "source", "lease");
    }

    @Test
    void 삭제나_없는_식당은_404이고_현재위치만_없는_식당은_409이다() throws Exception {
        given(repository.findActiveMapInfos(any(), any())).willReturn(List.of());
        mvc.perform(get("/api/v1/restaurants/1/map-location")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESTAURANT-004"))
                .andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.errors").doesNotExist());
        given(repository.findActiveMapInfos(any(), any())).willReturn(List.of(
                new RestaurantMapInfo(1L, "합성 식당", "cafe", "etc", null)));
        mvc.perform(get("/api/v1/restaurants/1/map-location")).andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RESTAURANT-018"));
    }

    @Test
    void 지역설정_미완료와_DB_실패는_503_코드로_구분한다() throws Exception {
        given(repository.findActiveRegions(any())).willReturn(List.of());
        mvc.perform(get("/api/v1/restaurants/map/regions")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESTAURANT-017"));
        given(repository.findActiveRegions(any())).willThrow(new DataAccessResourceFailureException("synthetic-private-detail"));
        String body = mvc.perform(get("/api/v1/restaurants/map/regions")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RESTAURANT-015"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/restaurants/map/regions"))
                .andExpect(jsonPath("$.errors").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("synthetic-private-detail");
    }

    @Test
    void 잘못된_ID는_400이며_지도_페이지_임시_API를_제공하지_않는다() throws Exception {
        mvc.perform(get("/api/v1/restaurants/0/map-location")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));
        mvc.perform(get("/api/v1/restaurants/bad/map-location")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/restaurants/map")).andExpect(status().isNotFound());
    }

    @Test
    void 기존_보호경로의_익명_접근은_계속_차단한다() throws Exception {
        mvc.perform(get("/api/v1/admin/restaurants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void transaction_시작_실패도_지도전용_503으로_변환하고_원인을_노출하지_않는다() throws Exception {
        given(repository.findActiveMapInfos(any(), any()))
                .willThrow(new CannotCreateTransactionException("synthetic-private-connection"));
        String body = mvc.perform(get("/api/v1/restaurants/1/map-location"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RESTAURANT-015"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("synthetic-private-connection");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean("japanClock")
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
