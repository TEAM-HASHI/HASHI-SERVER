package org.sopt.hashi.restaurant.service;

import java.math.RoundingMode;
import java.net.URI;
import java.util.Set;
import org.sopt.hashi.restaurant.domain.MapBounds;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.RestaurantLocationAttribution;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.sopt.hashi.restaurant.internal.map.places.PlacesCandidate;
import org.springframework.stereotype.Service;

/** Places 후보는 일본·도쿄·서비스 지도 경계를 모두 만족할 때만 선택 토큰을 받을 수 있다. */
@Service
public class PlacesCandidatePolicy {
    private static final Set<String> TOKYO = Set.of("Tokyo", "東京都");
    private final LocationJobProperties properties;

    public PlacesCandidatePolicy(LocationJobProperties properties) {
        this.properties = properties;
    }

    public boolean accepts(PlacesCandidate candidate) {
        if (!properties.isConfigured() || !"JP".equals(candidate.countryCode())
                || !TOKYO.contains(candidate.administrativeArea())
                || !isHttps(candidate.googleMapsUri())) {
            return false;
        }
        try {
            MapCoordinates coordinates = coordinates(candidate);
            attributions(candidate);
            return MapBounds.of(properties.south(), properties.north(), properties.west(), properties.east())
                    .contains(coordinates);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public MapCoordinates coordinates(PlacesCandidate candidate) {
        return MapCoordinates.of(candidate.latitude().setScale(6, RoundingMode.HALF_UP),
                candidate.longitude().setScale(6, RoundingMode.HALF_UP));
    }

    public java.util.List<RestaurantLocationAttribution> attributions(PlacesCandidate candidate) {
        return candidate.attributions().stream()
                .map(value -> new RestaurantLocationAttribution(value.displayName(), value.uri()))
                .toList();
    }

    private boolean isHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
