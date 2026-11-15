package org.sopt.hashi.restaurant.internal.map.places;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record PlacesCandidate(
        String placeId,
        String displayName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String countryCode,
        String administrativeArea,
        List<String> types,
        String businessStatus,
        List<PlacesAttribution> attributions,
        String googleMapsUri
) {

    public PlacesCandidate {
        Objects.requireNonNull(placeId, "placeId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(latitude, "latitude must not be null");
        Objects.requireNonNull(longitude, "longitude must not be null");
        Objects.requireNonNull(countryCode, "countryCode must not be null");
        Objects.requireNonNull(administrativeArea, "administrativeArea must not be null");
        types = List.copyOf(types);
        Objects.requireNonNull(businessStatus, "businessStatus must not be null");
        attributions = List.copyOf(attributions);
        Objects.requireNonNull(googleMapsUri, "googleMapsUri must not be null");
    }

    @Override
    public String toString() {
        return "PlacesCandidate[redacted]";
    }
}
