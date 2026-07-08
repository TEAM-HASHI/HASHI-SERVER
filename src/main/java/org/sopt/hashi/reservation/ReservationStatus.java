package org.sopt.hashi.reservation;

import lombok.Getter;

/**
 * 예약 상태. 예약의 공개 계약(ReservationInfo)에 실려 타 모듈(리뷰 등)이 예약 진행 상태를 판단하는 데 쓰이므로
 * 모듈 루트에 공개한다.
 *
 * <p>정상 흐름: {@link #REQUESTED} → {@link #CONTACTING} → {@link #CONFIRMED} → {@link #VISITED},
 * 어느 단계에서든 {@link #CANCELED}로 종료될 수 있다. 상태 전이 로직·이력은 상태 전이 이슈에서 다룬다(#40 범위 밖).
 */
@Getter
public enum ReservationStatus {

    REQUESTED("예약이 요청되어 식당 컨택을 기다리는 상태"),
    CONTACTING("식당에 예약 가능 여부를 확인하는 중인 상태"),
    CONFIRMED("식당이 예약을 확정한 상태"),
    VISITED("방문이 완료된 상태"),
    CANCELED("예약이 취소된 상태");

    private final String description;

    ReservationStatus(String description) {
        this.description = description;
    }
}
