package org.sopt.hashi.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;

@ExtendWith(MockitoExtension.class)
class AdminRestaurantServiceTest {

    @Mock
    private RestaurantPort restaurantPort;

    @InjectMocks
    private AdminRestaurantService adminRestaurantService;

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

        adminRestaurantService.update(1L, request);

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

    private AdminRestaurantInfo adminRestaurantInfo() {
        return new AdminRestaurantInfo(
                1L,
                "하시 식당",
                "ハシ食堂",
                "식당 소개",
                "상세 설명",
                "도쿄도",
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
