package org.sopt.hashi.reservation;

import java.util.Optional;

/**
 * reservation 모듈의 공개 포트 — 타 도메인(review 등)이 예약을 참조할 때 쓰는 최소 계약.
 * 의존 모듈은 reservation 내부(엔티티·Repository·서비스)가 아니라 이 포트에만 코딩한다.
 */
public interface ReservationPort {

    /** 예약 요약을 조회한다 — 없으면 empty. */
    Optional<ReservationInfo> findById(Long reservationId);
}
