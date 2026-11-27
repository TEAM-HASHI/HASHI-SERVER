package org.sopt.hashi.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.reservation.ReservationAlternativesInfo;

public record AdminReservationAlternativesResponse(
        List<LocalDateTime> times, List<Restaurant> restaurants, Long rebookedReservationId) {

    public static AdminReservationAlternativesResponse from(ReservationAlternativesInfo info) {
        var restaurants = info.restaurants().stream().map(Restaurant::from).toList();
        return new AdminReservationAlternativesResponse(info.times(), restaurants, info.rebookedReservationId());
    }

    public record Restaurant(Long restaurantId, String name, String area, String foodCategory,
                             BigDecimal rating, List<String> imageUrls) {
        private static Restaurant from(ReservationAlternativesInfo.Restaurant restaurant) {
            return new Restaurant(restaurant.restaurantId(), restaurant.name(), restaurant.area(),
                    restaurant.foodCategory(), restaurant.rating(), restaurant.imageUrls());
        }
    }
}
