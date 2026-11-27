package org.sopt.hashi.user.recent;

import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantDetailInfo.PriceRangeInfo;
import org.sopt.hashi.restaurant.RestaurantDetailInfo.TodayBusinessHourInfo;

public record RecentRestaurantPage(List<Card> content, String nextCursor, boolean hasNext) {
    public record Card(Long restaurantId, String name, BigDecimal rating, String area, String foodCategory,
                       TodayBusinessHourInfo todayBusinessHour, PriceRangeInfo priceRange, List<String> imageUrls,
                       long saveCount, boolean saved) {
        static Card of(RestaurantDetailInfo restaurant, boolean saved) {
            return new Card(restaurant.id(), restaurant.name(), restaurant.rating(), restaurant.area(),
                    restaurant.foodCategory(), restaurant.todayBusinessHour(), restaurant.priceRange(),
                    restaurant.imageUrls(), restaurant.saveCount(), saved);
        }
    }
}
