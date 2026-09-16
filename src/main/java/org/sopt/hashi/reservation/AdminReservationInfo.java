package org.sopt.hashi.reservation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.sopt.hashi.media.ImageReference;

/**
 * 모듈 간 전달용 어드민 예약 요약 DTO — 진입점(admin)이 예약을 관리(목록·상태 변경)할 때 받는 계약.
 * 사용자향 요약({@code ReservationResponse})의 필드에 관리에 필요한 예약자 ID·결제 정보
 * (usedPoint·amount·paymentStatus)를 더한다. 식당 표시 정보는 reservation이 유형별로 해석한 값이다
 * (STANDARD는 RestaurantPort enrich, ANYWHERE는 예약 저장 값).
 */
public record AdminReservationInfo(
        Long reservationId,
        Long userId,
        ReservationType reservationType,
        String reserverName,
        Long restaurantId,
        String restaurantName,
        ImageReference restaurantImageReference,
        String restaurantAddress,
        LocalDateTime reservedAt,
        int adultCount,
        int teenCount,
        int childCount,
        String requestNote,
        ReservationStatus reservationStatus,
        PaymentStatus paymentStatus,
        long usedPoint,
        BigDecimal amount,
        Long confirmDDay) {
}
