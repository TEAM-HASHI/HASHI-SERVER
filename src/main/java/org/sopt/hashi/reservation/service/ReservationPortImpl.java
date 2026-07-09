package org.sopt.hashi.reservation.service;

import java.util.Optional;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.sopt.hashi.reservation.ReservationInfo;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.user.UserInfo;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * reservation 공개 포트 구현 — 요약 조회는 Repository에서 직접 변환하고,
 * 어드민 유스케이스(상태 변경·목록·예약자 조회)는 {@link ReservationService}에 위임한다
 * (트랜잭션 경계·포인트 복원은 서비스가 갖는다, §8).
 */
@Component
class ReservationPortImpl implements ReservationPort {

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;

    ReservationPortImpl(ReservationRepository reservationRepository,
                        ReservationService reservationService) {
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
    }

    @Override
    public Optional<ReservationInfo> findById(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .map(reservation -> new ReservationInfo(
                        reservation.getId(),
                        reservation.getUserId(),
                        reservation.getRestaurantId(),
                        reservation.getReservationStatus()));
    }

    @Override
    public AdminReservationInfo changeStatusByAdmin(Long reservationId, ReservationStatus targetStatus) {
        return reservationService.changeStatusByAdmin(reservationId, targetStatus);
    }

    @Override
    public Page<AdminReservationInfo> findPageByAdmin(ReservationStatus statusFilter, int page, int size) {
        return reservationService.findPageByAdmin(statusFilter, page, size);
    }

    @Override
    public UserInfo findReserverByAdmin(Long reservationId) {
        return reservationService.findReserverByAdmin(reservationId);
    }
}
