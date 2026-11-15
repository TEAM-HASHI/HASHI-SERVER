package org.sopt.hashi.auth.internal.security;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;
import org.sopt.hashi.review.dto.RestaurantPhotosResponse;
import org.sopt.hashi.review.service.RestaurantPhotoService;
import org.sopt.hashi.review.web.RestaurantPhotoController;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = RestaurantPhotoController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long", "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m", "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.com/callback", "hashi.cors.allowed-origins=https://app.hashi.com",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class RestaurantPhotoHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean OnboardingTokenStore onboardingTokenStore;
    @MockitoBean TokenBlacklist tokenBlacklist;
    @MockitoBean RestaurantPhotoService service;

    @Test
    void 비회원_기본_조회와_빈_결과는_200이다() throws Exception {
        given(service.getPhotos(1, RestaurantPhotoFilter.ALL, null)).willReturn(new RestaurantPhotosResponse(
                1, RestaurantPhotoFilter.ALL, new RestaurantPhotosResponse.Counts(0, 0, 0, 0), 0, List.of(), null, false));
        mvc.perform(get("/api/v1/restaurants/1/photos"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.filter").value("ALL"))
                .andExpect(jsonPath("$.data.photos").isEmpty())
                .andExpect(jsonPath("$.data.counts.all").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 비회원_리뷰사진_추가조회는_필터와_커서를_전달하고_사진과_다음커서를_반환한다() throws Exception {
        List<RestaurantPhotosResponse.Photo> photos = IntStream.rangeClosed(21, 40)
                .mapToObj(id -> new RestaurantPhotosResponse.Photo("REVIEW:" + id,
                        RestaurantPhotoFilter.REVIEW, "https://cdn.example.com/reviews/" + id + ".jpg", null))
                .toList();
        given(service.getPhotos(7, RestaurantPhotoFilter.REVIEW, "previous-cursor")).willReturn(new RestaurantPhotosResponse(
                7, RestaurantPhotoFilter.REVIEW, new RestaurantPhotosResponse.Counts(46, 2, 3, 41),
                41, photos, "next-cursor", true));

        mvc.perform(get("/api/v1/restaurants/7/photos").param("filter", "REVIEW").param("cursor", "previous-cursor"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.restaurantId").value(7))
                .andExpect(jsonPath("$.data.filter").value("REVIEW"))
                .andExpect(jsonPath("$.data.counts.all").value(46))
                .andExpect(jsonPath("$.data.counts.representative").value(2))
                .andExpect(jsonPath("$.data.counts.menu").value(3))
                .andExpect(jsonPath("$.data.counts.review").value(41))
                .andExpect(jsonPath("$.data.totalCount").value(41))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.photos.length()").value(20))
                .andExpect(jsonPath("$.data.photos[0].photoId").value("REVIEW:21"))
                .andExpect(jsonPath("$.data.photos[0].type").value("REVIEW"))
                .andExpect(jsonPath("$.data.photos[0].imageUrl").value("https://cdn.example.com/reviews/21.jpg"))
                .andExpect(jsonPath("$.data.photos[0].image").isEmpty())
                .andExpect(jsonPath("$.data.photos[19].photoId").value("REVIEW:40"));
        verify(service).getPhotos(7, RestaurantPhotoFilter.REVIEW, "previous-cursor");
    }

    @Test
    void 잘못된_필터_과도한커서_식당ID는_공통400이다() throws Exception {
        for (String query : List.of("?filter=VIDEO", "?cursor=" + "a".repeat(513))) {
            mvc.perform(get("/api/v1/restaurants/1/photos" + query))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"));
        }
        mvc.perform(get("/api/v1/restaurants/0/photos"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"));
        verifyNoInteractions(service);
    }

    @Test
    void 삭제_미존재_식당의_404는_빈결과와_구분한다() throws Exception {
        given(service.getPhotos(1, RestaurantPhotoFilter.ALL, null))
                .willThrow(new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND));
        mvc.perform(get("/api/v1/restaurants/1/photos"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESTAURANT-004"));
    }
}
