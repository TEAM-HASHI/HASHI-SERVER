package org.sopt.hashi.restaurant.internal.map.places;

public interface PlacesProvider {

    PlacesSearchResult search(String query);

    PlaceDetailsResult details(String placeId);
}
