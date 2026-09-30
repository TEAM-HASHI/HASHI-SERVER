package org.sopt.hashi.restaurant;

import java.time.Instant;

/** 관리자용 값 계약. 내부 상태 enum, 주소, 좌표, 요청/lease 식별자를 노출하지 않는다. */
public record RestaurantLocationInfo(Long restaurantId, String locationStatus, long addressRevision,
                                     Instant validUntil, int attempt, Instant nextAttemptAt,
                                     String failureCode, boolean canRetry) {
}
