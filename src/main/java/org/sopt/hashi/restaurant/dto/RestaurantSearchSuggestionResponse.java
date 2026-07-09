package org.sopt.hashi.restaurant.dto;

import java.util.List;

public record RestaurantSearchSuggestionResponse(
        List<SearchSuggestionResponse> suggestions
) {

    public record SearchSuggestionResponse(
            String keyword,
            String type
    ) {
    }
}
