package org.sopt.hashi.reservation.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationType;

/**
 * 예약 단건 응답(목록·생성). 식당명·대표이미지는 유형에 따라 다르게 채운다 — STANDARD는 RestaurantPort로
 * enrich한 값, ANYWHERE는 저장된 식당명(이미지는 미등록 식당이라 null). 주소는 ANYWHERE만 값이 있다.
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
        ReservationStatus reservationStatus) {

    /** 엔티티 + 유형별로 해석된 식당명·대표이미지 URL로 응답을 만든다. */
    public static ReservationResponse of(Reservation reservation, String restaurantName,
                                         String restaurantImageUrl) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getReservationType(),
                reservation.getReserverName(),
                reservation.getRestaurantId(),
                restaurantName,
                restaurantImageUrl,
                reservation.getRestaurantAddress(),
                reservation.getReservedAt(),
                reservation.getAdultCount(),
                reservation.getTeenCount(),
                reservation.getChildCount(),
                reservation.getRequestNote(),
                reservation.getReservationStatus());
    }
}
