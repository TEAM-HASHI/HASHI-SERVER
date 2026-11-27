package org.sopt.hashi.user.recent;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
import org.sopt.hashi.restaurant.RestaurantMainInfo;
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
        controllers = RestaurantDetailViewController.class,
        excludeFilters = @Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {OnboardingJwtIssuer.class, JwtAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class RestaurantDetailViewControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RestaurantDetailViewService detail;

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
        RestaurantMainInfo response = new RestaurantMainInfo(
                1L,
                "히마와리 스시",
                "ひまわり寿司",
                BigDecimal.valueOf(4.8),
                100L,
                12L,
                "장인의 스시를 즐겨보세요.",
                "초밥",
                "도쿄도 신주쿠구",
                thumbnailUrl,
                thumbnailInfo,
                List.of(imageUrl),
                List.of(heroInfo),
                4_000L
        );
        given(detail.getSummary(1L, true)).willReturn(response);

        mockMvc.perform(get("/api/v1/restaurants/{restaurantId}/summary", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saveCount").value(12))
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

        verify(detail).getSummary(1L, true);
        mockMvc.perform(head("/api/v1/restaurants/1/summary")).andExpect(status().isOk());
        verify(detail).getSummary(1L, false);
    }

}
