package org.sopt.hashi.auth.internal.security;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.admin.service.AdminRestaurantService;
import org.sopt.hashi.admin.web.AdminRestaurantController;
import org.sopt.hashi.auth.AdminAccountPort;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.RestaurantLocationPreviewCommand;
import org.sopt.hashi.restaurant.RestaurantLocationReviewInfo;
import org.sopt.hashi.restaurant.RestaurantLocationReviewPage;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.RestaurantPlacesSearchInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminRestaurantController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({AdminRestaurantService.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        CookieUtil.class, OriginValidator.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long", "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m", "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback", "hashi.cors.allowed-origins=https://app.hashi.test",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class AdminLocationAuthorizationTest {
    private static final String PATH = "/api/v1/admin/restaurants/1/location";
    private static final String LIST_PATH = "/api/v1/admin/restaurants/locations";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider tokens;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean AdminAccountPort admins;
    @MockitoBean OnboardingTokenStore onboardingTokenStore;
    @MockitoBean TokenBlacklist tokenBlacklist;

    @Test
    void 관리자_상태_응답에는_서버가_조회한_확인자_로그인ID를_표시한다() throws Exception {
        given(restaurants.getLocationByAdmin(1L)).willReturn(new RestaurantLocationInfo(
                1L, "PENDING", 2, null, "PLACE_DETAILS", null, 0, null, null, false,
                "ADMIN_PLACE_SELECTION", 7L, null, 2L));
        given(admins.findLoginIds(Set.of(7L))).willReturn(Map.of(7L, "hashi_admin"));

        mvc.perform(get(PATH).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.confirmedByAdminId").value(7))
                .andExpect(jsonPath("$.data.confirmedByAdminLoginId").value("hashi_admin"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 3})
    void ADMIN은_실제_controller_service_port로_상태와_재처리_계약을_사용한다(long revision) throws Exception {
        var state = new RestaurantLocationInfo(1L, "PENDING", revision, null, "GEOCODING",
                null, 0, null, null, false);
        given(restaurants.getLocationByAdmin(1L)).willReturn(state);
        given(restaurants.retryLocationByAdmin(1L, revision)).willReturn(state);
        mvc.perform(get(PATH).header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.addressRevision").value((int) revision))
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.address").doesNotExist());
        mvc.perform(post(PATH + "/retry").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAddressRevision\":" + revision + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON-200"));
        verify(restaurants).retryLocationByAdmin(1L, revision);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ONBOARDING", "ANONYMOUS"})
    void 일반회원과_온보딩과_익명은_조회와_재처리_모두_차단한다(String role) throws Exception {
        var read = get(PATH);
        var retry = post(PATH + "/retry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAddressRevision\":1}");
        if (!role.equals("ANONYMOUS")) {
            String token = role.equals("ONBOARDING") ? tokens.createOnboardingToken(1L)
                    : tokens.createAccessToken(1L, "ROLE_USER");
            read.header("Authorization", "Bearer " + token);
            retry.header("Authorization", "Bearer " + token);
        }
        int expected = role.equals("ANONYMOUS") ? 401 : 403;
        mvc.perform(read).andExpect(status().is(expected));
        mvc.perform(retry).andExpect(status().is(expected));
        verifyNoInteractions(restaurants, admins);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {} | 주소 버전은 필수입니다
            {"expectedAddressRevision":null} | 주소 버전은 필수입니다
            {"expectedAddressRevision":-1} | 주소 버전은 0 이상입니다
            """)
    void 누락과_null과_음수_revision은_400과_한국어_필드_메시지로_거부한다(String body, String reason) throws Exception {
        mvc.perform(post(PATH + "/retry").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다"))
                .andExpect(jsonPath("$.path").value(PATH + "/retry"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("expectedAddressRevision"))
                .andExpect(jsonPath("$.errors[0].reason").value(reason));
        verifyNoInteractions(restaurants);
    }

    @Test
    void 숫자가_아닌_revision은_기존_400_봉투로_거부한다() throws Exception {
        mvc.perform(post(PATH + "/retry").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAddressRevision\":\"bad\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(restaurants);
    }

    @Test
    void revision과_READY_충돌은_RESTAURANT019를_유지한다() throws Exception {
        given(restaurants.retryLocationByAdmin(1L, 1))
                .willThrow(new BusinessException(RestaurantErrorCode.LOCATION_RETRY_CONFLICT));
        mvc.perform(post(PATH + "/retry").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAddressRevision\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESTAURANT-019"));
    }

    @Test
    void ADMIN은_기본_REVIEW_REQUIRED_필터와_offset_응답을_사용한다() throws Exception {
        var item = new RestaurantLocationReviewInfo(
                10L, "검토 식당", "東京都豊島区1-1", null, "REVIEW_REQUIRED", null,
                "GEOCODING", 2, null, 1, null, "NO_RESULTS", true, 7L);
        given(admins.findLoginIds(Set.of(7L))).willReturn(Map.of(7L, "hashi_admin"));
        given(restaurants.findLocationReviewsByAdmin("REVIEW_REQUIRED", null, 0, 20))
                .willReturn(new RestaurantLocationReviewPage(List.of(item), 0, 20, 1, 1));

        mvc.perform(get(LIST_PATH).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON-200"))
                .andExpect(jsonPath("$.data.restaurants[0].restaurantId").value(10))
                .andExpect(jsonPath("$.data.restaurants[0].locationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.restaurants[0].failureCode").value("NO_RESULTS"))
                .andExpect(jsonPath("$.data.restaurants[0].confirmedByAdminId").value(7))
                .andExpect(jsonPath("$.data.restaurants[0].confirmedByAdminLoginId").value("hashi_admin"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
        verify(restaurants).findLocationReviewsByAdmin("REVIEW_REQUIRED", null, 0, 20);
    }

    @Test
    void ADMIN은_page_size_status_source를_port에_전달한다() throws Exception {
        given(restaurants.findLocationReviewsByAdmin("READY", "GOOGLE_PLACES", 1, 10))
                .willReturn(new RestaurantLocationReviewPage(List.of(), 1, 10, 15, 2));

        mvc.perform(get(LIST_PATH + "?status=READY&source=GOOGLE_PLACES&page=1&size=10")
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.restaurants.length()").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalCount").value(15))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        verify(restaurants).findLocationReviewsByAdmin("READY", "GOOGLE_PLACES", 1, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ONBOARDING", "ANONYMOUS"})
    void 일반회원과_온보딩과_익명은_위치_검토_목록도_차단한다(String role) throws Exception {
        var request = get(LIST_PATH);
        if (!role.equals("ANONYMOUS")) {
            String token = role.equals("ONBOARDING") ? tokens.createOnboardingToken(1L)
                    : tokens.createAccessToken(1L, "ROLE_USER");
            request.header("Authorization", "Bearer " + token);
        }
        mvc.perform(request).andExpect(status().is(role.equals("ANONYMOUS") ? 401 : 403));
        verifyNoInteractions(restaurants);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?status=UNKNOWN",
            "?source=PLACES",
            "?page=-1",
            "?page=2147483647",
            "?page=2147483648",
            "?size=0",
            "?size=101"
    })
    void 위치_검토_목록의_지원하지_않는_필터와_범위는_400으로_거부한다(String query) throws Exception {
        mvc.perform(get(LIST_PATH + query).header("Authorization", admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));
        verifyNoInteractions(restaurants);
    }

    @Test
    void ADMIN은_POST로_Places_빈후보를_조회하고_서명후보를_선택한다() throws Exception {
        given(restaurants.searchLocationPlacesByAdmin(1L, 2))
                .willReturn(new RestaurantPlacesSearchInfo(1L, 2, List.of()));
        var pending = new RestaurantLocationInfo(1L, "PENDING", 2, null, "PLACE_DETAILS",
                null, 0, null, null, false);
        given(restaurants.selectLocationPlaceByAdmin(1L, 2, "signed-token")).willReturn(pending);

        mvc.perform(post(PATH + "/place-candidates").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedAddressRevision\":2}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.restaurantId").value(1))
                .andExpect(jsonPath("$.data.addressRevision").value(2))
                .andExpect(jsonPath("$.data.candidates.length()").value(0));

        mvc.perform(post(PATH + "/place-selection").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAddressRevision\":2,\"selectionToken\":\"signed-token\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.locationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.verificationMode").value("PLACE_DETAILS"));
        verify(restaurants).searchLocationPlacesByAdmin(1L, 2);
        verify(restaurants).selectLocationPlaceByAdmin(1L, 2, "signed-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/place-candidates", "/place-selection"})
    void 일반회원은_Places_관리자_API를_사용할수없다(String suffix) throws Exception {
        String body = suffix.equals("/place-selection")
                ? "{\"expectedAddressRevision\":2,\"selectionToken\":\"signed-token\"}"
                : "{\"expectedAddressRevision\":2}";
        mvc.perform(post(PATH + suffix)
                        .header("Authorization", "Bearer " + tokens.createAccessToken(1L, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(restaurants);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            /place-candidates | {"expectedAddressRevision":0}
            /place-selection | {"expectedAddressRevision":2,"selectionToken":" "}
            /place-selection | {"selectionToken":"signed-token"}
            """)
    void Places_요청의_누락과_잘못된값은_provider호출전_400이다(String suffix, String body) throws Exception {
        mvc.perform(post(PATH + suffix).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"));
        verifyNoInteractions(restaurants);
    }

    @Test
    void 등록전_미리보기는_ADMIN만_검색하고_빈후보도_성공응답으로_구분한다() throws Exception {
        var input = new RestaurantLocationPreviewCommand("식당", "現地名", "住所", null);
        given(restaurants.previewLocationByAdmin(input)).willReturn(List.of());
        mvc.perform(post("/api/v1/admin/restaurants/location-preview").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"식당\",\"localName\":\"現地名\",\"address\":\"住所\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.candidates.length()").value(0));
        verify(restaurants).previewLocationByAdmin(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ONBOARDING", "ANONYMOUS"})
    void 등록전_미리보기는_일반회원과_익명의_유료호출을_차단한다(String role) throws Exception {
        var request = post("/api/v1/admin/restaurants/location-preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"식당\",\"localName\":\"現地名\",\"address\":\"住所\"}");
        if (!role.equals("ANONYMOUS")) {
            String token = role.equals("ONBOARDING") ? tokens.createOnboardingToken(1L)
                    : tokens.createAccessToken(1L, "ROLE_USER");
            request.header("Authorization", "Bearer " + token);
        }
        mvc.perform(request).andExpect(role.equals("ANONYMOUS") ? status().isUnauthorized() : status().isForbidden());
        verifyNoInteractions(restaurants);
    }

    @Test
    void 등록전_미리보기의_공백기본주소는_유료호출전에_거부한다() throws Exception {
        mvc.perform(post("/api/v1/admin/restaurants/location-preview").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"식당\",\"localName\":\"現地名\",\"address\":\"住所\",\"geocodingAddress\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(restaurants);
    }

    private String admin() {
        return "Bearer " + tokens.createAccessToken(1L, "ROLE_ADMIN");
    }
}
