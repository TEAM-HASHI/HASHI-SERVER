package org.sopt.hashi.reservation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 확인한 대안 시간(일본 현지 시각), 대안 식당(등록 순서), 이미 접수한 재예약 ID. */
public record ReservationAlternativesInfo(List<LocalDateTime> times, List<Restaurant> restaurants,
                                          Long rebookedReservationId) {
    public record Restaurant(Long restaurantId, String name, String area, String foodCategory,
                             BigDecimal rating, List<String> imageUrls) {}
}
