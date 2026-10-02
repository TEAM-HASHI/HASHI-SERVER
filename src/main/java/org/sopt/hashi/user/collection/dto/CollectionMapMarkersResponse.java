package org.sopt.hashi.user.collection.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** visibleRestaurantCount는 현재 존재하는 식당 수다. 좌표 없는 식당은 이 수에 포함되지만 content에는 없다. */
public record CollectionMapMarkersResponse(Long collectionId, long collectionVersion, Instant generatedAt,
                                           int visibleRestaurantCount, int locationUnavailableCount, List<Marker> content) {
    public record Marker(Long restaurantId, String name, String placeType, String genre, Position location) { }
    public record Position(BigDecimal latitude, BigDecimal longitude, Instant validUntil) { }
}
