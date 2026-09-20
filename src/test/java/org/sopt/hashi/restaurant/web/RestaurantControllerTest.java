package org.sopt.hashi.restaurant.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.restaurant.RestaurantImageInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMainResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuDetailResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuListResponse;
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
    void 식당_목록_조회_조건을_서비스에_전달한다() throws Exception {
        RestaurantListResponse response = new RestaurantListResponse(List.of(), null, false);
        given(restaurantService.getRestaurants("sushi", "sushi", "popular", "sns-hot", null, 20))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants")
                        .param("keyword", "sushi")
                        .param("genre", "sushi")
                        .param("sort", "popular")
                        .param("type", "sns-hot")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(restaurantService)
                .getRestaurants("sushi", "sushi", "popular", "sns-hot", null, 20);
    }

    @Test
    void 식당_요약은_stable_association과_반응형_이미지_계약을_직렬화한다() throws Exception {
        UUID assetId = UUID.randomUUID();
        String imageUrl = "https://cdn.example.com/media/hero.webp";
        MediaImage.Source source = new MediaImage.Source(
                imageUrl, 860, 512, "image/webp");
        MediaImage heroImage = new MediaImage(
                assetId,
                MediaImageRole.RESTAURANT_HERO,
                MediaImageStatus.READY,
                source,
                List.of(new MediaImage.SourceSet(
                        "image/webp",
                        List.of(new MediaImage.Candidate(imageUrl, 860, 512))
                ))
        );
        String thumbnailUrl = "https://cdn.example.com/media/thumbnail.webp";
        MediaImage thumbnailImage = new MediaImage(
                assetId,
                MediaImageRole.RESTAURANT_THUMBNAIL,
                MediaImageStatus.READY,
                new MediaImage.Source(thumbnailUrl, 192, 192, "image/webp"),
                List.of(new MediaImage.SourceSet(
                        "image/webp",
                        List.of(new MediaImage.Candidate(thumbnailUrl, 192, 192))
                ))
        );
        RestaurantImageInfo thumbnailInfo = new RestaurantImageInfo(101L, 1, thumbnailImage, null);
        RestaurantImageInfo heroInfo = new RestaurantImageInfo(101L, 1, heroImage, null);
        RestaurantMainResponse response = new RestaurantMainResponse(
                1L,
                "히마와리 스시",
                "ひまわり寿司",
                BigDecimal.valueOf(4.8),
                100L,
                "장인의 스시를 즐겨보세요.",
                "초밥",
                "도쿄도 신주쿠구",
                thumbnailUrl,
                thumbnailInfo,
                List.of(imageUrl),
                List.of(heroInfo),
                4_000L
        );
        given(restaurantService.getRestaurantSummary(1L)).willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/summary", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thumbnailImage.restaurantImageId").value(101))
                .andExpect(jsonPath("$.data.thumbnailImage.displayOrder").value(1))
                .andExpect(jsonPath("$.data.thumbnailImage.image.assetId")
                        .value(assetId.toString()))
                .andExpect(jsonPath("$.data.thumbnailImage.image.role")
                        .value("RESTAURANT_THUMBNAIL"))
                .andExpect(jsonPath("$.data.thumbnailImage.image.status").value("READY"))
                .andExpect(jsonPath("$.data.heroImages[0].image.defaultSource.url")
                        .value(imageUrl))
                .andExpect(jsonPath("$.data.heroImages[0].image.sourceSets[0].candidates[0].width")
                        .value(860));

        verify(restaurantService).getRestaurantSummary(1L);
    }

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

    @Test
    void 메뉴_상세_조회는_다른_메뉴_개수를_포함한_공통_성공_응답을_반환한다() throws Exception {
        RestaurantMenuDetailResponse response = new RestaurantMenuDetailResponse(
                10L,
                "시오라멘",
                "맑은 닭 육수 라멘",
                "https://cdn.example.com/restaurant-menus/10.jpg",
                "JPY",
                1_000L,
                true,
                6L
        );
        given(restaurantService.getRestaurantMenu(1L, 10L)).willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/menus/{menuId}", 1L, 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.menuId").value(10))
                .andExpect(jsonPath("$.data.name").value("시오라멘"))
                .andExpect(jsonPath("$.data.otherMenuCount").value(6));

        verify(restaurantService).getRestaurantMenu(1L, 10L);
    }

    @Test
    void 메뉴_ID가_양수가_아니면_공통_검증_에러를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/menus/{menuId}", 1L, 0L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(restaurantService);
    }

    @Test
    void 메뉴를_찾을_수_없으면_식당_도메인_에러를_반환한다() throws Exception {
        given(restaurantService.getRestaurantMenu(1L, 999L))
                .willThrow(new BusinessException(RestaurantErrorCode.MENU_NOT_FOUND));

        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/menus/{menuId}", 1L, 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESTAURANT-009"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 메뉴_목록은_선택한_메뉴를_제외해_조회한다() throws Exception {
        RestaurantMenuListResponse response = new RestaurantMenuListResponse(List.of(), null, false);
        given(restaurantService.getRestaurantMenus(1L, 10L, null, 5)).willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/menus", 1L)
                        .param("excludeMenuId", "10")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(restaurantService).getRestaurantMenus(1L, 10L, null, 5);
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
