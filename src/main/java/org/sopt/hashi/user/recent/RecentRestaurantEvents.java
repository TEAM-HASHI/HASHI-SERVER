package org.sopt.hashi.user.recent;

import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantDetailViewedEvent;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class RecentRestaurantEvents {
    private final CurrentUserProvider currentUser;
    private final RecentRestaurantService service;
    private final RecentRestaurantRepository history;

    RecentRestaurantEvents(CurrentUserProvider currentUser, RecentRestaurantService service,
                           RecentRestaurantRepository history) {
        this.currentUser = currentUser;
        this.service = service;
        this.history = history;
    }

    @EventListener
    public void onDetailViewed(RestaurantDetailViewedEvent event) {
        if (currentUser.isAuthenticatedUser()) {
            service.recordDetail(currentUser.currentUserId(), event.restaurantId());
        }
    }

    /** 탈퇴 서비스가 회원 행을 잠근 트랜잭션에서 즉시 정리한다. */
    @EventListener
    public void onWithdrawal(UserWithdrawnEvent event) {
        history.deleteByUserId(event.userId());
    }
}
