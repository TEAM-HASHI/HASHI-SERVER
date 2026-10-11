package org.sopt.hashi.restaurant.domain;

import java.net.URI;
import java.util.Objects;

/** 공개 좌표와 함께 표시해야 하는 Places 제3자 attribution의 최소 저장 계약. */
public record RestaurantLocationAttribution(String displayName, String uri) {
    public RestaurantLocationAttribution {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(uri, "uri");
        if (displayName.isBlank() || displayName.length() > 200 || uri.length() > 2048) {
            throw new IllegalArgumentException("Places attribution is invalid");
        }
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Places attribution URI is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
            throw new IllegalArgumentException("Places attribution URI must use HTTPS");
        }
    }
}
