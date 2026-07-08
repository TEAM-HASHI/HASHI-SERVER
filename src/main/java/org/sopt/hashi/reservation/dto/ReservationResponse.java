package org.sopt.hashi.reservation.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationType;

/**
 * 예약 단건 응답(목록·생성·취소). 식당명·주소·대표이미지는 유형에 따라 다르게 채운다 — STANDARD는 RestaurantPort로
 * live enrich한 값(주소 포함, 스냅샷 아님), ANYWHERE는 예약에 저장된 값(이미지는 미등록 식당이라 null).
 * {@code confirmDDay}는 진행중(REQUESTED·CONTACTING) 예약만 값이 있고, 예정일 경과 시 음수로 감소한다.
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
