package org.sopt.hashi.admin.service;

import org.sopt.hashi.admin.dto.AdminReservationListResponse;
import org.sopt.hashi.admin.dto.AdminReservationResponse;
import org.sopt.hashi.admin.dto.AdminReservationUserResponse;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationStatus;
import org.springframework.stereotype.Service;

/**
 * 어드민 예약 관리 — 진입점 모듈이라 도메인 로직 없이 {@link ReservationPort}로 위임하고
 * 응답 DTO 변환만 한다(architecture.md §9). 트랜잭션·포인트 복원·예약자 enrich는 reservation 소관.
 */
@Service
public class AdminReservationService {

    private final ReservationPort reservationPort;

    public AdminReservationService(ReservationPort reservationPort) {
        this.reservationPort = reservationPort;
    }

    /** 예약 상태 변경(자유 전이) — CANCELED 진입 시 포인트 복원 규칙은 reservation이 적용한다. */
    public AdminReservationResponse changeStatus(Long reservationId, ReservationStatus targetStatus) {
        return AdminReservationResponse.from(
                reservationPort.changeStatusByAdmin(reservationId, targetStatus));
    }

    /** 예약 목록 — 전체 사용자 대상 offset 페이지네이션(최신순), status가 null이면 전체. */
    public AdminReservationListResponse getReservations(ReservationStatus status, int page, int size) {
        return AdminReservationListResponse.from(reservationPort.findPageByAdmin(status, page, size));
    }

    /** 예약자 정보 조회 — 예약의 예약자 enrich는 reservation 담당(§5-3), 여기서는 응답 변환만. */
    public AdminReservationUserResponse getReserver(Long reservationId) {
        return AdminReservationUserResponse.from(reservationPort.findReserverByAdmin(reservationId));
    }
}
