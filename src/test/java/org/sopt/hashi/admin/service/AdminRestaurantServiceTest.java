package org.sopt.hashi.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.admin.dto.RetryRestaurantLocationRequest;
import org.sopt.hashi.admin.dto.SelectRestaurantPlaceRequest;
import org.sopt.hashi.auth.AdminAccountPort;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.RestaurantLocationReviewInfo;
import org.sopt.hashi.restaurant.RestaurantLocationReviewPage;

@ExtendWith(MockitoExtension.class)
class AdminRestaurantServiceTest {

    @Mock
    private RestaurantPort restaurantPort;

    @Mock
    private AdminAccountPort adminAccountPort;

    @InjectMocks
    private AdminRestaurantService adminRestaurantService;

    @Test
    void 상태와_재시도와_후보선택은_저장된_확인자의_현재_로그인ID를_반환한다() {
        var info = location(7L);
        given(restaurantPort.getLocationByAdmin(1L)).willReturn(info);
        given(restaurantPort.retryLocationByAdmin(1L, 2)).willReturn(info);
        given(restaurantPort.selectLocationPlaceByAdmin(1L, 2, "selection-token")).willReturn(info);
        given(adminAccountPort.findLoginIds(Set.of(7L))).willReturn(Map.of(7L, "hashi_admin"));

        var responses = List.of(
                adminRestaurantService.getLocation(1L),
                adminRestaurantService.retryLocation(1L, new RetryRestaurantLocationRequest(2L)),
                adminRestaurantService.selectLocationPlace(1L, new SelectRestaurantPlaceRequest(2L, "selection-token")));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.confirmedByAdminId()).isEqualTo(7L);
            assertThat(response.confirmedByAdminLoginId()).isEqualTo("hashi_admin");
        });
        verify(adminAccountPort, times(3)).findLoginIds(Set.of(7L));
    }

    @Test
    void 확인_계정이_삭제되면_내부ID는_유지하고_로그인ID만_null이다() {
        given(restaurantPort.getLocationByAdmin(1L)).willReturn(location(7L));
        given(adminAccountPort.findLoginIds(Set.of(7L))).willReturn(Map.of());

        var response = adminRestaurantService.getLocation(1L);

        assertThat(response.confirmedByAdminId()).isEqualTo(7L);
        assertThat(response.confirmedByAdminLoginId()).isNull();
    }

    @Test
    void 확인자가_없는_위치는_계정을_조회하지_않는다() {
        given(restaurantPort.getLocationByAdmin(1L)).willReturn(location(null));

        assertThat(adminRestaurantService.getLocation(1L).confirmedByAdminLoginId()).isNull();

        verifyNoInteractions(adminAccountPort);
    }

    @Test
    void 검토_목록은_중복_확인자를_한번에_조회하고_없는_계정은_null로_표시한다() {
        given(restaurantPort.findLocationReviewsByAdmin("REVIEW_REQUIRED", null, 0, 20))
                .willReturn(new RestaurantLocationReviewPage(List.of(
                        review(1L, 7L), review(2L, null), review(3L, 7L), review(4L, 8L)), 0, 20, 4, 1));
        given(adminAccountPort.findLoginIds(Set.of(7L, 8L))).willReturn(Map.of(7L, "hashi_admin"));

        var response = adminRestaurantService.findLocationReviews("REVIEW_REQUIRED", null, 0, 20);

        assertThat(response.restaurants()).extracting("confirmedByAdminLoginId")
                .containsExactly("hashi_admin", null, "hashi_admin", null);
        assertThat(response.restaurants()).extracting("confirmedByAdminId").containsExactly(7L, null, 7L, 8L);
        verify(adminAccountPort).findLoginIds(Set.of(7L, 8L));
        verifyNoMoreInteractions(adminAccountPort);
    }

    @Test
    void 확인자가_없는_목록은_계정을_조회하지_않는다() {
        given(restaurantPort.findLocationReviewsByAdmin("REVIEW_REQUIRED", null, 0, 20))
                .willReturn(new RestaurantLocationReviewPage(List.of(review(1L, null)), 0, 20, 1, 1));

        assertThat(adminRestaurantService.findLocationReviews("REVIEW_REQUIRED", null, 0, 20)
                .restaurants().getFirst().confirmedByAdminLoginId()).isNull();

        verifyNoInteractions(adminAccountPort);
    }

    private RestaurantLocationInfo location(Long adminId) {
        return new RestaurantLocationInfo(1L, "PENDING", 2, null, "PLACE_DETAILS", null,
                0, null, null, false, "ADMIN_PLACE_SELECTION", adminId, null, 2L);
    }

    private RestaurantLocationReviewInfo review(Long restaurantId, Long adminId) {
        return new RestaurantLocationReviewInfo(restaurantId, "검토 식당", "東京都", null,
                "REVIEW_REQUIRED", null, "PLACE_DETAILS", 2, null, 1, null, "NO_RESULTS", true, adminId);
    }

    @Test
    void 식당_수정은_기존_메뉴_ID를_restaurant_포트에_전달한다() {
        UpdateRestaurantRequest request = updateRequest(List.of(
                new UpdateRestaurantRequest.MenuRequest(
                        10L,
                        "시오라멘",
                        "메뉴 설명",
                        "restaurant-menus/10.jpg",
                        "JPY",
                        BigDecimal.valueOf(1_000),
                        true
                )
        ));
        given(restaurantPort.updateByAdmin(eq(1L), any(AdminRestaurantCommand.class)))
                .willReturn(adminRestaurantInfo());

        var response = adminRestaurantService.update(1L, request);
        assertThat(response.restaurantId()).isEqualTo(1L);
        assertThat(response.geocodingAddress()).isEqualTo("東京都千代田区丸の内1-9-1");
        assertThat(response.locationStatus()).isEqualTo("PENDING");
        assertThat(response.addressRevision()).isEqualTo(1);

        ArgumentCaptor<AdminRestaurantCommand> commandCaptor =
                ArgumentCaptor.forClass(AdminRestaurantCommand.class);
        verify(restaurantPort).updateByAdmin(eq(1L), commandCaptor.capture());
        assertThat(commandCaptor.getValue().menus())
                .singleElement()
                .satisfies(menu -> {
                    assertThat(menu.menuId()).isEqualTo(10L);
                    assertThat(menu.name()).isEqualTo("시오라멘");
                });
    }

    @Test
    void 식당_수정은_명시한_위치확인주소를_restaurant_포트에_전달한다() {
        UpdateRestaurantRequest request = addressAndGeocodingAddressRequest(
                "東京都千代田区丸の内1-9-1 建物2F", "  東京都千代田区丸の内1-9-1  ");
        given(restaurantPort.updateByAdmin(eq(1L), any(AdminRestaurantCommand.class)))
                .willReturn(adminRestaurantInfo());

        adminRestaurantService.update(1L, request);

        ArgumentCaptor<AdminRestaurantCommand> commandCaptor =
                ArgumentCaptor.forClass(AdminRestaurantCommand.class);
        verify(restaurantPort).updateByAdmin(eq(1L), commandCaptor.capture());
        assertThat(commandCaptor.getValue().address()).isEqualTo("東京都千代田区丸の内1-9-1 建物2F");
        assertThat(commandCaptor.getValue().geocodingAddress())
                .isEqualTo("  東京都千代田区丸の内1-9-1  ");
    }

    private UpdateRestaurantRequest updateRequest(List<UpdateRestaurantRequest.MenuRequest> menus) {
        return new UpdateRestaurantRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                menus,
                null,
                null,
                null
        );
    }

    private UpdateRestaurantRequest addressAndGeocodingAddressRequest(String address, String geocodingAddress) {
        return new UpdateRestaurantRequest(
                null, null, null, null, address, geocodingAddress, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private AdminRestaurantInfo adminRestaurantInfo() {
        return new AdminRestaurantInfo(
                1L,
                "하시 식당",
                "ハシ食堂",
                "식당 소개",
                "상세 설명",
                "도쿄도",
                "東京都千代田区丸の内1-9-1",
                "도쿄",
                "sushi",
                "sushi",
                "restaurant",
                null,
                null,
                "JPY",
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000),
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of("현지인맛집"),
                List.of(),
                List.of(),
                LocalDateTime.of(2026, 7, 14, 0, 0), "PENDING", 1
        );
    }
}
