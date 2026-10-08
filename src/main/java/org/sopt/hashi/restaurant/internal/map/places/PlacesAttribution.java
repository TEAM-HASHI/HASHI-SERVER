package org.sopt.hashi.restaurant.internal.map.places;

import java.util.Objects;

public record PlacesAttribution(String displayName, String uri) {

    public PlacesAttribution {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(uri, "uri must not be null");
    }

    @Override
    public String toString() {
        return "PlacesAttribution[redacted]";
    }
}
