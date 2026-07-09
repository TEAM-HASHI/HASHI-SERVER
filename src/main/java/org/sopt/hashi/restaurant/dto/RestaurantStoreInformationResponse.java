package org.sopt.hashi.restaurant.dto;

import java.util.List;

public record RestaurantStoreInformationResponse(
        Long restaurantId,
        String description,
        List<BusinessHourResponse> businessHours,
        PriceRangeResponse priceRange
) {

    public record BusinessHourResponse(
            String dayOfWeek,
            String openTime,
            String closeTime,
            String lastOrderTime,
            boolean closed
    ) {
    }

    public record PriceRangeResponse(
            String currency,
            Long minPrice,
            Long maxPrice
    ) {
    }
}
