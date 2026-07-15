package org.sopt.hashi.reservation;

/**
 * 모듈 간 전달용 예약 요약 DTO. 리뷰 등 의존 모듈이 예약 소유자·대상 식당·상태를 확인하는 데 필요한
 * 최소 필드만 담는다(§2-2 {@code <Context>Info}).
 */
public record ReservationInfo(Long id, Long userId, Long restaurantId, ReservationStatus reservationStatus) {
}
