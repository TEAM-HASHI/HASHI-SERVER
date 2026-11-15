package org.sopt.hashi.user.collection.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.user.collection.domain.SavedRestaurant;

/** 저장 행과 enrich된 식당 카드를 묶은 정렬·커서 계산 단위. 표시 가능한(삭제되지 않은) 식당만 만든다. */
record SavedRestaurantItem(SavedRestaurant saved, RestaurantCardInfo card) {

    long savedId() {
        return saved.getId();
    }

    Long restaurantId() {
        return card.id();
    }

    BigDecimal rating() {
        return card.rating();
    }

    long reviewCount() {
        return card.reviewCount();
    }

    LocalDateTime savedAt() {
        return saved.getCreatedAt();
    }
}
