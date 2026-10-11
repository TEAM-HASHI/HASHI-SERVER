package org.sopt.hashi.restaurant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest;
import org.sopt.hashi.restaurant.service.RestaurantMapPageService;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

@WebMvcTest(controllers = RestaurantMapPageController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        RestaurantMapExceptionHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "jwt.access-token-ttl=30m", "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.cors.allowed-origins=https://app.hashi.test",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class RestaurantMapPageFailureTest {

    private static final String FAILURE_DETAIL = "synthetic-private-sql SELECT private_menu "
            + "keyword=private-keyword latitude=35.123456 longitude=139.123456";
    private static final String CAUSE_DETAIL = "synthetic-private-cause jdbc:mysql://private-db "
            + "password=private-password apiKey=private-api-key";
    private static final String[] PRIVATE_VALUES = {"synthetic-private-sql", "SELECT private_menu",
            "private-keyword", "35.123456", "139.123456", "synthetic-private-cause", "private-db",
            "private-password", "private-api-key", "private-query-input"};

    @Autowired private MockMvc mvc;
    @MockitoBean private RestaurantMapPageService service;
    @MockitoBean private OnboardingTokenStore onboardingTokenStore;
    @MockitoBean private TokenBlacklist tokenBlacklist;

    @Test
    void 명시적인_빈_검색어는_기존_지도_계약대로_400이다() throws Exception {
        for (String keyword : new String[]{"", "   ", "\u00a0\u3000"}) {
            mvc.perform(get("/api/v1/restaurants/map")
                            .param("south", "0").param("north", "1")
                            .param("west", "0").param("east", "1")
                            .param("keyword", keyword))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("COMMON-400"))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }
        verifyNoInteractions(service);
    }

    @Test
    void 페이지_DB_접근실패는_503과_안전한_WARN만_남긴다() throws Exception {
        assertSafeFailure(new DataAccessResourceFailureException(
                FAILURE_DETAIL, new IllegalStateException(CAUSE_DETAIL)));
    }

    @Test
    void 페이지_transaction_시작실패는_503과_안전한_WARN만_남긴다() throws Exception {
        assertSafeFailure(new CannotCreateTransactionException(
                FAILURE_DETAIL, new IllegalStateException(CAUSE_DETAIL)));
    }

    private void assertSafeFailure(RuntimeException failure) throws Exception {
        given(service.getPage(any(RestaurantMapPageRequest.class), any(String.class))).willThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String body = mvc.perform(get("/api/v1/restaurants/map")
                            .param("south", "0").param("north", "1")
                            .param("west", "0").param("east", "1")
                            .param("keyword", "private-query-input"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("RESTAURANT-015"))
                    .andExpect(jsonPath("$.message").value("지도 정보를 조회할 수 없습니다."))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.path").value("/api/v1/restaurants/map"))
                    .andExpect(jsonPath("$.errors").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(PRIVATE_VALUES);
            assertThat(appender.list).filteredOn(event -> Level.WARN.equals(event.getLevel()))
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).isEqualTo(
                                "Map query failed. operation=restaurant-map-query exceptionType="
                                        + failure.getClass().getSimpleName());
                        assertThat(event.getArgumentArray()).containsExactly(failure.getClass().getSimpleName());
                    });
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_VALUES);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
