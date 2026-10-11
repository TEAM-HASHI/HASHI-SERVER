package org.sopt.hashi.restaurant.internal.map.places;

public interface PlacesProvider {

    // Existing admin input limits: local name (100) + separator (1) + address (255).
    int MAX_SEARCH_QUERY_LENGTH = 356;

    PlacesSearchResult search(String query);

    PlaceDetailsResult details(String placeId);
}
