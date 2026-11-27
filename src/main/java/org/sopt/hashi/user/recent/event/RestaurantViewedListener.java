package org.sopt.hashi.user.recent.event;

import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantViewedEvent;
import org.sopt.hashi.user.recent.service.RecentRestaurantService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RestaurantViewedListener {
    private final CurrentUserProvider currentUser;
    private final RecentRestaurantService recentRestaurants;

    RestaurantViewedListener(CurrentUserProvider currentUser, RecentRestaurantService recentRestaurants) {
        this.currentUser = currentUser;
        this.recentRestaurants = recentRestaurants;
    }

    // 상세 읽기 트랜잭션이 끝난 뒤 처리한다. 다음 요청에서 이력이 바로 보이도록 요청 스레드에서 저장한다.
    @EventListener
    public void record(RestaurantViewedEvent event) {
        if (currentUser.isAuthenticatedUser()) {
            try {
                recentRestaurants.recordDetail(currentUser.currentUserId(), event.restaurantId());
            } catch (RuntimeException ex) {
                log.warn("최근 본 맛집 기록 실패. restaurantId={}, error={}", event.restaurantId(), ex.getClass().getSimpleName());
            }
        }
    }
}
