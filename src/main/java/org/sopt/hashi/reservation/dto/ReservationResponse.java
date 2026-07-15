package org.sopt.hashi.reservation.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.ReservationType;

/**
 * 예약 단건 응답(목록·생성·취소). 식당 정보는 STANDARD면 실시간 조회 값, ANYWHERE면 저장된 값(이미지 null).
 * confirmDDay는 진행중(REQUESTED·CONTACTING) 예약만 값이 있고, 예정일 경과 시 음수로 감소한다.
 */
public record ReservationResponse(
        Long reservationId,
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
        Long confirmDDay) {

    /** 엔티티 + 유형별로 해석된 식당명·대표이미지·주소로 응답을 만든다. */
    public static ReservationResponse of(Reservation reservation, String restaurantName,
                                         String restaurantImageUrl, String restaurantAddress) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getReservationType(),
                reservation.getReserverName(),
                reservation.getRestaurantId(),
                restaurantName,
                restaurantImageUrl,
                restaurantAddress,
                reservation.getReservedAt(),
                reservation.getAdultCount(),
                reservation.getTeenCount(),
                reservation.getChildCount(),
                reservation.getRequestNote(),
                reservation.getReservationStatus(),
                reservation.confirmDDay());
    }
}
