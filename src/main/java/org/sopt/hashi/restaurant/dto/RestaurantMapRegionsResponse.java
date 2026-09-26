package org.sopt.hashi.restaurant.dto;

import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;

public record RestaurantMapRegionsResponse(BoundsResponse initialBounds, QueryLimitsResponse queryLimits,
                                           List<RegionResponse> regions) {

    public record BoundsResponse(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east) {
        public static BoundsResponse from(MapQueryBounds bounds) {
            return new BoundsResponse(bounds.south(), bounds.north(), bounds.west(), bounds.east());
        }
    }

    public record QueryLimitsResponse(BoundsResponse supportedBounds, int maxLatitudeSpan, int maxLongitudeSpan) {
    }

    public record PositionResponse(BigDecimal latitude, BigDecimal longitude) {
    }

    public record RegionResponse(Long mapRegionId, String name, long restaurantCount,
                                 PositionResponse clusterPosition, BoundsResponse cameraBounds, int displayOrder) {
    }
}
