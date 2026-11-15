package org.sopt.hashi.reservation.service;

import java.util.Set;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.WithdrawalBlocker;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 조건(SPRINT-001 §3.2) — 방문 완료·취소가 아닌 예약이 남아 있으면 탈퇴를 막는다(RESERVATION-008, 409).
 * user가 공개한 {@link WithdrawalBlocker}를 reservation이 구현하므로 의존 방향은 기존대로 reservation → user다.
 * user가 회원 행을 잠근 탈퇴 트랜잭션 안에서 호출된다.
 */
@Component
class ReservationWithdrawalBlocker implements WithdrawalBlocker {

    /** 아직 끝나지 않은 예약 상태 — 요청·컨택·확정. */
    static final Set<ReservationStatus> UNFINISHED_STATUSES = Set.of(
            ReservationStatus.REQUESTED, ReservationStatus.CONTACTING, ReservationStatus.CONFIRMED);

    private final ReservationRepository reservationRepository;

    ReservationWithdrawalBlocker(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Override
    public void validateWithdrawable(Long userId) {
        if (reservationRepository.existsByUserIdAndReservationStatusIn(userId, UNFINISHED_STATUSES)) {
            throw new BusinessException(ReservationErrorCode.UNFINISHED_RESERVATION_EXISTS);
        }
    }
}
