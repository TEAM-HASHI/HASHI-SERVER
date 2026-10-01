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



@WebMvcTest(
        controllers = {org.sopt.hashi.support.web.NoticeController.class, org.sopt.hashi.admin.web.AdminNoticeController.class},
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
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
class NoticeAuthorizationTest {

    private static final String NOTICE_PATH = "/api/v1/notices/1";
    private static final String ADMIN_PATH = "/api/v1/admin/notices/1";

    @Autowired
    MockMvc mvc;

    @Autowired JwtProvider jwtProvider;
    @MockitoBean org.sopt.hashi.support.service.NoticeService noticeService;
    @MockitoBean org.sopt.hashi.admin.service.AdminNoticeService adminNoticeService;
    @MockitoBean
    OnboardingTokenStore onboardingTokenStore;

    @Test
    @DisplayName("비회원은 공지 상세를 조회한다")
    void 비회원_공개조회() throws Exception {
        mvc.perform(get(NOTICE_PATH))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비회원의 보호 API 접근은 계속 차단한다")
    void 비회원_관리요청차단() throws Exception {
        mvc.perform(get(ADMIN_PATH))
                .andExpect(status().isUnauthorized());
    }


    @Test
    void 회원은_관리API에_접근하지_못하고_ADMIN은_허용한다() throws Exception {
        mvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, "ROLE_USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void 공개허용은_GET에만_적용한다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/notices/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_입력검증은_필드오류와_400을_반환한다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/notices")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, "ROLE_ADMIN"))
                .contentType("application/json").content("{\"title\":\"\",\"body\":[],\"imageAssetIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errors").isArray());
    }

    @Test
    void 공개상세에서_초안은_404봉투를_반환한다() throws Exception {
        org.mockito.Mockito.when(noticeService.detail(1L)).thenThrow(
                new org.sopt.hashi.shared.error.BusinessException(
                        org.sopt.hashi.support.code.SupportErrorCode.NOTICE_NOT_FOUND));
        mvc.perform(get(NOTICE_PATH)).andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SUPPORT-400"));
    }
    @Test
    void 잘못된_날짜cursor도_공개GET에서_400봉투로_반환한다() throws Exception {
        org.mockito.Mockito.when(noticeService.list("YmFkfDE")).thenAnswer(invocation ->
                org.sopt.hashi.support.domain.NoticeCursor.parse(invocation.getArgument(0)));
        mvc.perform(get("/api/v1/notices").param("cursor", "YmFkfDE"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SUPPORT-402"));
    }
}
