package org.sopt.hashi.restaurant;

import java.time.Instant;

/** 관리자용 값 계약. 내부 상태 enum, 주소, 좌표, 요청/lease 식별자를 노출하지 않는다. */
public record RestaurantLocationInfo(Long restaurantId, String locationStatus, long addressRevision,
                                     String source, String verificationMode,
                                     Instant validUntil, int attempt, Instant nextAttemptAt,
                                     String failureCode, boolean canRetry, String confirmationMethod,
                                     Long confirmedByAdminId, Instant confirmedAt, Long confirmedRevision) {
    public RestaurantLocationInfo(Long restaurantId, String locationStatus, long addressRevision,
                                  String source, String verificationMode, Instant validUntil, int attempt,
                                  Instant nextAttemptAt, String failureCode, boolean canRetry) {
        this(restaurantId, locationStatus, addressRevision, source, verificationMode, validUntil, attempt,
                nextAttemptAt, failureCode, canRetry, null, null, null, null);
    }
}
