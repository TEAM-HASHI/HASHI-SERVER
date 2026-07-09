package org.sopt.hashi.reservation.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.ReservationType;

/**
 * 예약 상세 응답. 목록보다 많은 식당 정보(일본어명·주소·대표이미지)를 함께 내린다.
 * 식당 정보는 STANDARD면 RestaurantPort로 enrich한 값, ANYWHERE면 예약에 저장된 값이다
 * (ANYWHERE는 미등록 식당이라 일본어명·이미지는 없어 null).
 *
 * <p>{@code receivedAt}(접수 일자)는 생성 시각, {@code confirmExpectedAt}(확정 예정 일자)는 도메인 규칙
 * ({@link Reservation#confirmExpectedAt()})으로 계산된 값을 그대로 매핑한다.
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
