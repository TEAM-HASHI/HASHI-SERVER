package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;

public record MapRegionSummary(Long id, String name, BigDecimal latitude, BigDecimal longitude,
                               MapQueryBounds bounds, int displayOrder, long restaurantCount,
                               long outsideBoundsCount) {
}
