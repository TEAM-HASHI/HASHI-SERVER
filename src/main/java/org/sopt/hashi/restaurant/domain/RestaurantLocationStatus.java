package org.sopt.hashi.restaurant.domain;

/** 위치 행이 없으면 UNRESOLVED로 해석한다. 실제 작업 claim/lease는 후속 worker의 책임이다. */
public enum RestaurantLocationStatus {
    PENDING,
    READY,
    RETRY_WAIT,
    REVIEW_REQUIRED,
    FAILED
}
