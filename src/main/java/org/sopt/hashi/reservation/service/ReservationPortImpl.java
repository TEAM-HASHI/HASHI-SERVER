package org.sopt.hashi.reservation.service;

import java.util.Optional;
import org.sopt.hashi.reservation.ReservationInfo;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.springframework.stereotype.Component;

/**
 * reservation 공개 포트 구현 — 예약 요약을 Repository에서 조회해 {@link ReservationInfo}로 변환한다.
 */
@Component
class ReservationPortImpl implements ReservationPort {

    private final ReservationRepository reservationRepository;

    ReservationPortImpl(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
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
}
