package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;

/** 후보 순위를 고정하는 데 필요한 값만 읽는다. 이미지나 Entity를 조회하지 않는다. */
public record RestaurantMapCandidate(Long restaurantId, BigDecimal rating, long reviewCount) {
}
