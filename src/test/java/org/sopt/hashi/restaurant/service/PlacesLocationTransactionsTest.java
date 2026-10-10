package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.domain.GeocodingBudgetRepository;
import org.sopt.hashi.restaurant.domain.PlacesBudget;
import org.sopt.hashi.restaurant.domain.PlacesBudget.Operation;
import org.sopt.hashi.restaurant.domain.PlacesBudgetRepository;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantLocation;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

class PlacesLocationTransactionsTest {
    private final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    private final PlacesBudgetRepository budgets = mock(PlacesBudgetRepository.class);
    private final GeocodingBudgetRepository clock = mock(GeocodingBudgetRepository.class);
    private final PlacesLocationTransactions transactions = new PlacesLocationTransactions(
            restaurants, mock(RestaurantLocationJobRepository.class), budgets, clock);

    @Test
    void 예산_잠금_대기중_자정이_지나면_잠금획득후_날짜와_분으로_예약한다() {
        restaurant("매장", "주소");
        AtomicReference<LocalDateTime> now = new AtomicReference<>(LocalDateTime.parse("2026-10-09T23:59:59"));
        LocalDateTime acquired = LocalDateTime.parse("2026-10-10T00:00:01");
        PlacesBudget budget = mock(PlacesBudget.class);
        given(clock.databaseUtcTime()).willAnswer(ignored -> now.get().toString());
        given(budgets.findByOperationForUpdate(Operation.SEARCH)).willAnswer(ignored -> {
            now.set(acquired);
            return Optional.of(budget);
        });
        given(budget.reserve(any())).willAnswer(invocation -> {
            assertThat(invocation.<LocalDateTime>getArgument(0)).isEqualTo(acquired);
            return true;
        });

        assertThat(transactions.prepareSearch(1L, 1).query()).isEqualTo("매장 주소");
    }

    @Test
    void 로컬_검색입력이_너무_길면_예산을_예약하기_전에_거부한다() {
        restaurant("가".repeat(101), "東".repeat(255));
        assertThatThrownBy(() -> transactions.prepareSearch(1L, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(budgets, clock);
    }

    private void restaurant(String name, String address) {
        Restaurant restaurant = mock(Restaurant.class);
        RestaurantLocation location = mock(RestaurantLocation.class);
        given(restaurants.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));
        given(restaurant.getLocation()).willReturn(location);
        given(restaurant.getId()).willReturn(1L);
        given(restaurant.getLocalName()).willReturn(name);
        given(restaurant.geocodingAddressForResolution()).willReturn(address);
        given(location.getAddressRevision()).willReturn(1L);
        given(location.getStatus()).willReturn(RestaurantLocationStatus.REVIEW_REQUIRED);
        given(location.getRequestId()).willReturn(UUID.randomUUID());
    }
}
