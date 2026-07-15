package org.sopt.hashi.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.sopt.hashi.reservation.PaymentStatus;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;

/**
 * 어드민 예약 단건 응답(목록 항목·상태 변경 결과). 사용자향 예약 응답 필드에 관리용
 * 예약자 ID·결제 정보(usedPoint·amount·paymentStatus)를 더한 구성이다(클라 합의 계약).
 */
public record AdminReservationResponse(
        Long reservationId,
        Long userId,
        ReservationType reservationType,
        String reserverName,
        Long restaurantId,
        String restaurantName,
        String restaurantImageUrl,
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

    public static AdminReservationResponse from(AdminReservationInfo info) {
        return new AdminReservationResponse(
                info.reservationId(),
                info.userId(),
                info.reservationType(),
                info.reserverName(),
                info.restaurantId(),
                info.restaurantName(),
                info.restaurantImageUrl(),
                info.restaurantAddress(),
                info.reservedAt(),
                info.adultCount(),
                info.teenCount(),
                info.childCount(),
                info.requestNote(),
                info.reservationStatus(),
                info.paymentStatus(),
                info.usedPoint(),
                info.amount(),
                info.confirmDDay());
    }
}
