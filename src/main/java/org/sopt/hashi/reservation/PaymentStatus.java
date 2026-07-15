package org.sopt.hashi.reservation;

import lombok.Getter;

/**
 * 예약 결제 상태(ERD reservation.payment_status). 실 PG 연동 전 MVP 값 — 값 목록은 결제 스펙 확정 시 조정될 수 있다.
 */
@Getter
public enum PaymentStatus {

    PENDING("결제 대기 — 예약 생성 직후 초기 상태"),
    PAID("결제 완료"),
    CANCELED("결제 취소 — 예약 취소로 결제가 무효화된 상태");

    private final String description;

    PaymentStatus(String description) {
        this.description = description;
    }
}
