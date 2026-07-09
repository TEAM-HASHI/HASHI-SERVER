package org.sopt.hashi.restaurant.dto;

import java.util.List;

public record RestaurantSearchSuggestionResponse(List<Suggestion> suggestions) {

    public record Suggestion(String keyword, String type) {
    }
}
