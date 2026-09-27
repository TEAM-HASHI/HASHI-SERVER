package org.sopt.hashi.admin.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.sopt.hashi.admin.service.AdminMagazineService;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = AdminMagazineController.class,
        excludeFilters = @Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {OnboardingJwtIssuer.class, JwtAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminMagazineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminMagazineService adminMagazineService;

    @Test
    void 등록은_동일_슬롯의_key와_asset_ID를_함께_받으면_400으로_거부한다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/magazines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "이번 주 매거진",
                                  "bannerKey": "magazines/banner.jpg",
                                  "bannerImageAssetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
                                  "thumbnailKey": "magazines/thumbnail.jpg",
                                  "instagramRedirectUrl": "https://www.instagram.com/p/test/"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));

        verifyNoInteractions(adminMagazineService);
    }

    @Test
    void 수정도_동일_슬롯의_key와_asset_ID를_함께_받으면_400으로_거부한다() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/magazines/{magazineId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "thumbnailKey": "magazines/thumbnail.jpg",
                                  "thumbnailImageAssetId": "a3af06f1-4ef2-46f8-a489-2347fb840447"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));

        verifyNoInteractions(adminMagazineService);
    }
}
