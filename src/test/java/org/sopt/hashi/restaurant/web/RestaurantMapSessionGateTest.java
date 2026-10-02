package org.sopt.hashi.restaurant.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.security.JwtAccessDeniedHandler;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationEntryPoint;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.auth.internal.security.SecurityConfig;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.internal.map.MapCursorCodec;
import org.sopt.hashi.restaurant.internal.map.MapSessionId;
import org.sopt.hashi.restaurant.internal.map.MapSessionProperties;
import org.sopt.hashi.restaurant.internal.map.RedisMapSessionStore;
import org.sopt.hashi.restaurant.service.RestaurantMapPageReader;
import org.sopt.hashi.restaurant.service.RestaurantMapPageService;
import org.sopt.hashi.restaurant.service.RestaurantMapService;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 실제 Controller, Service, Codec, 설정 빈 및 SecurityFilterChain을 통과한다. */
@WebMvcTest(controllers = RestaurantMapPageController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        RestaurantMapExceptionHandler.class, GlobalExceptionHandler.class,
        RestaurantMapPageService.class, MapCursorCodec.class, RestaurantMapSessionGateTest.Configuration.class})
@EnableConfigurationProperties(MapSessionProperties.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m", "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.cors.allowed-origins=https://app.hashi.test",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class RestaurantMapSessionGateTest {
    private static final String PATH = "/api/v1/restaurants/map";
    @Autowired MockMvc mvc;
    @Autowired MapSessionProperties properties;
    @Autowired MapCursorCodec cursors;
    @MockitoBean RestaurantMapService mapService;
    @MockitoBean RestaurantMapPageReader reader;
    @MockitoBean RedisMapSessionStore store;
    @MockitoBean OnboardingTokenStore onboardingTokenStore;

    @BeforeEach
    void prepareValidKeyWithoutActivation() {
        properties.setEnabled(false);
        properties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @EnumSource(QueryMode.class)
    void 비활성화는_유효한_키와_정상요청도_DB와_Redis_호출전에_차단한다(QueryMode mode) throws Exception {
        assertUnavailable(request(mode));
        verifyNoInteractions(mapService, reader, store);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"synthetic-private-key!", "c2hvcnQ="})
    void 활성화해도_키가_없거나_무효이면_모든_조회모드가_503이다(String key) throws Exception {
        // 키 변경 전에 유효한 cursor를 만들어 설정 실패가 cursor 오류에 가려지지 않게 한다.
        var requests = new MockHttpServletRequestBuilder[]{request(QueryMode.NEW), request(QueryMode.NEXT), request(QueryMode.RESORT)};
        properties.setEnabled(true);
        properties.setSigningKey(key);
        for (var request : requests) assertUnavailable(request);
        verifyNoInteractions(mapService, reader, store);
    }

    @Test
    void 비활성화해도_잘못된_입력의_기존_400_경계를_유지한다() throws Exception {
        mvc.perform(get(PATH).param("cursor", ""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON-400"));
        verifyNoInteractions(mapService, reader, store);
    }

    private MockHttpServletRequestBuilder request(QueryMode mode) {
        var id = new MapSessionId(0, new UUID(0, 1));
        return switch (mode) {
            case NEW -> get(PATH).param("south", "0").param("north", "1").param("west", "0").param("east", "1");
            case NEXT -> get(PATH).param("cursor", cursors.encode(id, RestaurantMapSort.RECOMMEND, 10));
            case RESORT -> get(PATH).param("querySessionId", id.value()).param("sort", "rating");
        };
    }

    private void assertUnavailable(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RESTAURANT-014"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    enum QueryMode { NEW, NEXT, RESORT }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean("japanClock")
        Clock japanClock() { return Clock.systemUTC(); }
    }
}
