package org.sopt.hashi.user.recent;

import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantMainInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.springframework.stereotype.Service;

@Service
class RestaurantDetailViewService {
    private final RestaurantPort restaurants;
    private final CurrentUserProvider currentUser;
    private final RecentRestaurantService history;

    RestaurantDetailViewService(RestaurantPort restaurants, CurrentUserProvider currentUser,
                                RecentRestaurantService history) {
        this.restaurants = restaurants;
        this.currentUser = currentUser;
        this.history = history;
    }

    public RestaurantMainInfo getSummary(Long restaurantId, boolean detailView) {
        RestaurantMainInfo summary = restaurants.getRestaurantSummary(restaurantId);
        if (detailView && currentUser.isAuthenticatedUser()) {
            history.recordDetail(currentUser.currentUserId(), restaurantId);
        }
        return summary;
    }
}
