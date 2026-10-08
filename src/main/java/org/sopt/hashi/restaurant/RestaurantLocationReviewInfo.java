package org.sopt.hashi.restaurant;

import java.time.Instant;

/** 관리자 위치 검토 목록용 공개 정보. 좌표와 provider 원문, 내부 작업 ID는 노출하지 않는다. */
public record RestaurantLocationReviewInfo(
        Long restaurantId,
        String name,
        String address,
        String geocodingAddress,
        String locationStatus,
        String source,
        long addressRevision,
        Instant validUntil,
        int attempt,
        Instant nextAttemptAt,
        String failureCode,
        boolean canRetry) {
}
