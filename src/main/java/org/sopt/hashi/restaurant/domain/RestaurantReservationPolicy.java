package org.sopt.hashi.restaurant.domain;

/** MVP에서 모든 식당에 동일하게 적용하는 예약 정책. */
public final class RestaurantReservationPolicy {

    public static final long RESERVATION_FEE = 4_000L;

    private RestaurantReservationPolicy() {
    }
}
