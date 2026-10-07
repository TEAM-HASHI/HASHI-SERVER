package org.sopt.hashi.auth.internal.security;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
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
        controllers = {org.sopt.hashi.support.web.TermsController.class, org.sopt.hashi.admin.web.AdminTermsController.class},
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
class TermsAuthorizationTest {

    private static final String TERMS_PATH = "/api/v1/terms/1";
    private static final String ADMIN_PATH = "/api/v1/admin/terms/1";

    @Autowired
    MockMvc mvc;

    @Autowired JwtProvider jwtProvider;
    @MockitoBean org.sopt.hashi.support.service.TermsService termsService;
    @MockitoBean org.sopt.hashi.admin.service.AdminTermsService adminTermsService;
    @MockitoBean
    OnboardingTokenStore onboardingTokenStore;

    @Test
    @DisplayName("비회원은 약관 상세를 조회한다")
    void 비회원_공개조회() throws Exception {
        org.mockito.Mockito.when(termsService.currentDetail(1L)).thenReturn(new org.sopt.hashi.support.TermsInfo(
                1L, org.sopt.hashi.support.TermsType.SERVICE_TERMS, "이용약관", "1",
                java.time.LocalDate.of(2026, 10, 1), java.util.List.of(), "CURRENT", null));
        mvc.perform(get(TERMS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsId").value(1))
                .andExpect(jsonPath("$.data.status").value("CURRENT"));
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
    void 관리자_이력은_page_size와_전체건수를_반환한다() throws Exception {
        org.mockito.Mockito.when(adminTermsService.history(org.sopt.hashi.support.TermsType.SERVICE_TERMS, 1, 20))
                .thenReturn(new org.sopt.hashi.admin.dto.AdminTermsListResponse(java.util.List.of(), 1, 20, 22, 2));
        mvc.perform(get("/api/v1/admin/terms").param("type", "SERVICE_TERMS").param("page", "1").param("size", "20")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalCount").value(22))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        org.mockito.Mockito.verify(adminTermsService).history(org.sopt.hashi.support.TermsType.SERVICE_TERMS, 1, 20);
    }

    @Test
    void 공개허용은_GET에만_적용한다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/terms/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_입력검증은_필드오류와_400을_반환한다() throws Exception {
        mvc.perform(post("/api/v1/admin/terms")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, "ROLE_ADMIN"))
                .contentType("application/json").content("""
                        {"type":null,"title":"","version":"","effectiveDate":null,"clauses":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.errors[?(@.field == 'type')].reason", hasItem("약관 유형은 필수입니다")))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')].reason", hasItem("약관 제목은 필수입니다")))
                .andExpect(jsonPath("$.errors[?(@.field == 'version')].reason", hasItem("약관 버전은 필수입니다")))
                .andExpect(jsonPath("$.errors[?(@.field == 'effectiveDate')].reason",
                        hasItem("약관 시행일은 필수입니다")))
                .andExpect(jsonPath("$.errors[?(@.field == 'clauses')].reason",
                        hasItem("약관 조항은 최소 1개 이상 필요합니다")));
        verifyNoInteractions(adminTermsService);
    }

    @Test
    void 관리자_입력의_길이와_형식_위반은_한국어_필드오류를_반환한다() throws Exception {
        String clauses = String.join(",", Collections.nCopies(201, "{\"heading\":\"조항\",\"content\":\"내용\"}"));

        mvc.perform(post("/api/v1/admin/terms")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, "ROLE_ADMIN"))
                .contentType("application/json").content("""
                        {"type":"SERVICE_TERMS","title":"%s","version":"v 1",
                        "effectiveDate":"2026-10-03","clauses":[%s]}
                        """.formatted("가".repeat(101), clauses)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')].reason", hasItem("약관 제목은 100자 이내입니다")))
                .andExpect(jsonPath("$.errors[?(@.field == 'version')].reason",
                        hasItem("약관 버전은 영문 또는 숫자로 시작하고 영문, 숫자, 점, 밑줄, 하이픈으로 50자 이내여야 합니다")))
                .andExpect(jsonPath("$.errors[?(@.field == 'clauses')].reason",
                        hasItem("약관 조항은 최대 200개까지 등록할 수 있습니다")));
        verifyNoInteractions(adminTermsService);
    }

    @Test
    void 관리자_입력의_null_조항은_한국어_필드오류를_반환한다() throws Exception {
        mvc.perform(post("/api/v1/admin/terms")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, "ROLE_ADMIN"))
                .contentType("application/json").content("""
                        {"type":"SERVICE_TERMS","title":"이용약관","version":"v1",
                        "effectiveDate":"2026-10-03","clauses":[null]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.errors[?(@.field == 'clauses[0]')].reason",
                        hasItem("약관 조항은 null일 수 없습니다")));
        verifyNoInteractions(adminTermsService);
    }

    @Test
    void 공개상세에서_초안은_404봉투를_반환한다() throws Exception {
        org.mockito.Mockito.when(termsService.currentDetail(1L)).thenThrow(
                new org.sopt.hashi.shared.error.BusinessException(
                        org.sopt.hashi.support.code.SupportErrorCode.TERMS_NOT_FOUND));
        mvc.perform(get(TERMS_PATH)).andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SUPPORT-004"));
    }
}
