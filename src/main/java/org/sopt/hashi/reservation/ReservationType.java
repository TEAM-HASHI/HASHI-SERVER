package org.sopt.hashi.reservation;

import lombok.Getter;

/**
 * 예약 유형. 등록된 식당 예약과 미등록 식당(어디든) 예약을 구분한다.
 * ANYWHERE는 restaurant 테이블에 없는 식당이라 식당명·주소를 예약 레코드에 직접 보관한다.
 */
@Getter
public enum ReservationType {

    STANDARD("등록된 식당 예약 — restaurant_id로 참조하고 식당명은 RestaurantPort로 조회"),
    ANYWHERE("미등록 식당(어디든) 예약 — 식당명·주소를 예약에 직접 저장");

    private final String description;

    ReservationType(String description) {
        this.description = description;
    }
}
