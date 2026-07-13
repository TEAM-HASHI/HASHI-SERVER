package org.sopt.hashi.restaurant.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.dto.RestaurantMainResponse;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = RestaurantController.class,
        excludeFilters = @Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {OnboardingJwtIssuer.class, JwtAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class RestaurantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestaurantService restaurantService;

    @Test
    void 랜덤_추천_요청이면_현재_식당을_제외하고_공통_성공_응답을_반환한다() throws Exception {
        RestaurantMainResponse response = createResponse(2L);
        given(restaurantService.getRandomRestaurantRecommendation(1L)).willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants/recommendations/random")
                        .param("excludeRestaurantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.restaurantId").value(2))
                .andExpect(jsonPath("$.data.name").value("히마와리 스시"));

        verify(restaurantService).getRandomRestaurantRecommendation(1L);
    }

    @Test
    void 최초_랜덤_추천은_제외할_식당_ID_없이_요청할_수_있다() throws Exception {
        RestaurantMainResponse response = createResponse(1L);
        given(restaurantService.getRandomRestaurantRecommendation(null)).willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants/recommendations/random"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantId").value(1));

        verify(restaurantService).getRandomRestaurantRecommendation(null);
    }

    @Test
    void 제외할_식당_ID가_양수가_아니면_공통_검증_에러를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/recommendations/random")
                        .param("excludeRestaurantId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(restaurantService);
    }

    @Test
    void 랜덤_추천_후보가_없으면_식당_도메인_에러를_반환한다() throws Exception {
        given(restaurantService.getRandomRestaurantRecommendation(1L))
                .willThrow(new BusinessException(RestaurantErrorCode.RECOMMENDATION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/restaurants/recommendations/random")
                        .param("excludeRestaurantId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESTAURANT-008"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private RestaurantMainResponse createResponse(Long restaurantId) {
        String thumbnailUrl = "https://cdn.example.com/restaurants/%d/thumbnail.jpg".formatted(restaurantId);
        return new RestaurantMainResponse(
                restaurantId,
                "히마와리 스시",
                "ひまわり寿司",
                BigDecimal.valueOf(4.8),
                100L,
                "장인의 스시를 즐겨보세요.",
                "초밥",
                "도쿄도 신주쿠구",
                thumbnailUrl,
                List.of(thumbnailUrl),
                4_000L
        );
    }
}
