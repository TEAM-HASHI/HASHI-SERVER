package org.sopt.hashi.reservation;

import java.time.LocalDateTime;

/**
 * 리뷰 유스케이스에 제공하는 예약 조회 DTO. 예약 소유권·방문 완료 여부와 화면 표시용 방문 정보를
 * 한 번에 확인할 수 있도록 reservation 모듈이 소유한 필드만 공개한다.
 */
public record ReservationReviewInfo(
        Long id,
        Long userId,
        ReservationType reservationType,
        Long restaurantId,
        String restaurantName,
        String restaurantAddress,
        LocalDateTime reservedAt,
        int adultCount,
        int teenCount,
        int childCount,
        ReservationStatus reservationStatus
) {

    public int partySize() {
        return adultCount + teenCount + childCount;
    }

    public boolean supportsReview() {
        return reservationType == ReservationType.STANDARD && restaurantId != null;
    }
}
