package org.sopt.hashi.admin.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.admin.service.AdminRestaurantService;
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
        controllers = AdminRestaurantController.class,
        excludeFilters = @Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {OnboardingJwtIssuer.class, JwtAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminRestaurantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminRestaurantService adminRestaurantService;

    @Test
    void 등록은_수정용_images_필드를_무시하지_않고_400으로_거부한다() throws Exception {
        UUID assetId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/restaurants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "하시 스시",
                                  "localName": "ハシ寿司",
                                  "summary": "한 줄 소개",
                                  "description": "상세 설명",
                                  "address": "도쿄도 시부야구",
                                  "area": "도쿄",
                                  "genre": "sushi",
                                  "foodCategory": "스시",
                                  "priceCurrency": "JPY",
                                  "minPrice": 1000,
                                  "maxPrice": 3000,
                                  "imageAssetIds": ["%s"],
                                  "images": [{"imageAssetId": "%s"}],
                                  "menus": [],
                                  "hashtags": ["스시"],
                                  "curationTypes": [],
                                  "businessHours": [
                                    {"dayOfWeek": "MONDAY", "closed": true},
                                    {"dayOfWeek": "TUESDAY", "closed": true},
                                    {"dayOfWeek": "WEDNESDAY", "closed": true},
                                    {"dayOfWeek": "THURSDAY", "closed": true},
                                    {"dayOfWeek": "FRIDAY", "closed": true},
                                    {"dayOfWeek": "SATURDAY", "closed": true},
                                    {"dayOfWeek": "SUNDAY", "closed": true}
                                  ]
                                }
                                """.formatted(assetId, assetId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));

        verifyNoInteractions(adminRestaurantService);
    }

    @Test
    void 수정은_등록용_imageAssetIds_필드를_무시하지_않고_400으로_거부한다() throws Exception {
        UUID retainedAssetId = UUID.randomUUID();
        UUID unsupportedAssetId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/admin/restaurants/{restaurantId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [{"imageAssetId": "%s"}],
                                  "imageAssetIds": ["%s"]
                                }
                                """.formatted(retainedAssetId, unsupportedAssetId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));

        verifyNoInteractions(adminRestaurantService);
    }
}
