package org.sopt.hashi.reservation.domain;

import java.util.Collection;
import java.util.List;
import org.sopt.hashi.reservation.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 여러 예약을 리뷰/목록 화면에서 일괄 enrich하기 위한 조회. */
    List<Reservation> findByIdIn(Collection<Long> reservationIds);

    /** 사용자의 방문 완료 예약을 방문 일시와 ID 기준 최신순으로 조회한다. */
    List<Reservation> findByUserIdAndReservationStatusOrderByReservedAtDescIdDesc(
            Long userId, ReservationStatus reservationStatus);

    /** 어드민 목록 — 전체 사용자 대상 상태 필터 offset 페이지(정렬은 Pageable의 Sort로 지정). */
    Page<Reservation> findByReservationStatus(ReservationStatus reservationStatus, Pageable pageable);

    /** 커서 페이지네이션 첫 페이지 — 최신(id 내림차순)부터. */
    List<Reservation> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 커서 페이지네이션 다음 페이지 — 커서(id) 이전의 예약을 최신순으로. */
    List<Reservation> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long cursor, Pageable pageable);

    /** 상태 필터 첫 페이지. */
    List<Reservation> findByUserIdAndReservationStatusInOrderByIdDesc(
            Long userId, Collection<ReservationStatus> statuses, Pageable pageable);

    /** 상태 필터 다음 페이지. */
    List<Reservation> findByUserIdAndReservationStatusInAndIdLessThanOrderByIdDesc(
            Long userId, Collection<ReservationStatus> statuses, Long cursor, Pageable pageable);
}
