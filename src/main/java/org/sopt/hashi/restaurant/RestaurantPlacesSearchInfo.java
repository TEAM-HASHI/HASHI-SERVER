package org.sopt.hashi.restaurant;

import java.util.List;

public record RestaurantPlacesSearchInfo(Long restaurantId, long addressRevision,
                                         List<RestaurantPlacesCandidateInfo> candidates) {
}
