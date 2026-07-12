package org.sopt.hashi.reservation.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.ReservationType;

/**
 * 예약 상세 응답. 식당 정보는 STANDARD면 실시간 조회 값, ANYWHERE면 저장된 값(일본어명·이미지 null).
 * receivedAt은 접수(생성) 시각, confirmExpectedAt은 접수 + 2일이다.
 */
public record ReservationDetailResponse(
        Long reservationId,
        ReservationType reservationType,
        String reserverName,
        ReservationStatus reservationStatus,
        Long restaurantId,
        String restaurantName,
        String restaurantNameJa,
        String restaurantAddress,
        String restaurantImageUrl,
        LocalDateTime reservedAt,
        int adultCount,
        int teenCount,
        int childCount,
        String requestNote,
        LocalDateTime receivedAt,
        LocalDateTime confirmExpectedAt) {

    /** 엔티티 + 유형별로 해석된 식당 표시 정보(name·nameJa·address·imageUrl)로 상세 응답을 만든다. */
    public static ReservationDetailResponse of(Reservation reservation, String restaurantName,
                                               String restaurantNameJa, String restaurantAddress,
                                               String restaurantImageUrl) {
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getReservationType(),
                reservation.getReserverName(),
                reservation.getReservationStatus(),
                reservation.getRestaurantId(),
                restaurantName,
                restaurantNameJa,
                restaurantAddress,
                restaurantImageUrl,
                reservation.getReservedAt(),
                reservation.getAdultCount(),
                reservation.getTeenCount(),
                reservation.getChildCount(),
                reservation.getRequestNote(),
                reservation.getCreatedAt(),
                reservation.confirmExpectedAt());
    }
}
